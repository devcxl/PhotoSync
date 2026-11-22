package cn.devcxl.photosync.editor.domain

/**
 * A `N x N` interleaved `(dx, dy)` inverse-mapping displacement field, in normalized frame units.
 *
 * The Pass A geometry program samples exactly one such field through `uWarpTex`, and the CPU
 * preview-cache resampler reads the same values, so every producer (liquify brush, face MLS, body
 * warp and their composition) has to expose the identical layout: row-major cells, `dx` before
 * `dy`, texel centres at `(i + 0.5) / N`.
 */
interface WarpDisplacementField {

    /** Edge length of the square grid. */
    val gridSize: Int

    /** True when the field is visually identical to no warp at all. */
    fun isZero(): Boolean

    /** Writes every cell as interleaved half-float bits, the upload and persistence form. */
    fun readHalfFloatBitsInto(destination: ShortArray)

    /** Raw component at [index] of the interleaved field, for the quantized upload fallback. */
    fun displacementRaw(index: Int): Float

    /** Samples the field at normalized [u], [v] with bilinear interpolation. */
    fun displacementAt(u: Float, v: Float): Uv
}
