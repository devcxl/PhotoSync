package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SpatialDetailPlanner
import cn.devcxl.photosync.editor.domain.TileUvTransform
import cn.devcxl.photosync.editor.domain.ToneShaderParams
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.lut.Lut3dTexture

/**
 * GL execution of the four physical preview passes for one offscreen render.
 *
 * Holds every program, texture and colour target of the chain and runs the same shader programs the
 * canvas and the export bake use:
 * 1. **Pass A** ([GeometryCaGlProgram]) resamples through `H^-1`, `uWarpTex` and lateral CA.
 * 2. **Pass B** ([ExposureGlProgram]) applies `BaseLook`, white balance, linear tone and display
 *    color.
 * 3. **Pass C** ([SpatialDetailGlChain]) runs the 1024px Pre-pass, noise reduction, dehaze,
 *    clarity/texture and sharpening.
 * 4. **Pass D** ([EffectsOutputGlProgram]) adds the 3D LUT, grain, post-crop vignette and dither.
 *
 * Sharing the programs does not prove pixel-identical output: every stage is verified by its own
 * result instead of being assumed. A requested graph resource that cannot be staged, a Pass C that
 * ran fewer sub-passes than planned, an incomplete target or any GL error makes the call fail so the
 * caller renders on the CPU rather than publishing a frame that silently misses an effect.
 *
 * Single use and single threaded: every GL object belongs to the EGL context of the calling thread.
 */
internal class OffscreenPreviewPasses : AutoCloseable {

    /** Capabilities of this render's headless context; never shared with the canvas or a bake. */
    private val capabilities = GlCapabilities()

    private val geometryProgram = GeometryCaGlProgram()
    private val exposureProgram = ExposureGlProgram()
    private val spatialChain = SpatialDetailGlChain(capabilities)
    private val effectsProgram = EffectsOutputGlProgram()

    private val warpTexture = WarpTexture()
    private val skinMaskTexture = SkinMaskTexture()
    private val subjectTexture = SubjectMaskTexture()
    private val subjectWeightTexture = SubjectWeightTexture()
    private val cloudTextures = CloudLayerTextures()
    private val curveLutTexture = CurveLutTexture()
    private val lutTexture = Lut3dTexture()
    private val prepassAnalyzer = PrepassAnalyzer(capabilities)

    private val geometryTarget = OffscreenColorTarget(capabilities)
    private val passBTarget = OffscreenColorTarget(capabilities)
    private val outputTarget = OffscreenColorTarget(capabilities)

    private val readback = OffscreenFrameReadback()
    private var prepassTextureId: Int = 0
    private var atmosphericLight: FloatArray = DEFAULT_ATMOSPHERIC_LIGHT
    private var cloudOpacity: Float = 0.0f

    /** `GL_MAX_TEXTURE_SIZE` of the context, from [init]. */
    var maxTextureSize: Int = 0
        private set

    /** Texture holding the last [drawMainPassB] result. */
    val passBTextureId: Int
        get() = passBTarget.textureId

    /** Samples per axis of the uploaded 3D LUT, or `0` when none is bound. */
    val lutSampleSize: Int
        get() = lutTexture.sampleSize

    /** True when a subject composite was uploaded and can be sampled by Pass D. */
    val isSubjectActive: Boolean
        get() = subjectTexture.isActive

    /**
     * Compiles every program and probes the driver on the current thread.
     *
     * @return True only when the complete Pass A/B/C/D chain can run here.
     */
    fun init(maxTextureSize: Int): Boolean {
        this.maxTextureSize = maxTextureSize.coerceAtLeast(1)
        capabilities.reset()
        capabilities.refresh()
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        val passA = geometryProgram.init()
        exposureProgram.init(preferEs3 = true)
        val passC = spatialChain.init()
        val passD = effectsProgram.init()
        val ready = passA && exposureProgram.isFullQuality && passC && passD
        if (!ready) {
            Timber.w(
                "Offscreen preview: the full shader chain is unavailable " +
                    "(passA=%s passB=%s passC=%s passD=%s)",
                passA,
                exposureProgram.isFullQuality,
                passC,
                passD
            )
        }
        return ready
    }

