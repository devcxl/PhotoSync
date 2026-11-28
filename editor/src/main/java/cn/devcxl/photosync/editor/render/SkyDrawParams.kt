package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyBlendParams
import cn.devcxl.photosync.editor.domain.SkyRelightStats
import cn.devcxl.photosync.editor.domain.TileUvTransform

/**
 * Uniform bundle of the sky stage for one Pass B and Pass D draw.
 *
 * The same instance is consumed twice: Pass B reads [relight]/[stats] for the foreground relight and
 * Pass D reads [blend] plus the procedural palette for the composite. Every coordinate is mapped
 * through [uvMatrix] (Pass A inverse) and, inside a bake band, through [tileUv] first, so a column
 * band samples exactly the same source-space mask pixel as the preview.
 */
internal data class SkyDrawParams(
    val maskTextureId: Int,
    val assetTextureId: Int,
    val procedural: ProceduralSkyPreset?,
    val blend: SkyBlendParams,
    val stats: SkyRelightStats,
    val relight: Int,
    val uvMatrix: FloatArray,
    val warpTextureId: Int = 0,
    val warpScale: Float = 1.0f,
    val warpOffset: Float = 0.0f,
    val tileUv: TileUvTransform = TileUvTransform.IDENTITY,
    /** Full-resolution source short edge that defines the `edgeShift` unit. */
    val pixelScaleX: Float = 1.0f,
    val pixelScaleY: Float = 1.0f,
    /** True when the mask is already band-aligned and must be sampled with the local UV. */
    val maskLocalSpace: Boolean = false
) {

    /** True when Pass D has a replacement to composite. */
    val isCompositeActive: Boolean
        get() = maskTextureId != 0 && (procedural != null || assetTextureId != 0)

    /** True when Pass B may run the foreground relight branch. */
    val isRelightActive: Boolean
        get() = maskTextureId != 0 && relight > 0 && !stats.isIdentity()

    companion object {

        /** Identity matrix, used when Pass A is neutral. */
        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    }
}
