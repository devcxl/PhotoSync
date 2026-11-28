package cn.devcxl.photosync.editor.domain

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The generated pixels of one tile, already decoded to display-domain RGB.
 *
 * Two shapes reach this class: the legacy generator returns a `[1,3,512,512]` float tensor that the
 * patch builder resamples back onto the crop, while the uint8 pipeline returns RGB bytes at crop
 * resolution. Both become one opaque ARGB raster so the patch arithmetic has a single path.
 */
class GeneratedTilePixels private constructor(
    val width: Int,
    val height: Int,
    val argb: IntArray
) {

    init {
        require(width > 0 && height > 0) { "Generated tile dimensions must be positive" }
        require(argb.size == width * height) { "Generated tile payload does not match its size" }
    }

    companion object {

        /** Decodes one `[1,3,512,512]` CHW float output with the reference post-process. */
        fun fromModelFloats(modelOutput: FloatArray): GeneratedTilePixels {
            require(modelOutput.size == InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) {
                "MI-GAN output must be [1,3,512,512]"
            }
            val side = InpaintCropBlender.MODEL_SIDE
            val plane = side * side
            val argb = IntArray(plane) { index ->
                val red = InpaintCropBlender.encodeOutputChannel(modelOutput[index])
                val green = InpaintCropBlender.encodeOutputChannel(modelOutput[plane + index])
                val blue = InpaintCropBlender.encodeOutputChannel(modelOutput[2 * plane + index])
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            return GeneratedTilePixels(side, side, argb)
        }

        /** Wraps a pipeline RGB result that already has crop resolution. */
        fun fromRgb(argb: IntArray, width: Int, height: Int): GeneratedTilePixels {
            require(argb.size == width * height) { "Generated tile payload does not match its size" }
            val opaque = IntArray(argb.size) { index ->
                val pixel = argb[index]
                (0xFF shl 24) or (pixel and 0x00FFFFFF)
            }
            return GeneratedTilePixels(width, height, opaque)
        }
    }
}

/**
 * Builds the single [InpaintBlendPatch] a tile emits (spec §6.3 / §12 #6).
 *
 * ## Contract
 * - The patch covers exactly [InpaintTilePlan.cropRect]; its alpha comes only from
 *   [InpaintTileWeight], so the two invariants (unpainted and outside-core pixels stay `0`) are not
 *   re-derived in a second implementation.
 * - Pixels with `alpha == 0` keep the exact source bytes, including the whole 128 px context margin.
 * - The light edge match (mean source-minus-generated offset of the 8 px ring around the hole,
 *   clamped to ±16 levels) shifts generated content only where `alpha > 0`, so it can never alter a
 *   pixel the mask did not paint.
 * - Allocation is bounded by the tile crop (≤ 1024²), never by the painted area.
 */
object InpaintTilePatch {

    /**
     * Builds one tile patch.
     *
     * @param tile Tile to materialise.
     * @param mask Source-resolution mask the tile was planned from.
     * @param sourceArgb Original opaque source pixels of the whole image, row-major.
     * @param sourceWidth Source width.
     * @param sourceHeight Source height.
     * @param generated Decoded model output of this tile.
     * @throws IllegalArgumentException when the buffers do not match the tile or the source.
     */
    fun build(
        tile: InpaintTilePlan,
        mask: InpaintMaskBuffer,
        sourceArgb: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        generated: GeneratedTilePixels
    ): InpaintBlendPatch {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(sourceArgb.size == sourceWidth * sourceHeight) { "Source buffer does not match its size" }
        require(mask.width == sourceWidth && mask.height == sourceHeight) {
            "Mask and source dimensions differ"
        }
        val crop = tile.cropRect
        require(crop.right <= sourceWidth && crop.bottom <= sourceHeight) {
            "Inpaint tile crop must stay inside the source"
        }
        val width = crop.width
        val height = crop.height
        val alpha = InpaintTileWeight.alpha(mask, tile)
        val argb = copyRegion(sourceArgb, crop, sourceWidth)
        val offset = measureEdgeOffset(tile, argb, generated, alpha)
        val sample = FloatArray(PIXEL_CHANNELS)
        for (y in 0 until height) {
            val sourceY = crop.top + y
            for (x in 0 until width) {
                val index = y * width + x
                if ((alpha[index].toInt() and 0xFF) == 0) continue
                sampleGenerated(generated, crop, x, y, sample)
                val red = (sample[0] + offset[0]).roundToInt().coerceIn(0, 255)
                val green = (sample[1] + offset[1]).roundToInt().coerceIn(0, 255)
                val blue = (sample[2] + offset[2]).roundToInt().coerceIn(0, 255)
                argb[index] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return InpaintBlendPatch(
            bbox = crop.toInpaintBBox(sourceWidth, sourceHeight),
            width = width,
            height = height,
            argb = argb,
            alpha = alpha
        )
    }

    private fun copyRegion(sourceArgb: IntArray, crop: InpaintPixelRect, sourceWidth: Int): IntArray {
        val copy = IntArray(crop.width * crop.height)
        for (y in 0 until crop.height) {
            val sourceRow = (crop.top + y) * sourceWidth + crop.left
            System.arraycopy(sourceArgb, sourceRow, copy, y * crop.width, crop.width)
        }
        return copy
    }

    /**
     * Mean source-minus-generated offset of the ring just outside the hole, so generated content
     * lands on the same luminance/chroma as its surroundings.
     *
     * The ring is `dilated(alpha) && alpha == 0`, i.e. within 8 px of generated content but never a
     * pixel the patch is allowed to change. Below [MIN_EDGE_SAMPLES] ring pixels the offset is
     * statistically meaningless and stays zero.
     */
    private fun measureEdgeOffset(
        tile: InpaintTilePlan,
        sourceRegion: IntArray,
        generated: GeneratedTilePixels,
        alpha: ByteArray
    ): FloatArray {
        val crop = tile.cropRect
        val ring = InpaintMaskFeather.dilate(alpha, crop.width, crop.height, InpaintMaskFeather.EDGE_MATCH_RADIUS_PX)
        val sample = FloatArray(PIXEL_CHANNELS)
        var sumRed = 0.0f
        var sumGreen = 0.0f
        var sumBlue = 0.0f
        var count = 0
        for (y in 0 until crop.height) {
            for (x in 0 until crop.width) {
                val index = y * crop.width + x
                if ((alpha[index].toInt() and 0xFF) != 0) continue
                if ((ring[index].toInt() and 0xFF) == 0) continue
                sampleGenerated(generated, crop, x, y, sample)
                val source = sourceRegion[index]
                sumRed += (source shr 16 and 0xFF) - sample[0]
                sumGreen += (source shr 8 and 0xFF) - sample[1]
                sumBlue += (source and 0xFF) - sample[2]
                count++
            }
        }
        if (count < MIN_EDGE_SAMPLES) return FloatArray(PIXEL_CHANNELS)
        return floatArrayOf(
            (sumRed / count).coerceIn(-MAX_EDGE_OFFSET, MAX_EDGE_OFFSET),
            (sumGreen / count).coerceIn(-MAX_EDGE_OFFSET, MAX_EDGE_OFFSET),
            (sumBlue / count).coerceIn(-MAX_EDGE_OFFSET, MAX_EDGE_OFFSET)
        )
    }

    /** Bilinear sample of [generated] for crop-relative pixel ([x], [y]) into [destination]. */
    private fun sampleGenerated(
        generated: GeneratedTilePixels,
        crop: InpaintPixelRect,
        x: Int,
        y: Int,
        destination: FloatArray
    ) {
        val sourceX = (x + 0.5f) * generated.width / crop.width - 0.5f
        val sourceY = (y + 0.5f) * generated.height / crop.height - 0.5f
        val x0 = floor(sourceX).toInt().coerceIn(0, generated.width - 1)
        val y0 = floor(sourceY).toInt().coerceIn(0, generated.height - 1)
        val x1 = (x0 + 1).coerceAtMost(generated.width - 1)
        val y1 = (y0 + 1).coerceAtMost(generated.height - 1)
        val fx = (sourceX - x0).coerceIn(0.0f, 1.0f)
        val fy = (sourceY - y0).coerceIn(0.0f, 1.0f)
        for (channel in 0 until PIXEL_CHANNELS) {
            val shift = 16 - channel * 8
            val topLeft = (generated.argb[y0 * generated.width + x0] shr shift) and 0xFF
            val topRight = (generated.argb[y0 * generated.width + x1] shr shift) and 0xFF
            val bottomLeft = (generated.argb[y1 * generated.width + x0] shr shift) and 0xFF
            val bottomRight = (generated.argb[y1 * generated.width + x1] shr shift) and 0xFF
            val top = topLeft + (topRight - topLeft) * fx
            val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
            destination[channel] = top + (bottom - top) * fy
        }
    }

    private const val PIXEL_CHANNELS = 3

    /** Below this many ring pixels the offset is statistically meaningless. */
    private const val MIN_EDGE_SAMPLES = 16

    /** "Light" colour match: at most 16 of 255 levels per channel. */
    private const val MAX_EDGE_OFFSET = 16.0f
}