    /**
     * Stages and uploads the graph resources Pass A/B/C/D sample.
     *
     * @param skinMask `256 x 256` mask, or `null` when the portrait group is unused.
     * @return False when a requested resource was rejected by the driver.
     */
    fun stageGraph(
        warpField: WarpDisplacementField?,
        skinMask: FloatArray?,
        curve: CurveOps,
        lut: Lut3dSamples?,
        subject: SubjectComposite? = null,
        cloud: CloudLayerPixels? = null
    ): Boolean {
        warpTexture.stage(warpField)
        val warpUploadFailed = warpTexture.uploadIfNeeded()
        if (warpRequested(warpField) && (warpUploadFailed || !warpTexture.isWarpActive)) {
            return previewUnavailable("the liquify/portrait warp could not be uploaded")
        }

        skinMaskTexture.stage(skinMask)
        skinMaskTexture.uploadIfNeeded()
        if (skinMask != null && !skinMaskTexture.isActive) {
            return previewUnavailable("the skin mask could not be uploaded")
        }

        subjectTexture.stage(subject)
        subjectTexture.uploadIfNeeded()
        if (subject != null && !subjectTexture.isActive) {
            return previewUnavailable("the subject composite could not be uploaded")
        }
        subjectWeightTexture.stage(subject)
        subjectWeightTexture.uploadIfNeeded()
        if (subject != null && !subjectWeightTexture.isActive) {
            return previewUnavailable("the subject weight could not be uploaded")
        }

        cloudTextures.stage(cloud)
        cloudTextures.uploadIfNeeded()
        if (cloud != null && !cloudTextures.isActive) {
            // The layer is optional: skip it instead of refusing the whole frame.
            Timber.w("Offscreen preview: the cloud layer could not be uploaded; skipping it")
            cloudTextures.clear()
            cloudTextures.uploadIfNeeded()
        }
        cloudOpacity = if (cloudTextures.isActive) cloud?.opacity ?: 0.0f else 0.0f

        curveLutTexture.stage(curve)
        curveLutTexture.uploadIfNeeded(preferEs3 = true)
        if (!curveLutTexture.isUploaded) {
            return previewUnavailable("the tone curve could not be uploaded")
        }

        lutTexture.stage(lut)
        lutTexture.uploadIfNeeded(preferEs3 = true)
        if (lut != null && !lutTexture.isReady) {
            return previewUnavailable("the 3D LUT could not be uploaded")
        }
        return clearOfGlErrors("graph resource uploads")
    }

    /**
     * Analyses the 1024px Pre-pass from the frame produced by [renderProxy].
     *
     * The analysis receives the proxy-sized Pass A output, so [renderProxy] must draw Pass A for
     * `target.width x target.height` and then Pass B into [target].
     *
     * @return True when the guide texture and atmospheric light are ready for Pass C.
     */
    fun preparePrepass(
        aspect: Float,
        renderProxy: (OffscreenColorTarget) -> Boolean
    ): Boolean {
        // The Pre-pass must come from the whole frame: dehaze's atmospheric light and the
        // clarity/texture guide pyramid are global quantities.
        prepassAnalyzer.invalidate()
        val analysed = prepassAnalyzer.ensureAnalysis(
            revision = PREPASS_REVISION,
            aspect = aspect,
            allowHalfFloat = false,
            renderFrame = renderProxy
        )
        if (!analysed) return previewUnavailable("the Pass C Pre-pass could not be analysed")
        prepassTextureId = prepassAnalyzer.textureId
        atmosphericLight = prepassAnalyzer.atmosphericLight
        return true
    }

    /** Draws Pass A for the main frame into the chain's geometry target. */
    fun drawPassA(
        width: Int,
        height: Int,
        sourceTextureId: Int,
        sourceAspect: Float,
        inverseMatrix: FloatArray,
        caRedK: Float,
        caBlueK: Float,
        linearSource: Boolean
    ): Boolean {
        geometryTarget.ensureSize(width, height, allowHalfFloat = true, exactSize = true)
        if (!geometryTarget.isReady) return previewUnavailable("the Pass A target is incomplete")
        geometryTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        geometryProgram.draw(
            GeometryDrawParams(
                sourceTextureId = sourceTextureId,
                columnMajorInverseMatrix = inverseMatrix,
                caRedK = caRedK,
                caBlueK = caBlueK,
                srcAspect = sourceAspect,
                encodeSrgbWithDither = linearSource && !geometryTarget.isHalfFloatTarget,
                warpTextureId = if (warpTexture.isWarpActive) warpTexture.textureId else 0,
                warpDecodeScale = warpTexture.decodeScale,
                warpDecodeOffset = warpTexture.decodeOffset
            )
        )
        geometryTarget.unbind()
        return clearOfGlErrors("Pass A")
    }

    /** Sizes the shared Pass B target and grades the main frame into it. */
    fun drawMainPassB(
        width: Int,
        height: Int,
        linearSource: Boolean,
        autoBrightGain: Float,
        graph: EditGraph,
        toneParams: ToneShaderParams,
        colorParams: ColorGradingParams,
        subjectUvMatrix: FloatArray = IDENTITY_MATRIX
    ): Boolean {
        passBTarget.ensureSize(width, height, allowHalfFloat = true, exactSize = true)
        return drawPassBInto(
            passBTarget, width, height, linearSource, autoBrightGain, graph, toneParams, colorParams, subjectUvMatrix
        )
    }

