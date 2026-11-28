package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.TileUvTransform
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * Uniform bundle for a single Pass B preview draw.
 */
internal data class ExposureDrawParams(
    val textureId: Int,
    val compareOriginal: Boolean,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f,
    val isLinearInput: Boolean,
    val baseLook: BaseLook,
    val autoBrightGain: Float,
    val toneParams: ToneShaderParams = ToneShaderParams(),
    val colorParams: ColorGradingParams = ColorGradingParams(),
    val curveTextureId: Int = 0,
    val texelWidth: Int = 1,
    val texelHeight: Int = 1,
    /** Triangular dither amplitude in display units; `1/255` for 8-bit targets. */
    val ditherAmp: Float = 0.0f,
    /** True only when presenting directly to the default screen framebuffer (`FBO 0`). */
    val flipVerticalForScreen: Boolean = false,
    /** Foreground/background relative grade; `null` or neutral keeps the shader branch closed. */
    val subject: SubjectOps? = null,
    /** Refined subject alpha texture; `0` disables the relight even with a non-neutral group. */
    val subjectWeightTextureId: Int = 0,
    /** True when [subjectWeightTextureId] is already in this draw's coordinate grid (bake band). */
    val subjectLocalSpace: Boolean = false,
    /** Output-UV to pre-crop-UV matrix for a source-space mask. */
    val subjectUvMatrix: FloatArray = IDENTITY_MATRIX,
    /** `uWarpTex` to mirror the Pass A liquify/portrait displacement, or `0`. */
    val subjectWarpTextureId: Int = 0,
    val subjectWarpScale: Float = 1.0f,
    val subjectWarpOffset: Float = 0.0f,
    /** Post-Pass A cloud layer texture, or `0` when no layer may be sampled. */
    val cloudLayerTextureId: Int = 0,
    /** Optional cloud coverage texture; `0` means the layer covers the whole frame. */
    val cloudMaskTextureId: Int = 0,
    /** Effective cloud opacity in `0..1`; `0` keeps the shader branch closed. */
    val cloudOpacity: Float = 0.0f,
    /** Full-frame UV transform for [cloudLayerTextureId]; identity outside a bake band. */
    val cloudUvTransform: TileUvTransform = TileUvTransform.IDENTITY,
    /** Optional P3 sky relight; `null` or a neutral delta closes the shader branch. */
    val sky: SkyDrawParams? = null
) {

    companion object {
        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    }
}
