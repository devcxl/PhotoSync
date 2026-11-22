package cn.devcxl.photosync.editor.domain

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Normalized pre-crop source rectangle that a Tier 2 band uploads to the GPU.
 *
 * Pass A converts its pre-crop lookup into slice UV with `(preCropUv - xy) / zw`, so a full-frame
 * residency simply uses [FULL].
 */
data class SourceSliceRect(
    val x0: Float,
    val y0: Float,
    val width: Float,
    val height: Float
) {

    /** Packs the rect into the `vec4(x0, y0, width, height)` uniform layout. */
    fun toVec4(): FloatArray = floatArrayOf(x0, y0, width, height)

    companion object {

        /** Whole-source rectangle, the identity of the slice mapping. */
        val FULL = SourceSliceRect(0.0f, 0.0f, 1.0f, 1.0f)
    }
}

/**
 * Pixel bounds of a Tier 2 slice, with an exclusive right/bottom edge.
 */
data class SourceSliceBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    /** Width of the slice in source pixels. */
    val width: Int get() = right - left

    /** Height of the slice in source pixels. */
    val height: Int get() = bottom - top
}

/**
 * Resolved Tier 2 slice: normalized rect for `uSrcSliceRect` plus the pixel box to extract.
 *
 * @property exceedsUploadLimit True when the inverse-mapped box is larger than the GPU upload
 *   budget; the caller must clamp and accept edge smearing rather than fail the export.
 */
data class SourceSliceRequest(
    val rect: SourceSliceRect,
    val bounds: SourceSliceBounds,
    val exceedsUploadLimit: Boolean
)

/**
 * Computes the smallest pre-crop source box a column band needs.
 *
 * A homography maps straight lines onto straight lines, so sampling the four edges of the band's
 * halo rectangle is enough to bound the source region exactly. The box is expanded by a small
 * bilinear margin so texel-centre sampling at the outermost band pixels never clamps.
 */
object SourceSlicePlanner {

    /** Extra source pixels around the inverse-mapped border for bilinear texel centres. */
    const val MARGIN_PX = 4

    /** Samples per band edge when bounding the inverse image. */
    const val SAMPLES_PER_EDGE = 4

    /**
     * Inverse-maps [band] through [postCropToPreCrop] and returns the source slice to upload.
     *
     * @param postCropToPreCrop Pass A's `H^-1` (post-crop global UV to pre-crop source UV).
     * @param band Band to render, halo included.
     * @param cropWidth Final cropped frame width.
     * @param cropHeight Final cropped frame height.
     * @param sourceWidth Upright source width in pixels.
     * @param sourceHeight Upright source height in pixels.
     * @param marginPx Bilinear safety margin in source pixels.
     * @param maxEdgePx Longest slice edge the uploader accepts.
     * @param extraMarginUv Extra normalized UV margin added when Pass A carries a warp field.
     */
    fun plan(
        postCropToPreCrop: Mat3,
        band: ColumnBand,
        cropWidth: Int,
        cropHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        marginPx: Int = MARGIN_PX,
        maxEdgePx: Int = SourceResidencyPlanner.MAX_SLICE_EDGE_PX,
        extraMarginUv: Float = 0.0f
    ): SourceSliceRequest {
        require(cropWidth > 0 && cropHeight > 0) { "Crop frame must be positive" }
        require(sourceWidth > 0 && sourceHeight > 0) { "Source frame must be positive" }
        val left = band.x0 - band.halo
        val top = band.y0 - band.halo
        val right = left + band.fboWidth
        val bottom = top + band.fboHeight
        var minU = Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for ((px, py) in boundarySamples(left, top, right, bottom)) {
            val mapped = postCropToPreCrop.map(px / cropWidth, py / cropHeight)
            if (!mapped.u.isFinite() || !mapped.v.isFinite()) continue
            minU = minOf(minU, mapped.u)
            minV = minOf(minV, mapped.v)
            maxU = maxOf(maxU, mapped.u)
            maxV = maxOf(maxV, mapped.v)
        }
        if (minU > maxU || minV > maxV) {
            return SourceSliceRequest(SourceSliceRect.FULL, fullBounds(sourceWidth, sourceHeight), false)
        }
        val warpMargin = extraMarginUv.takeIf { it.isFinite() && it > 0.0f } ?: 0.0f
        val bounds = clampBounds(
            left = floor((minU - warpMargin) * sourceWidth).toInt() - marginPx,
            top = floor((minV - warpMargin) * sourceHeight).toInt() - marginPx,
            right = ceil((maxU + warpMargin) * sourceWidth).toInt() + marginPx,
            bottom = ceil((maxV + warpMargin) * sourceHeight).toInt() + marginPx,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
        val exceeds = bounds.width > maxEdgePx || bounds.height > maxEdgePx
        val rect = SourceSliceRect(
            x0 = bounds.left.toFloat() / sourceWidth,
            y0 = bounds.top.toFloat() / sourceHeight,
            width = bounds.width.toFloat() / sourceWidth,
            height = bounds.height.toFloat() / sourceHeight
        )
        return SourceSliceRequest(rect = rect, bounds = bounds, exceedsUploadLimit = exceeds)
    }

    private fun boundarySamples(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): List<Pair<Float, Float>> {
        val samples = ArrayList<Pair<Float, Float>>((SAMPLES_PER_EDGE + 1) * 4)
        for (step in 0..SAMPLES_PER_EDGE) {
            val fraction = step.toFloat() / SAMPLES_PER_EDGE
            val x = left + (right - left) * fraction
            val y = top + (bottom - top) * fraction
            samples += x to top.toFloat()
            samples += x to bottom.toFloat()
            samples += left.toFloat() to y
            samples += right.toFloat() to y
        }
        return samples
    }

    private fun clampBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): SourceSliceBounds {
        val clampedLeft = left.coerceIn(0, sourceWidth - 1)
        val clampedTop = top.coerceIn(0, sourceHeight - 1)
        val clampedRight = right.coerceIn(clampedLeft + 1, sourceWidth)
        val clampedBottom = bottom.coerceIn(clampedTop + 1, sourceHeight)
        return SourceSliceBounds(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }

    private fun fullBounds(sourceWidth: Int, sourceHeight: Int): SourceSliceBounds =
        SourceSliceBounds(0, 0, sourceWidth, sourceHeight)
}
