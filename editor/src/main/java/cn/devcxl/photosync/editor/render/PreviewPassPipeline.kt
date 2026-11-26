package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.SpatialDetailPlanner
import cn.devcxl.photosync.editor.domain.TileUvTransform
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Owns every GL object of the preview chain and executes one frame:
 *
 * 1. **Pass A** ([GeometryCaGlProgram]) resamples the source through `H^-1`, the composed warp
 *    (`uWarpTex` = face MLS + body + liquify) and lateral CA into [OffscreenColorTarget].
 * 2. **Pass B** ([ExposureGlProgram]) applies `BaseLook`, Bradford WB, linear/display tone (B1) and
 *    display color (B2).
 * 3. **Pass C** ([SpatialDetailRenderer]) runs the 1024px Pre-pass + spatial-detail chain
 *    (skin smoothing, NR, dehaze, clarity/texture, sharpening) when non-neutral outside a fast
 *    proxy drag, otherwise short-circuits at **0 FBO draw calls**.
 * 4. **Pass D** ([EffectsOutputStage]) composites the subject cut-out, then adds the 3D LUT, film
 *    grain, post-crop vignette and dithering.
 */
internal class PreviewPassPipeline {

    /**
     * Capabilities of the EGL context this pipeline currently renders on.
     *
     * Created with the pipeline, re-probed by [init] on every surface (re)creation, and never shared
     * with the headless bake or a previous context.
     */
    internal val capabilities = GlCapabilities()

    private val exposureProgram = ExposureGlProgram()
    private val geometryProgram = GeometryCaGlProgram()
    private val spatialDetail = SpatialDetailRenderer(capabilities)
    private val effectsStage = EffectsOutputStage(exposureProgram, capabilities)
    private val geometryTarget = OffscreenColorTarget(capabilities)
    private val textures = EditorSourceTextures()
    private val compareTextures = EditorSourceTextures()
    private val warpTexture = WarpTexture()
    private val skinMaskTexture = SkinMaskTexture()
    private val subjectTexture = SubjectMaskTexture()
    private val subjectWeightTexture = SubjectWeightTexture()
    private val cloudTextures = CloudLayerTextures()
    private val skyTextures = SkyLayerTextures()
    private val curveLutTexture = CurveLutTexture()
    private var geometryFallbackLogged = false

    /** True when a non-zero composed warp must be sampled by the next Pass A draw. */
    val isWarpActive: Boolean
        get() = warpTexture.isWarpActive

    /** FBO draw calls Pass C issued in the most recent frame. */
    var lastPassCDrawCalls: Int = 0
        private set

