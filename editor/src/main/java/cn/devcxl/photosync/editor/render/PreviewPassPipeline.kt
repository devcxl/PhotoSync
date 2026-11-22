package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.SpatialDetailPlanner
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Owns every GL object of the preview chain and executes one frame:
 *
 * 1. **Pass A** ([GeometryCaGlProgram]) resamples the source through `H^-1`, the liquify warp and
 *    lateral CA into [OffscreenColorTarget].
 * 2. **Pass B** ([ExposureGlProgram]) applies `BaseLook`, Bradford WB, linear/display tone (B1) and
 *    display color (B2).
 * 3. **Pass C** ([SpatialDetailRenderer]) runs the 1024px Pre-pass + spatial-detail chain when
 *    non-neutral outside a fast proxy drag, otherwise short-circuits at **0 FBO draw calls**.
 * 4. **Pass D** ([EffectsOutputStage]) adds the 3D LUT, film grain, post-crop vignette and dithering.
 */
internal class PreviewPassPipeline {

    private val exposureProgram = ExposureGlProgram()
    private val geometryProgram = GeometryCaGlProgram()
    private val spatialDetail = SpatialDetailRenderer()
    private val effectsStage = EffectsOutputStage(exposureProgram)
    private val geometryTarget = OffscreenColorTarget()
    private val textures = EditorSourceTextures()
    private val warpTexture = WarpTexture()
    private val skinMaskTexture = SkinMaskTexture()
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
        GlCapabilities.invalidate()
        if (preferEs3) GlCapabilities.refresh()
        exposureProgram.init(preferEs3)
        geometryTarget.release()
        geometryTarget.resetDisableFlag()
        if (preferEs3) spatialDetail.init() else spatialDetail.release()
        effectsStage.init(preferEs3)
        geometryFallbackLogged = false
        lastPassCDrawCalls = 0
        warpTexture.invalidate()
        skinMaskTexture.invalidate()
        curveLutTexture.invalidate()
        textures.invalidateContext()
        if (preferEs3) geometryProgram.init()
    }

    /** Stages [source] for upload on the GL thread. */
    fun setSource(source: EditorSource) = textures.stage(source)

    /** Drops the staged source without touching GL objects. */
    fun releaseSource() = textures.clear()

    /**
     * Stages the composed `uWarpTex` field (face MLS + body + liquify) and returns whether a warp
     * is now active.
     */
    fun stageWarpField(field: WarpDisplacementField?): Boolean = warpTexture.stage(field)

    /** Stages the portrait `skinMask`; `null` unbinds the Stage 0 / sharpening suppression mask. */
    fun stageSkinMask(mask: FloatArray?): Boolean = skinMaskTexture.stage(mask)

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

    /** Uploads everything staged on the GL thread. */
    fun uploadPending(preferEs3: Boolean): Boolean {
        textures.uploadPending()
        val warpFailed = warpTexture.uploadIfNeeded()
        skinMaskTexture.uploadIfNeeded()
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
        if (GlCapabilities.isMinimalFallback) {
            lastPassCDrawCalls = 0
            drawPassB(request, frame)
            return
        }
        val passCTexture = renderSpatialDetailIfActive(request, frame)
        val effectsActive = !request.state.effectsParams.isIdentity && effectsStage.isReady
        when {
            effectsActive && passCTexture != 0 -> drawPassDFromTexture(request, frame, passCTexture)
            effectsActive -> drawPassD(request, frame)
            passCTexture != 0 -> presentPassC(request, frame, passCTexture)
            else -> drawPassB(request, frame)
        }
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
        val plan = SpatialDetailPlanner.plan(
            state.spatialParams,
            prepassAvailable = true,
            skinMaskAvailable = skinMaskAvailable
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
            allowHalfFloat = true
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
                ditherAmp = PassDEffectsMath.DITHER_AMPLITUDE
            )
        )
    }

    private fun drawPassD(request: PreviewFrameRequest, frame: PreviewFrameInput) {
        if (!effectsStage.draw(PreviewDrawRequests.effects(request, frame, curveLutTexture.textureId))) {
            drawPassB(request, frame)
        }
    }

    private fun drawPassDFromTexture(
        request: PreviewFrameRequest,
        frame: PreviewFrameInput,
        textureId: Int
    ) {
        effectsStage.drawFromTexture(
            textureId,
            PreviewDrawRequests.effects(request, frame, curveLutTexture.textureId)
        )
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }
}
