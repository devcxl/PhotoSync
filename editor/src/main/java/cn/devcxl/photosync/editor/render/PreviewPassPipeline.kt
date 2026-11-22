package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsMath

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

    private data class FrameInput(
        val textureId: Int,
        val isLinear: Boolean,
        val aspect: Float,
        val texelWidth: Int,
        val texelHeight: Int
    )

    private val exposureProgram = ExposureGlProgram()
    private val geometryProgram = GeometryCaGlProgram()
    private val spatialDetail = SpatialDetailRenderer()
    private val effectsStage = EffectsOutputStage(exposureProgram)
    private val geometryTarget = OffscreenColorTarget()
    private val textures = EditorSourceTextures()
    private val warpTexture = LiquifyWarpTexture()
    private val curveLutTexture = CurveLutTexture()
    private var geometryFallbackLogged = false

    /** True when a non-zero liquify mesh must be sampled by the next Pass A draw. */
    val isLiquifyWarpActive: Boolean
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
        curveLutTexture.invalidate()
        textures.invalidateContext()
        if (preferEs3) geometryProgram.init()
    }

    /** Stages [source] for upload on the GL thread. */
    fun setSource(source: EditorSource) = textures.stage(source)

    /** Drops the staged source without touching GL objects. */
    fun releaseSource() = textures.clear()

    /** Stages the liquify grid and returns whether the warp is active. */
    fun stageLiquifyMesh(mesh: LiquifyMesh?): Boolean = warpTexture.stage(mesh)

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
        curveLutTexture.uploadIfNeeded(preferEs3)
        effectsStage.uploadPending(preferEs3)
        return warpFailed
    }

    /** Draws one frame (`Pass A -> Pass B -> Pass C -> Pass D`) into the default framebuffer. */
    fun drawFrame(request: PreviewFrameRequest) {
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        val source = FrameInput(
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
        val passCTexture = renderSpatialDetailIfActive(request, frame)
        val effectsActive = !request.state.effectsParams.isIdentity && effectsStage.isReady
        when {
            effectsActive && passCTexture != 0 -> drawPassDFromTexture(request, frame, passCTexture)
            effectsActive -> drawPassD(request, frame)
            passCTexture != 0 -> presentPassC(request, frame, passCTexture)
            else -> drawPassB(request, frame)
        }
    }

    private fun renderGeometryPass(request: PreviewFrameRequest, source: FrameInput): FrameInput {
        val state = request.state
        if ((!state.geometryActive && !state.liquifyWarp) || !geometryProgram.isReady) return source
        val aspect = state.outputAspectRatio.takeIf { it.isFinite() && it > 0.0f } ?: source.aspect
        val (scaleX, scaleY) = letterboxScale(request, aspect)
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
                warpTextureId = if (state.liquifyWarp) warpTexture.textureId else 0,
                warpDecodeScale = warpTexture.decodeScale,
                warpDecodeOffset = warpTexture.decodeOffset
            )
        )
        geometryTarget.unbind()
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        return FrameInput(
            textureId = geometryTarget.textureId,
            isLinear = request.isLinearInput && geometryTarget.isHalfFloatTarget,
            aspect = aspect,
            texelWidth = geometryTarget.width,
            texelHeight = geometryTarget.height
        )
    }

    private fun renderSpatialDetailIfActive(request: PreviewFrameRequest, frame: FrameInput): Int {
        val state = request.state
        if (state.useFastProxy || state.spatialParams.isZeroDrawCallShortCircuit || !spatialDetail.isReady) {
            lastPassCDrawCalls = 0
            return 0
        }
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
        val (targetWidth, targetHeight) = OffscreenColorTarget.targetSizeFor(
            request.viewportWidth, request.viewportHeight, scaleX, scaleY
        )
        val outputTex = spatialDetail.renderToTexture(
            aspect = frame.aspect,
            params = state.spatialParams,
            revision = request.graphRevision,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            allowHalfFloat = true
        ) { target ->
            target.bind()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val dither = if (target.isHalfFloatTarget) 0.0f else PassDEffectsMath.DITHER_AMPLITUDE
            exposureProgram.draw(passBDrawParams(request, frame, 1.0f, 1.0f, 0.0f, 0.0f, dither))
            target.unbind()
            true
        }
        lastPassCDrawCalls = if (outputTex != 0) spatialDetail.lastDrawCalls else 0
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        return outputTex
    }

    private fun presentPassC(request: PreviewFrameRequest, frame: FrameInput, textureId: Int) {
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
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

    private fun drawPassB(request: PreviewFrameRequest, frame: FrameInput) {
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
        exposureProgram.draw(
            passBDrawParams(
                request, frame, scaleX, scaleY,
                request.canvas.offsetX, request.canvas.offsetY, PassDEffectsMath.DITHER_AMPLITUDE
            )
        )
    }

    private fun drawPassD(request: PreviewFrameRequest, frame: FrameInput) {
        if (!effectsStage.draw(buildEffectsRequest(request, frame))) {
            drawPassB(request, frame)
        }
    }

    private fun drawPassDFromTexture(request: PreviewFrameRequest, frame: FrameInput, textureId: Int) {
        effectsStage.drawFromTexture(textureId, buildEffectsRequest(request, frame))
    }

    private fun buildEffectsRequest(request: PreviewFrameRequest, frame: FrameInput): EffectsStageRequest {
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
        return EffectsStageRequest(
            viewportWidth = request.viewportWidth,
            viewportHeight = request.viewportHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = request.canvas.offsetX,
            offsetY = request.canvas.offsetY,
            aspect = frame.aspect,
            effects = request.state.effectsParams,
            passBDraw = passBDrawParams(request, frame, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f)
        )
    }

    private fun passBDrawParams(
        request: PreviewFrameRequest,
        frame: FrameInput,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        ditherAmp: Float
    ): ExposureDrawParams {
        val state = request.state
        return ExposureDrawParams(
            textureId = frame.textureId,
            compareOriginal = request.compareOriginal,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = offsetX,
            offsetY = offsetY,
            isLinearInput = frame.isLinear,
            baseLook = state.baseLook,
            autoBrightGain = request.autoBrightGain,
            toneParams = state.toneParams,
            colorParams = state.colorParams,
            curveTextureId = curveLutTexture.textureId,
            texelWidth = frame.texelWidth,
            texelHeight = frame.texelHeight,
            ditherAmp = ditherAmp
        )
    }

    private fun letterboxScale(request: PreviewFrameRequest, imageAspect: Float): Pair<Float, Float> {
        val viewAspect = request.viewportWidth.toFloat() / request.viewportHeight.toFloat()
        return CanvasLetterbox.scale(imageAspect, viewAspect, request.canvas.scale)
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }
}
