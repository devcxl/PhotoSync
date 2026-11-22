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
 *    display color (B2), either straight to the canvas or into the Pass D staging target.
 * 3. **Pass D** ([EffectsOutputStage]) adds the 3D LUT, film grain, post-crop vignette and dithering.
 *
 * The pipeline keeps [EditorGlRenderer] a thin lifecycle/state shell and gives the 4-tier fallback
 * matrix exactly one place to degrade.
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
    private val effectsStage = EffectsOutputStage(exposureProgram)
    private val geometryTarget = OffscreenColorTarget()
    private val textures = EditorSourceTextures()
    private val warpTexture = LiquifyWarpTexture()
    private val curveLutTexture = CurveLutTexture()
    private var geometryFallbackLogged = false

    /** True when a non-zero liquify mesh must be sampled by the next Pass A draw. */
    val isLiquifyWarpActive: Boolean
        get() = warpTexture.isWarpActive

    /** Creates every GL object. Must run on the GL thread after the EGL context exists. */
    fun init(preferEs3: Boolean) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GlCapabilities.invalidate()
        if (preferEs3) GlCapabilities.refresh()
        exposureProgram.init(preferEs3)
        geometryTarget.release()
        geometryTarget.resetDisableFlag()
        effectsStage.init(preferEs3)
        geometryFallbackLogged = false
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

    /** Texture to sample for this frame, or `0` when nothing is uploaded yet. */
    fun activeTexture(useFastProxy: Boolean): Int = textures.activeTexture(useFastProxy)

    /** Pixel dimensions of the active source texture. */
    fun activeDimensions(useFastProxy: Boolean): Pair<Int, Int> =
        textures.activeDimensions(useFastProxy)

    /**
     * Uploads everything staged. Must run on the GL thread.
     *
     * @return True when the liquify upload failed and the warp must be disabled.
     */
    fun uploadPending(preferEs3: Boolean): Boolean {
        textures.uploadPending()
        val warpFailed = warpTexture.uploadIfNeeded()
        curveLutTexture.uploadIfNeeded(preferEs3)
        effectsStage.uploadPending(preferEs3)
        return warpFailed
    }

    /** Draws one frame into the default framebuffer. */
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
            drawPassB(request, source)
            return
        }
        val frame = renderGeometryPass(request, source)
        if (request.state.effectsParams.isIdentity || !effectsStage.isReady) {
            drawPassB(request, frame)
        } else {
            drawPassD(request, frame)
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

    private fun drawPassB(request: PreviewFrameRequest, frame: FrameInput) {
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
        exposureProgram.draw(
            passBDrawParams(
                request = request,
                frame = frame,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = request.canvas.offsetX,
                offsetY = request.canvas.offsetY,
                ditherAmp = PassDEffectsMath.DITHER_AMPLITUDE
            )
        )
    }

    private fun drawPassD(request: PreviewFrameRequest, frame: FrameInput) {
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
        val drawn = effectsStage.draw(
            EffectsStageRequest(
                viewportWidth = request.viewportWidth,
                viewportHeight = request.viewportHeight,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = request.canvas.offsetX,
                offsetY = request.canvas.offsetY,
                aspect = frame.aspect,
                effects = request.state.effectsParams,
                passBDraw = passBDrawParams(
                    request = request,
                    frame = frame,
                    scaleX = 1.0f,
                    scaleY = 1.0f,
                    offsetX = 0.0f,
                    offsetY = 0.0f,
                    ditherAmp = 0.0f
                )
            )
        )
        if (!drawn) drawPassB(request, frame)
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

    private fun letterboxScale(
        request: PreviewFrameRequest,
        imageAspect: Float
    ): Pair<Float, Float> {
        val viewAspect = request.viewportWidth.toFloat() / request.viewportHeight.toFloat()
        val zoom = request.canvas.scale
            .takeIf { it.isFinite() && it > 0.0f }
            ?.coerceIn(1.0f, MAX_CANVAS_ZOOM) ?: 1.0f
        return if (imageAspect > viewAspect) {
            zoom to (viewAspect / imageAspect) * zoom
        } else {
            (imageAspect / viewAspect) * zoom to zoom
        }
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }

    private companion object {
        private const val MAX_CANVAS_ZOOM = 8.0f
    }
}
