package cn.devcxl.photosync.editor.render

/**
 * One resolved Pass A output for the current frame.
 *
 * @property textureId GL texture of the (possibly warped) source.
 * @property isLinear True when the texture still holds scene-linear light.
 * @property aspect Width/height ratio of the frame.
 * @property texelWidth Pixel width of [textureId], for `uTexelSize`.
 * @property texelHeight Pixel height of [textureId], for `uTexelSize`.
 */
internal data class PreviewFrameInput(
    val textureId: Int,
    val isLinear: Boolean,
    val aspect: Float,
    val texelWidth: Int,
    val texelHeight: Int
)

/**
 * Pure builders of the Pass B / Pass D draw requests.
 *
 * Keeping them out of `PreviewPassPipeline` makes the uniform mapping readable in one place: every
 * number here comes from the immutable [RenderFrameState] or from the letterbox fit, never from
 * mutable pipeline state.
 */
internal object PreviewDrawRequests {

    /** Pass B request sized to the viewport with dithering. */
    fun viewportPassB(
        request: PreviewFrameRequest,
        frame: PreviewFrameInput,
        curveTextureId: Int,
        ditherAmp: Float,
        sky: SkyDrawParams? = null
    ): ExposureDrawParams {
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
        return passB(
            request = request,
            frame = frame,
            curveTextureId = curveTextureId,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = request.canvas.offsetX,
            offsetY = request.canvas.offsetY,
            ditherAmp = ditherAmp,
            flipVerticalForScreen = true,
            sky = sky
        )
    }

    /** Pass D request, including the un-letterboxed Pass B draw it fuses with. */
    fun effects(
        request: PreviewFrameRequest,
        frame: PreviewFrameInput,
        curveTextureId: Int,
        subject: SubjectDrawParams? = null,
        sky: SkyDrawParams? = null
    ): EffectsStageRequest {
        val (scaleX, scaleY) = letterboxScale(request, frame.aspect)
        val (targetWidth, targetHeight) = OffscreenColorTarget.targetSizeFor(
            request.viewportWidth, request.viewportHeight, scaleX, scaleY
        )
        return EffectsStageRequest(
            viewportWidth = request.viewportWidth,
            viewportHeight = request.viewportHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = request.canvas.offsetX,
            offsetY = request.canvas.offsetY,
            effects = request.state.effectsParams,
            passBDraw = passB(
                request = request,
                frame = frame,
                curveTextureId = curveTextureId,
                scaleX = 1.0f,
                scaleY = 1.0f,
                offsetX = 0.0f,
                offsetY = 0.0f,
                ditherAmp = 0.0f,
                flipVerticalForScreen = false,
                sky = sky
            ),
            pixelScaleX = targetWidth,
            pixelScaleY = targetHeight,
            subject = subject,
            sky = sky
        )
    }

    /** Pass B request for an offscreen target that must not be letterboxed again. */
    fun offscreenPassB(
        request: PreviewFrameRequest,
        frame: PreviewFrameInput,
        curveTextureId: Int,
        ditherAmp: Float
    ): ExposureDrawParams = passB(
        request = request,
        frame = frame,
        curveTextureId = curveTextureId,
        scaleX = 1.0f,
        scaleY = 1.0f,
        offsetX = 0.0f,
        offsetY = 0.0f,
        ditherAmp = ditherAmp,
        flipVerticalForScreen = false
    )

    /** Letterbox scale of the frame inside the viewport, including the user's canvas zoom. */
    fun letterboxScale(request: PreviewFrameRequest, imageAspect: Float): Pair<Float, Float> {
        val viewAspect = request.viewportWidth.toFloat() / request.viewportHeight.toFloat()
        return CanvasLetterbox.scale(imageAspect, viewAspect, request.canvas.scale)
    }

    private fun passB(
        request: PreviewFrameRequest,
        frame: PreviewFrameInput,
        curveTextureId: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        ditherAmp: Float,
        flipVerticalForScreen: Boolean,
        sky: SkyDrawParams? = null
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
            curveTextureId = curveTextureId,
            texelWidth = frame.texelWidth,
            texelHeight = frame.texelHeight,
            ditherAmp = ditherAmp,
            flipVerticalForScreen = flipVerticalForScreen,
            subject = state.subjectOps,
            subjectWeightTextureId = request.subjectWeightTextureId,
            subjectLocalSpace = false,
            subjectUvMatrix = state.subjectUvMatrix,
            subjectWarpTextureId = if (state.subjectWarpActive) request.subjectWarpTextureId else 0,
            subjectWarpScale = request.subjectWarpScale,
            subjectWarpOffset = request.subjectWarpOffset,
            cloudLayerTextureId = request.cloudLayerTextureId,
            cloudMaskTextureId = request.cloudMaskTextureId,
            cloudOpacity = state.cloudLayerOpacity,
            sky = sky
        )
    }
}