    /** Grades into the Pre-pass target, which must keep the format [PrepassAnalyzer] allocated. */
    fun drawPrepassPassB(
        target: OffscreenColorTarget,
        linearSource: Boolean,
        autoBrightGain: Float,
        graph: EditGraph,
        toneParams: ToneShaderParams,
        colorParams: ColorGradingParams,
        subjectUvMatrix: FloatArray = IDENTITY_MATRIX
    ): Boolean = drawPassBInto(
        target, target.width, target.height, linearSource, autoBrightGain, graph, toneParams, colorParams,
        subjectUvMatrix
    )

    /**
     * Runs the planned Pass C sub-passes on [passBTextureId].
     *
     * @return The texture holding the Pass C output, `0` when the graph needs no Pass C, or `null`
     *   when Pass C was required but could not run.
     */
    fun runSpatialDetail(
        width: Int,
        height: Int,
        spatialParams: SpatialDetailParams,
        fullResolutionShortEdge: Int,
        graph: EditGraph,
        subjectUvMatrix: FloatArray = IDENTITY_MATRIX
    ): Int? {
        val plan = SpatialDetailPlanner.plan(
            params = spatialParams,
            prepassAvailable = prepassTextureId != 0,
            skinMaskAvailable = skinMaskTexture.isActive,
            subjectMaskAvailable = subjectWeightTexture.isActive
        )
        if (plan.stages.isEmpty()) return 0
        val output = spatialChain.run(
            inputTextureId = passBTarget.textureId,
            width = width,
            height = height,
            fullResolutionShortEdge = fullResolutionShortEdge,
            params = spatialParams,
            plan = plan,
            prepassTextureId = prepassTextureId,
            skinMaskTextureId = if (skinMaskTexture.isActive) skinMaskTexture.textureId else 0,
            atmosphericLight = atmosphericLight,
            guideTransform = TileUvTransform.IDENTITY,
            subjectBokeh = subjectBokehParams(
                graph = graph,
                width = width,
                height = height,
                fullResolutionShortEdge = fullResolutionShortEdge,
                subjectUvMatrix = subjectUvMatrix
            )
        )
        // The chain returns its input when nothing ran and an *intermediate* texture when a later
        // ping-pong target failed, so comparing against the input is not enough. Counting the draws
        // is what proves every planned stage reached the output.
        if (spatialChain.lastDrawCalls != plan.stages.size) {
            previewUnavailable(
                "Pass C ran ${spatialChain.lastDrawCalls} of ${plan.stages.size} stages"
            )
            return null
        }
        if (!clearOfGlErrors("Pass C")) return null
        if (output == passBTarget.textureId) {
            previewUnavailable("Pass C produced no output")
            return null
        }
        return output
    }

    /** Applies the Pass D effects and writes a dithered `RGBA8` frame into the output target. */
    fun drawPassD(
        width: Int,
        height: Int,
        inputTextureId: Int,
        effectsParams: PassDEffectsParams,
        subjectMode: Int = 0,
        subjectColorRgb: Int = 0xFFFFFF,
        subjectUvMatrix: FloatArray? = null,
        subjectCheckerboard: Boolean = true
    ): Boolean {
        outputTarget.ensureSize(width, height, allowHalfFloat = false, exactSize = true)
        if (!outputTarget.isReady) return previewUnavailable("the Pass D target is incomplete")
        val subject = if (subjectMode != 0 && subjectTexture.isActive) {
            SubjectDrawParams(
                textureId = subjectTexture.textureId,
                mode = subjectMode,
                solidColorRgb = subjectColorRgb,
                localSpace = false,
                uvMatrix = subjectUvMatrix ?: IDENTITY_MATRIX,
                warpTextureId = if (warpTexture.isWarpActive) warpTexture.textureId else 0,
                warpScale = warpTexture.decodeScale,
                warpOffset = warpTexture.decodeOffset,
                checkerboard = subjectCheckerboard
            )
        } else {
            null
        }
        outputTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        effectsProgram.draw(
            EffectsDrawParams(
                textureId = inputTextureId,
                lutTextureId = if (lutTexture.isReady) lutTexture.textureId else 0,
                compareOriginal = false,
                scaleX = 1.0f,
                scaleY = 1.0f,
                offsetX = 0.0f,
                offsetY = 0.0f,
                effects = effectsParams,
                ditherAmp = PassDEffectsMath.DITHER_AMPLITUDE,
                tileUv = TileUvTransform.IDENTITY,
                pixelScaleX = width.toFloat(),
                pixelScaleY = height.toFloat(),
                flipVerticalForScreen = false,
                subject = subject
            )
        )
        outputTarget.unbind()
        return clearOfGlErrors("Pass D")
    }

