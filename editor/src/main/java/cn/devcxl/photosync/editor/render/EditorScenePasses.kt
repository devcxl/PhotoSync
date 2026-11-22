package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

/**
 * Owns the `Pass A` and `Pass B` draws of the preview pipeline.
 *
 * Splitting them out of the renderer keeps the GL orchestration and the pass maths in separate
 * files: this class knows about homographies, lateral CA, the liquify warp and the tone/color
 * uniforms, while [EditorGlRenderer] only sequences `Pass A → Pass B → Pass C → present`.
 */
internal class EditorScenePasses {

    private val exposureProgram = ExposureGlProgram()
    private val geometryProgram = GeometryCaGlProgram()
    private val geometryTarget = PassAFrameBuffer()
    private var geometryFallbackLogged: Boolean = false

    /** Compiles both programs for a fresh EGL context. */
    fun init(preferEs3: Boolean) {
        exposureProgram.init(preferEs3)
        geometryTarget.release()
        geometryTarget.resetDisableFlag()
        geometryFallbackLogged = false
        if (preferEs3) geometryProgram.init()
    }

    /**
     * Renders `Pass A` when the graph needs geometry, lateral CA or a liquify warp.
     *
     * @return The display-domain source `Pass B` must sample; the untouched source when geometry is
     *   neutral or the offscreen target is unavailable.
     */
    fun renderPassA(
        state: RenderFrameState,
        warp: WarpBinding,
        sourceTexture: Int,
        sourceAspect: Float,
        linearInput: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        canvasZoom: Float
    ): PassAOutput {
        val plain = PassAOutput(sourceTexture, linearInput, sourceWidth, sourceHeight, sourceAspect)
        if ((!state.geometryActive && !state.liquifyWarp) || !geometryProgram.isReady) return plain
        val aspect = state.outputAspectRatio.takeIf { it.isFinite() && it > 0.0f } ?: sourceAspect
        if (ensureGeometryTarget(aspect, viewportWidth, viewportHeight, canvasZoom) == null) {
            logGeometryFallbackOnce()
            return plain
        }
        geometryTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        geometryProgram.draw(
            GeometryDrawParams(
                sourceTextureId = sourceTexture,
                columnMajorInverseMatrix = state.geometryInverseMatrix,
                caRedK = state.lateralCaRedK,
                caBlueK = state.lateralCaBlueK,
                srcAspect = sourceAspect,
                encodeSrgbWithDither = linearInput && !geometryTarget.isHalfFloatTarget,
                warpTextureId = if (state.liquifyWarp) warp.textureId else 0,
                warpDecodeScale = warp.decodeScale,
                warpDecodeOffset = warp.decodeOffset
            )
        )
        geometryTarget.unbind()
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        return PassAOutput(
            textureId = geometryTarget.textureId,
            isLinear = linearInput && geometryTarget.isHalfFloatTarget,
            width = geometryTarget.width,
            height = geometryTarget.height,
            aspect = aspect
        )
    }

    /** Draws `Pass B` into [target] at full bleed, i.e. without the canvas letterbox transform. */
    fun drawPassBInto(
        target: PassAFrameBuffer,
        passA: PassAOutput,
        state: RenderFrameState,
        autoBrightGain: Float,
        curveTextureId: Int
    ) {
        target.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawExposure(
            passA = passA,
            state = state,
            comparingOriginal = false,
            scaleX = 1.0f,
            scaleY = 1.0f,
            offsetX = 0.0f,
            offsetY = 0.0f,
            autoBrightGain = autoBrightGain,
            curveTextureId = curveTextureId
        )
        target.unbind()
    }

    /** Draws `Pass B` (or the untouched source while comparing) straight to the canvas. */
    fun drawToCanvas(
        passA: PassAOutput,
        state: RenderFrameState,
        comparingOriginal: Boolean,
        viewport: CanvasViewportTransform,
        viewportWidth: Int,
        viewportHeight: Int,
        autoBrightGain: Float,
        curveTextureId: Int
    ) {
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val (scaleX, scaleY) = CanvasLetterbox.scale(passA.aspect, viewAspect, viewport.scale)
        drawExposure(
            passA = passA,
            state = state,
            comparingOriginal = comparingOriginal,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = viewport.offsetX,
            offsetY = viewport.offsetY,
            autoBrightGain = autoBrightGain,
            curveTextureId = curveTextureId
        )
    }

    private fun ensureGeometryTarget(
        aspect: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        canvasZoom: Float
    ): PassAFrameBuffer? {
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val (scaleX, scaleY) = CanvasLetterbox.scale(aspect, viewAspect, canvasZoom)
        val (targetWidth, targetHeight) = CanvasLetterbox.targetSize(
            viewportWidth, viewportHeight, scaleX, scaleY, MAX_TARGET_EDGE_PX
        )
        geometryTarget.ensureSize(targetWidth, targetHeight, allowHalfFloat = true)
        return geometryTarget.takeIf { it.isReady }
    }

    private fun drawExposure(
        passA: PassAOutput,
        state: RenderFrameState,
        comparingOriginal: Boolean,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        autoBrightGain: Float,
        curveTextureId: Int
    ) {
        exposureProgram.draw(
            ExposureDrawParams(
                textureId = passA.textureId,
                compareOriginal = comparingOriginal,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = offsetX,
                offsetY = offsetY,
                isLinearInput = passA.isLinear,
                baseLook = state.baseLook,
                autoBrightGain = autoBrightGain,
                toneParams = state.toneParams,
                colorParams = state.colorParams,
                curveTextureId = curveTextureId,
                texelWidth = passA.width,
                texelHeight = passA.height
            )
        )
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }

    private companion object {
        private const val MAX_TARGET_EDGE_PX = 2048
    }
}

/** The liquify displacement texture and the decode window the shader must apply to it. */
internal data class WarpBinding(
    val textureId: Int,
    val decodeScale: Float,
    val decodeOffset: Float
)
