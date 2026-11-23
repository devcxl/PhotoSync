package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.lut.Lut3dTexture

/**
 * Immutable per-draw request for [EffectsOutputStage].
 */
internal data class EffectsStageRequest(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
    val effects: PassDEffectsParams,
    val passBDraw: ExposureDrawParams,
    /** Pixel size of the rendered frame, used as the global dither coordinate scale. */
    val pixelScaleX: Int = 0,
    val pixelScaleY: Int = 0
)

/**
 * Executes Pass D after Pass B: renders Pass B into an offscreen target of the cropped frame, then
 * applies the 3D LUT, film grain and post-crop vignette on `[0, 1]^2` global UVs before writing the
 * dithered result to the screen.
 *
 * Staging Pass B offscreen is what makes the grain and vignette seams disappear: their coordinates
 * are the cropped frame's own UVs, never the letterboxed canvas.
 */
internal class EffectsOutputStage(
    private val exposureProgram: ExposureGlProgram,
    private val capabilities: GlCapabilities
) {

    private val program = EffectsOutputGlProgram()
    private val lutTexture = Lut3dTexture()
    private val stagingTarget = OffscreenColorTarget(capabilities)
    private var fallbackLogged = false

    /** True when Pass D can run on this context. */
    val isReady: Boolean
        get() = program.isReady

    /** Compiles Pass D; skipped on ES 2.0 contexts, which never expose `sampler3D`. */
    fun init(preferEs3: Boolean) {
        program.release()
        stagingTarget.release()
        stagingTarget.resetDisableFlag()
        lutTexture.invalidate()
        fallbackLogged = false
        if (!preferEs3) return
        if (!program.init()) capabilities.markMinimalFallback("Pass D effects shader")
    }

    /** Stages [samples] for the GL thread. */
    fun stageLut(samples: Lut3dSamples?) {
        lutTexture.stage(samples)
    }

    /** Uploads the staged LUT if it changed. Must run on the GL thread. */
    fun uploadPending(preferEs3: Boolean) {
        lutTexture.uploadIfNeeded(preferEs3)
    }

    /** Releases every GL object owned by this stage. */
    fun release() {
        program.release()
        lutTexture.release()
        stagingTarget.release()
    }

    /**
     * Renders Pass B offscreen and applies Pass D to the screen.
     *
     * @return True when Pass D rendered; false when the staging target is unavailable, so the caller
     *   can draw Pass B straight to the screen instead.
     */
    fun draw(request: EffectsStageRequest): Boolean {
        if (!program.isReady) return false
        val (targetWidth, targetHeight) = OffscreenColorTarget.targetSizeFor(
            viewportWidth = request.viewportWidth,
            viewportHeight = request.viewportHeight,
            scaleX = request.scaleX,
            scaleY = request.scaleY
        )
        stagingTarget.ensureSize(targetWidth, targetHeight, allowHalfFloat = true)
        if (!stagingTarget.isReady) {
            logFallbackOnce()
            return false
        }
        stagingTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        exposureProgram.draw(
            request.passBDraw.copy(
                scaleX = 1.0f,
                scaleY = 1.0f,
                offsetX = 0.0f,
                offsetY = 0.0f,
                ditherAmp = if (stagingTarget.isHalfFloatTarget) {
                    0.0f
                } else {
                    PassDEffectsMath.DITHER_AMPLITUDE
                },
                flipVerticalForScreen = false
            )
        )
        stagingTarget.unbind()
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        drawFromTextureInternal(
            stagingTarget.textureId,
            request,
            pixelScaleX = request.pixelScaleX.takeIf { it > 0 } ?: stagingTarget.width,
            pixelScaleY = request.pixelScaleY.takeIf { it > 0 } ?: stagingTarget.height
        )
        return true
    }

    /**
     * Applies Pass D directly to [inputTextureId] (e.g. Pass C output) and presents to the screen.
     */
    fun drawFromTexture(inputTextureId: Int, request: EffectsStageRequest) {
        GLES20.glViewport(0, 0, request.viewportWidth, request.viewportHeight)
        drawFromTextureInternal(
            inputTextureId,
            request,
            pixelScaleX = request.pixelScaleX.takeIf { it > 0 } ?: request.viewportWidth,
            pixelScaleY = request.pixelScaleY.takeIf { it > 0 } ?: request.viewportHeight
        )
    }

    private fun drawFromTextureInternal(
        inputTextureId: Int,
        request: EffectsStageRequest,
        pixelScaleX: Int,
        pixelScaleY: Int
    ) {
        program.draw(
            EffectsDrawParams(
                textureId = inputTextureId,
                lutTextureId = if (lutTexture.isReady) lutTexture.textureId else 0,
                compareOriginal = request.passBDraw.compareOriginal,
                scaleX = request.scaleX,
                scaleY = request.scaleY,
                offsetX = request.offsetX,
                offsetY = request.offsetY,
                effects = request.effects.withLutSize(lutTexture.sampleSize),
                ditherAmp = PassDEffectsMath.DITHER_AMPLITUDE,
                pixelScaleX = pixelScaleX.toFloat(),
                pixelScaleY = pixelScaleY.toFloat(),
                flipVerticalForScreen = true
            )
        )
    }

    private fun logFallbackOnce() {
        if (fallbackLogged) return
        fallbackLogged = true
        Timber.w("Pass D staging target unavailable; effects render directly from Pass B")
    }
}