    /**
     * Reads the Pass D frame back as top-down ARGB pixels.
     *
     * @return The pixels, or `null` when the driver reports an error.
     */
    fun readFrame(width: Int, height: Int): IntArray? = readback.read(outputTarget, width, height)

    /** Releases every GL object of this render. Must run with the context still current. */
    override fun close() {
        geometryProgram.release()
        exposureProgram.release()
        spatialChain.release()
        effectsProgram.release()
        warpTexture.release()
        skinMaskTexture.release()
        subjectTexture.release()
        subjectWeightTexture.release()
        cloudTextures.release()
        curveLutTexture.release()
        lutTexture.release()
        prepassAnalyzer.release()
        geometryTarget.release()
        passBTarget.release()
        outputTarget.release()
        readback.release()
        capabilities.reset()
    }

    private fun drawPassBInto(
        target: OffscreenColorTarget,
        width: Int,
        height: Int,
        linearSource: Boolean,
        autoBrightGain: Float,
        graph: EditGraph,
        toneParams: ToneShaderParams,
        colorParams: ColorGradingParams,
        subjectUvMatrix: FloatArray
    ): Boolean {
        if (!target.isReady) return previewUnavailable("the Pass B target is incomplete")
        target.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        exposureProgram.draw(
            ExposureDrawParams(
                textureId = geometryTarget.textureId,
                compareOriginal = false,
                scaleX = 1.0f,
                scaleY = 1.0f,
                offsetX = 0.0f,
                offsetY = 0.0f,
                isLinearInput = linearSource && geometryTarget.isHalfFloatTarget,
                baseLook = graph.baseLook,
                autoBrightGain = autoBrightGain,
                toneParams = toneParams,
                colorParams = colorParams,
                curveTextureId = curveLutTexture.textureId,
                texelWidth = target.width,
                texelHeight = target.height,
                ditherAmp = if (target.isHalfFloatTarget) 0.0f else PassDEffectsMath.DITHER_AMPLITUDE,
                flipVerticalForScreen = false,
                subject = graph.subject,
                subjectWeightTextureId = subjectWeightTexture.activeTextureId,
                subjectLocalSpace = false,
                subjectUvMatrix = subjectUvMatrix,
                subjectWarpTextureId = if (warpTexture.isWarpActive) warpTexture.textureId else 0,
                subjectWarpScale = warpTexture.decodeScale,
                subjectWarpOffset = warpTexture.decodeOffset,
                cloudLayerTextureId = if (cloudTextures.isActive) cloudTextures.imageTextureId else 0,
                cloudMaskTextureId = if (cloudTextures.isActive) cloudTextures.maskTextureId else 0,
                cloudOpacity = cloudOpacity
            )
        )
        target.unbind()
        return clearOfGlErrors("Pass B")
    }

    private fun subjectBokehParams(
        graph: EditGraph,
        width: Int,
        height: Int,
        fullResolutionShortEdge: Int,
        subjectUvMatrix: FloatArray
    ): SubjectBokehDrawParams? = SubjectBokehPlanner.params(
        blur = graph.subject.blur,
        shortEdge = minOf(width, height),
        maskTextureId = subjectWeightTexture.activeTextureId,
        localSpace = false,
        uvMatrix = subjectUvMatrix,
        fullShortEdge = fullResolutionShortEdge,
        warpTextureId = if (warpTexture.isWarpActive) warpTexture.textureId else 0,
        warpScale = warpTexture.decodeScale,
        warpOffset = warpTexture.decodeOffset
    )

    /**
     * Drains every pending GL error.
     *
     * Called once at the start of a render so later `glGetError` checks belong to this render and a
     * stale error from the context hand-over cannot be mistaken for a pass failure (or hide one).
     */
    fun resetErrorState() {
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // Drain; the previous owner's errors are not ours to report.
        }
    }

    private fun warpRequested(field: WarpDisplacementField?): Boolean =
        field != null && !field.isZero()

    private fun clearOfGlErrors(stage: String): Boolean = GlErrorGuard.clear(stage)

    private companion object {
        /** A single-use chain analyses exactly one graph revision. */
        const val PREPASS_REVISION = 1

        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )

        val DEFAULT_ATMOSPHERIC_LIGHT = floatArrayOf(1.0f, 1.0f, 1.0f)
    }
}