    /** Creates every GL object. Must run on the GL thread after the EGL context exists. */
    fun init(preferEs3: Boolean) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        // A (re)created context may have a different driver, so drop the previous facts and probe
        // before any program is compiled: compile failures are recorded on top of the fresh tier.
        capabilities.reset()
        if (preferEs3) capabilities.refresh()
        exposureProgram.init(preferEs3)
        geometryTarget.release()
        geometryTarget.resetDisableFlag()
        if (preferEs3) spatialDetail.init() else spatialDetail.release()
        effectsStage.init(preferEs3)
        geometryFallbackLogged = false
        lastPassCDrawCalls = 0
        warpTexture.invalidate()
        skinMaskTexture.invalidate()
        subjectTexture.invalidate()
        subjectWeightTexture.invalidate()
        cloudTextures.invalidate()
        skyTextures.invalidate()
        curveLutTexture.invalidate()
        textures.invalidateContext()
        compareTextures.invalidateContext()
        if (preferEs3 && !geometryProgram.init()) {
            capabilities.markMinimalFallback("Pass A GeometryCa")
        }
    }

    /** Stages [source] for upload on the GL thread. */
    fun setSource(source: EditorSource) = textures.stage(source)

    /** Stages the untouched decode the Before/After comparison must show. */
    fun setCompareSource(source: EditorSource) = compareTextures.stage(source)

    /** Drops the staged comparison source, so compare falls back to the edited source. */
    fun clearCompareSource() = compareTextures.clear()

    /** Drops the staged source without touching GL objects. */
    fun releaseSource() {
        textures.clear()
        compareTextures.clear()
    }

    /** Stages the composed `uWarpTex` field (face MLS + body + liquify). */
    fun stageWarpField(field: WarpDisplacementField?): Boolean = warpTexture.stage(field)

    /** Stages the portrait `skinMask`; `null` unbinds the Stage 0 / sharpening suppression mask. */
    fun stageSkinMask(mask: FloatArray?): Boolean = skinMaskTexture.stage(mask)

    /** Stages the source-space subject composite, or `null` to disable the Pass D subject stage. */
    fun stageSubjectComposite(composite: SubjectComposite?) {
        subjectTexture.stage(composite)
        subjectWeightTexture.stage(composite)
    }

    /** True when a composite is staged or uploaded, so [EditorGlRenderer] may activate the stage. */
    val isSubjectCompositeAvailable: Boolean
        get() = subjectTexture.isActive || subjectTexture.isStaged

    /** Uploaded subject texture name, or `0` before the first upload. */
    val subjectTextureId: Int
        get() = subjectTexture.textureId

    /** Uploaded refined subject alpha, or `0` when the subject stage has no mask. */
    val subjectWeightTextureId: Int
        get() = subjectWeightTexture.activeTextureId

    /** Stages the Post-Pass A cloud layer pixels, or `null` to unbind the layer. */
    fun stageCloudLayer(pixels: CloudLayerPixels?) = cloudTextures.stage(pixels)

    /** Stages the decoded P3 sky layer, or `null` to render the original sky. */
    fun stageSkyLayer(layer: SkyLayerPixels?) = skyTextures.stage(layer)

    /** Uploaded sky mask texture, or `0` when no sky may be composited. */
    val skyMaskTextureId: Int
        get() = if (skyTextures.isActive) skyTextures.maskTextureId else 0

    /** Uploaded replacement sky image, or `0` for a procedural sky. */
    val skyAssetTextureId: Int
        get() = if (skyTextures.isActive && skyTextures.hasAsset) skyTextures.assetTextureId else 0

    /** Uploaded cloud layer texture, or `0` when no layer may be sampled. */
    val cloudLayerTextureId: Int
        get() = if (cloudTextures.isActive) cloudTextures.imageTextureId else 0

    /** Uploaded cloud coverage texture, or `0` for a whole-frame layer. */
    val cloudMaskTextureId: Int
        get() = if (cloudTextures.isActive) cloudTextures.maskTextureId else 0

    /** Uploaded `uWarpTex`, or `0` when the composed warp is neutral. */
    val warpTextureId: Int
        get() = if (warpTexture.isWarpActive) warpTexture.textureId else 0

    /** Decode scale of the uploaded warp grid. */
    val warpDecodeScale: Float
        get() = warpTexture.decodeScale

    /** Decode offset of the uploaded warp grid. */
    val warpDecodeOffset: Float
        get() = warpTexture.decodeOffset

    /** Stages the 1D tone-curve LUT for the next frame. */
    fun stageCurve(curve: CurveOps) = curveLutTexture.stage(curve)

    /** Stages the 3D LUT samples for the next frame. */
    fun stageLut(samples: Lut3dSamples?) = effectsStage.stageLut(samples)

    /** Marks the 1024px Pass C Pre-pass dirty after a graph change. */
    fun invalidatePrepass() = spatialDetail.invalidatePrepass()

    /** Texture to sample for this frame, or `0` when nothing is uploaded yet. */
    fun activeTexture(useFastProxy: Boolean): Int = textures.activeTexture(useFastProxy)

    /** Pixel dimensions of the active source texture. */
    fun activeDimensions(useFastProxy: Boolean): Pair<Int, Int> =
        textures.activeDimensions(useFastProxy)

    /** Texture of the un-edited decode, or `0` when compare must fall back to [activeTexture]. */
    fun compareActiveTexture(useFastProxy: Boolean): Int =
        compareTextures.activeTexture(useFastProxy)

    /** Pixel dimensions of the compare texture. */
    fun compareActiveDimensions(useFastProxy: Boolean): Pair<Int, Int> =
        compareTextures.activeDimensions(useFastProxy)

    /** Uploads everything staged on the GL thread. */
    fun uploadPending(preferEs3: Boolean): Boolean {
        textures.uploadPending()
        compareTextures.uploadPending()
        val warpFailed = warpTexture.uploadIfNeeded()
        skinMaskTexture.uploadIfNeeded()
        subjectTexture.uploadIfNeeded()
        subjectWeightTexture.uploadIfNeeded()
        cloudTextures.uploadIfNeeded()
        skyTextures.uploadIfNeeded()
        curveLutTexture.uploadIfNeeded(preferEs3)
        effectsStage.uploadPending(preferEs3)
        return warpFailed
    }

    /** Draws one frame (`Pass A -> Pass B -> Pass C -> Pass D`) into the default framebuffer. */
    fun drawFrame(request: PreviewFrameRequest) {
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        val source = PreviewFrameInput(
            textureId = request.sourceTextureId,
            isLinear = request.isLinearInput,
            aspect = request.sourceAspect,
            texelWidth = request.sourceWidth,
            texelHeight = request.sourceHeight
        )
        if (request.compareOriginal) {
            lastPassCDrawCalls = 0
            drawPassB(request, source)
            return
        }
        val frame = renderGeometryPass(request, source)
        if (capabilities.isMinimalFallback) {
            lastPassCDrawCalls = 0
            drawPassB(request, frame)
            return
        }
        val passCTexture = renderSpatialDetailIfActive(request, frame)
        val subject = subjectDrawParams(request)
        val sky = skyDrawParams(request)
        val effectsActive = (!request.state.effectsParams.isIdentity ||
            subject != null ||
            sky != null) && effectsStage.isReady
        when {
            effectsActive && passCTexture != 0 ->
                drawPassDFromTexture(request, frame, passCTexture, subject, sky)
            effectsActive -> drawPassD(request, frame, subject, sky)
            passCTexture != 0 -> presentPassC(request, frame, passCTexture)
            else -> drawPassB(request, frame)
        }
    }

    /**
     * Builds the Pass B/D sky bundle from the staged layer and the current frame state.
     *
     * The mask is sampled through the Pass A inverse matrix and the composed warp, so a crop, a
     * liquify stroke or a body reshape moves the sky boundary together with the pixels.
     */
    private fun skyDrawParams(request: PreviewFrameRequest): SkyDrawParams? {
        val state = request.state
        val blend = state.skyBlend ?: return null
        val maskId = skyMaskTextureId
        if (maskId == 0) return null
        return SkyDrawParams(
            maskTextureId = maskId,
            assetTextureId = skyAssetTextureId,
            procedural = state.skyProcedural,
            blend = blend,
            stats = state.skyStats,
            relight = state.skyOps.relight,
            uvMatrix = if (state.geometryActive) state.geometryInverseMatrix else SkyDrawParams.IDENTITY_MATRIX,
            warpTextureId = if (state.warpActive) warpTexture.textureId else 0,
            warpScale = warpTexture.decodeScale,
            warpOffset = warpTexture.decodeOffset,
            tileUv = TileUvTransform.IDENTITY,
            // `edgeShift` is expressed in full-resolution source pixels; using the viewport here
            // would make a 2 px shrink mean a different amount at every zoom level.
            pixelScaleX = request.fullResolutionShortEdge.coerceAtLeast(1).toFloat(),
            pixelScaleY = request.fullResolutionShortEdge.coerceAtLeast(1).toFloat()
        )
    }

    private fun subjectDrawParams(request: PreviewFrameRequest): SubjectDrawParams? {
        val state = request.state
        if (state.subjectMode == 0 || request.subjectTextureId == 0) return null
        return SubjectDrawParams(
            textureId = request.subjectTextureId,
            mode = state.subjectMode,
            solidColorRgb = state.subjectColorRgb,
            localSpace = false,
            uvMatrix = state.subjectUvMatrix,
            warpTextureId = if (state.subjectWarpActive) warpTexture.textureId else 0,
            warpScale = warpTexture.decodeScale,
            warpOffset = warpTexture.decodeOffset,
            checkerboard = state.subjectMode == SUBJECT_MODE_TRANSPARENT
        )
    }

    private fun renderGeometryPass(request: PreviewFrameRequest, source: PreviewFrameInput): PreviewFrameInput {
        val state = request.state
        if ((!state.geometryActive && !state.warpActive) || !geometryProgram.isReady) return source
        val aspect = state.outputAspectRatio.takeIf { it.isFinite() && it > 0.0f } ?: source.aspect
        val (scaleX, scaleY) = PreviewDrawRequests.letterboxScale(request, aspect)
        val (targetWidth, targetHeight) = OffscreenColorTarget.targetSizeFor(
            request.viewportWidth, request.viewportHeight, scaleX, scaleY
        )
        geometryTarget.ensureSize(targetWidth, targetHeight, allowHalfFloat = true)
        if (!geometryTarget.isReady) {
            logGeometryFallbackOnce()
            return source
        }
        geometryTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        geometryProgram.draw(
            GeometryDrawParams(
                sourceTextureId = source.textureId,
                columnMajorInverseMatrix = state.geometryInverseMatrix,
                caRedK = state.lateralCaRedK,
                caBlueK = state.lateralCaBlueK,
                srcAspect = request.sourceAspect,
                encodeSrgbWithDither = request.isLinearInput && !geometryTarget.isHalfFloatTarget,
                warpTextureId = if (state.warpActive) warpTexture.textureId else 0,
                warpDecodeScale = warpTexture.decodeScale,
                warpDecodeOffset = warpTexture.decodeOffset
            )
        )
        geometryTarget.unbind()
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        return PreviewFrameInput(
            textureId = geometryTarget.textureId,
            isLinear = request.isLinearInput && geometryTarget.isHalfFloatTarget,
            aspect = aspect,
            texelWidth = geometryTarget.width,
            texelHeight = geometryTarget.height
        )
    }

    private fun renderSpatialDetailIfActive(request: PreviewFrameRequest, frame: PreviewFrameInput): Int {
        val state = request.state
        val skinMaskAvailable = skinMaskTexture.isActive
        val subjectMaskAvailable = subjectMaskTextureAvailable()
        val plan = SpatialDetailPlanner.plan(
            state.spatialParams,
            prepassAvailable = true,
            skinMaskAvailable = skinMaskAvailable,
            subjectMaskAvailable = subjectMaskAvailable
        )
        if (state.useFastProxy || plan.isZeroDrawCallShortCircuit || !spatialDetail.isReady) {
            lastPassCDrawCalls = 0
            return 0
        }
        val (scaleX, scaleY) = PreviewDrawRequests.letterboxScale(request, frame.aspect)
        val (targetWidth, targetHeight) = OffscreenColorTarget.targetSizeFor(
            request.viewportWidth, request.viewportHeight, scaleX, scaleY
        )
        val outputTex = spatialDetail.renderToTexture(
            aspect = frame.aspect,
            params = state.spatialParams,
            revision = request.graphRevision,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fullResolutionShortEdge = request.fullResolutionShortEdge,
            skinMaskTextureId = if (skinMaskTexture.isActive) skinMaskTexture.textureId else 0,
            allowHalfFloat = true,
            subjectBokeh = subjectBokehParams(request, targetWidth, targetHeight)
        ) { target ->
            target.bind()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val dither = if (target.isHalfFloatTarget) 0.0f else PassDEffectsMath.DITHER_AMPLITUDE
            exposureProgram.draw(
                PreviewDrawRequests.offscreenPassB(
                    request = request,
                    frame = frame,
                    curveTextureId = curveLutTexture.textureId,
                    ditherAmp = dither
                )
            )
            target.unbind()
            true
        }
        lastPassCDrawCalls = if (outputTex != 0) spatialDetail.lastDrawCalls else 0
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        return outputTex
    }

    private fun subjectMaskTextureAvailable(): Boolean =
        subjectWeightTexture.isActive && subjectWeightTexture.activeTextureId != 0

    private fun subjectBokehParams(
        request: PreviewFrameRequest,
        targetWidth: Int,
        targetHeight: Int
    ): SubjectBokehDrawParams? {
        val state = request.state
        if (!state.subjectOps.hasBackgroundBlur() || !subjectMaskTextureAvailable()) return null
        return SubjectBokehPlanner.params(
            blur = state.subjectOps.blur,
            shortEdge = minOf(targetWidth, targetHeight),
            maskTextureId = subjectWeightTexture.activeTextureId,
            localSpace = false,
            uvMatrix = state.subjectUvMatrix,
            fullShortEdge = request.fullResolutionShortEdge,
            warpTextureId = if (state.subjectWarpActive) warpTexture.textureId else 0,
            warpScale = warpTexture.decodeScale,
            warpOffset = warpTexture.decodeOffset
        )
    }

    private fun presentPassC(request: PreviewFrameRequest, frame: PreviewFrameInput, textureId: Int) {
        val (scaleX, scaleY) = PreviewDrawRequests.letterboxScale(request, frame.aspect)
        spatialDetail.present(
            textureId = textureId,
            viewportWidth = request.viewportWidth,
            viewportHeight = request.viewportHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = request.canvas.offsetX,
            offsetY = request.canvas.offsetY
        )
    }

    private fun drawPassB(request: PreviewFrameRequest, frame: PreviewFrameInput) {
        exposureProgram.draw(
            PreviewDrawRequests.viewportPassB(
                request = request,
                frame = frame,
                curveTextureId = curveLutTexture.textureId,
                ditherAmp = PassDEffectsMath.DITHER_AMPLITUDE,
                sky = skyDrawParams(request)
            )
        )
    }

    private fun drawPassD(
        request: PreviewFrameRequest,
        frame: PreviewFrameInput,
        subject: SubjectDrawParams?,
        sky: SkyDrawParams?
    ) {
        val stageRequest = PreviewDrawRequests.effects(
            request,
            frame,
            curveLutTexture.textureId,
            subject,
            sky
        )
        if (!effectsStage.draw(stageRequest)) {
            drawPassB(request, frame)
        }
    }

    private fun drawPassDFromTexture(
        request: PreviewFrameRequest,
        frame: PreviewFrameInput,
        textureId: Int,
        subject: SubjectDrawParams?,
        sky: SkyDrawParams?
    ) {
        effectsStage.drawFromTexture(
            textureId,
            PreviewDrawRequests.effects(request, frame, curveLutTexture.textureId, subject, sky)
        )
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }

    private companion object {
        const val SUBJECT_MODE_TRANSPARENT = 2
    }
}
