package cn.devcxl.photosync.editor.domain

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Accumulates the generated tiles of one [InpaintComponentPlan] into a single feathered patch.
 *
 * Tiles overlap; each contributes its generated pixels weighted by the feathered mask alpha, so
 * the overlap fades instead of showing the hard seam a last-writer-wins paste would produce. Only
 * the component's patch rect is allocated — the rest of the 24 MP source is never copied.
 *
 * A light edge match (the mean source-minus-generated offset of an 8 px ring just outside the hole,
 * clamped to ±16 levels) removes the global "green patch" tint MI-GAN can leave on a strongly
 * coloured background. The offset is applied only to generated content, so it can never alter a
 * pixel whose alpha is zero.
 */
class InpaintComponentBlend internal constructor(
    plan: InpaintCropPlan,
    private val component: InpaintComponentPlan,
    mask: InpaintMaskBuffer,
    sourceArgb: IntArray
) {

    private val sourceWidth: Int = plan.sourceWidth
    private val sourceHeight: Int = plan.sourceHeight
    private val patch: InpaintPixelRect = component.patch
    private val width: Int = patch.width
    private val height: Int = patch.height
    private val alpha: ByteArray = InpaintMaskFeather.feather(mask, patch)
    private val nearHole: ByteArray =
        InpaintMaskFeather.dilate(alpha, width, height, InpaintMaskFeather.EDGE_MATCH_RADIUS_PX)
    private val sourceRegion: IntArray = copyRegion(sourceArgb)
    private val accumulated: FloatArray = FloatArray(width * height * CHANNELS)
    private val weights: FloatArray = FloatArray(width * height)
    private val addedTiles = mutableSetOf<Int>()

    private var finished = false

    /** Number of tiles fed into this component. */
    val tileCount: Int
        get() = addedTiles.size

    /**
     * Adds one tile's model output.
     *
     * @param tile Tile of [component], identified by [InpaintTilePlan.index].
     * @param modelOutput `[1,3,512,512]` floats in CHW order.
     * @throws IllegalArgumentException when the tile belongs to another component or the output
     *   tensor does not have the model shape.
     * @throws IllegalStateException when the tile was already added or the patch is finished.
     */
    fun addTile(tile: InpaintTilePlan, modelOutput: FloatArray) {
        check(!finished) { "Inpaint component is already finished" }
        require(tile.index in component.tiles.indices && component.tiles[tile.index] == tile) {
            "Tile does not belong to this component"
        }
        require(modelOutput.size == InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) {
            "MI-GAN output must be [1,3,512,512]"
        }
        check(addedTiles.add(tile.index)) { "Tile ${tile.index} was already added" }
        val offset = measureEdgeOffset(tile, modelOutput)
        accumulate(tile, modelOutput, offset)
    }

    /**
     * Seals the patch.
     *
     * @throws IllegalStateException when called twice or before every tile was added.
     */
    fun finish(): InpaintBlendPatch {
        check(!finished) { "Inpaint component is already finished" }
        require(addedTiles.size == component.tiles.size) {
            "Inpaint component is missing ${component.tiles.size - addedTiles.size} tile(s)"
        }
        finished = true
        val argb = sourceRegion.copyOf()
        val outputAlpha = ByteArray(width * height)
        for (index in argb.indices) {
            val weight = weights[index]
            if (weight <= 0.0f) continue
            argb[index] = packPixel(index, weight)
            outputAlpha[index] = (min(1.0f, weight) * 255.0f)
                .roundToInt()
                .coerceIn(0, 255)
                .toByte()
        }
        return InpaintBlendPatch(
            bbox = patch.toInpaintBBox(sourceWidth, sourceHeight),
            width = width,
            height = height,
            argb = argb,
            alpha = outputAlpha
        )
    }

    private fun packPixel(index: Int, weight: Float): Int {
        val red = (accumulated[index * CHANNELS] / weight).roundToInt().coerceIn(0, 255)
        val green = (accumulated[index * CHANNELS + 1] / weight).roundToInt().coerceIn(0, 255)
        val blue = (accumulated[index * CHANNELS + 2] / weight).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun accumulate(tile: InpaintTilePlan, modelOutput: FloatArray, offset: FloatArray) {
        val crop = tile.crop
        val sample = FloatArray(CHANNELS)
        for (y in crop.top until crop.bottom) {
            val modelY = (y - crop.top + 0.5f) * InpaintCropBlender.MODEL_SIDE / crop.height - 0.5f
            val patchRow = (y - patch.top) * width
            for (x in crop.left until crop.right) {
                val patchIndex = patchRow + (x - patch.left)
                val coverage = alpha[patchIndex].toInt() and 0xFF
                if (coverage == 0) continue
                val weight = coverage / 255.0f
                val modelX = (x - crop.left + 0.5f) * InpaintCropBlender.MODEL_SIDE / crop.width - 0.5f
                sampleGenerated(modelOutput, modelX, modelY, sample)
                val base = patchIndex * CHANNELS
                accumulated[base] += (sample[0] + offset[0]) * weight
                accumulated[base + 1] += (sample[1] + offset[1]) * weight
                accumulated[base + 2] += (sample[2] + offset[2]) * weight
                weights[patchIndex] += weight
            }
        }
    }

    /**
     * Mean source-minus-generated offset of the ring outside the hole, so the generated content
     * lands on the same luminance/chroma as its surroundings.
     */
    private fun measureEdgeOffset(tile: InpaintTilePlan, modelOutput: FloatArray): FloatArray {
        val crop = tile.crop
        val sample = FloatArray(CHANNELS)
        var sumRed = 0.0f
        var sumGreen = 0.0f
        var sumBlue = 0.0f
        var count = 0
        for (y in crop.top until crop.bottom) {
            val modelY = (y - crop.top + 0.5f) * InpaintCropBlender.MODEL_SIDE / crop.height - 0.5f
            val patchRow = (y - patch.top) * width
            for (x in crop.left until crop.right) {
                val patchIndex = patchRow + (x - patch.left)
                if ((alpha[patchIndex].toInt() and 0xFF) != 0) continue
                if ((nearHole[patchIndex].toInt() and 0xFF) == 0) continue
                val modelX = (x - crop.left + 0.5f) * InpaintCropBlender.MODEL_SIDE / crop.width - 0.5f
                sampleGenerated(modelOutput, modelX, modelY, sample)
                val source = sourceRegion[patchIndex]
                sumRed += (source shr 16 and 0xFF) - sample[0]
                sumGreen += (source shr 8 and 0xFF) - sample[1]
                sumBlue += (source and 0xFF) - sample[2]
                count++
            }
        }
        if (count < MIN_EDGE_SAMPLES) return FloatArray(CHANNELS)
        return floatArrayOf(
            (sumRed / count).coerceIn(-MAX_EDGE_OFFSET, MAX_EDGE_OFFSET),
            (sumGreen / count).coerceIn(-MAX_EDGE_OFFSET, MAX_EDGE_OFFSET),
            (sumBlue / count).coerceIn(-MAX_EDGE_OFFSET, MAX_EDGE_OFFSET)
        )
    }

    /** Bilinear sample of the CHW output, decoded to the 0..255 display scale into [destination]. */
    private fun sampleGenerated(
        modelOutput: FloatArray,
        sampleX: Float,
        sampleY: Float,
        destination: FloatArray
    ) {
        val plane = InpaintCropBlender.MODEL_SIDE * InpaintCropBlender.MODEL_SIDE
        val side = InpaintCropBlender.MODEL_SIDE
        val x0 = floor(sampleX).toInt().coerceIn(0, side - 1)
        val y0 = floor(sampleY).toInt().coerceIn(0, side - 1)
        val x1 = (x0 + 1).coerceAtMost(side - 1)
        val y1 = (y0 + 1).coerceAtMost(side - 1)
        val fx = (sampleX - x0).coerceIn(0.0f, 1.0f)
        val fy = (sampleY - y0).coerceIn(0.0f, 1.0f)
        for (channel in 0 until CHANNELS) {
            val planeOffset = channel * plane
            val topLeft = decoded(modelOutput[planeOffset + y0 * side + x0])
            val topRight = decoded(modelOutput[planeOffset + y0 * side + x1])
            val bottomLeft = decoded(modelOutput[planeOffset + y1 * side + x0])
            val bottomRight = decoded(modelOutput[planeOffset + y1 * side + x1])
            val top = topLeft + (topRight - topLeft) * fx
            val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
            destination[channel] = top + (bottom - top) * fy
        }
    }

    /**
     * Decodes one output sample with the reference contract, then keeps it as a float so
     * interpolating between neighbouring model pixels stays smooth. The integer conversion uses
     * the same truncation as `astype(uint8)` in the official pipeline.
     */
    private fun decoded(value: Float): Float =
        InpaintCropBlender.encodeOutputChannel(value).toFloat()

    private fun copyRegion(sourceArgb: IntArray): IntArray {
        val copy = IntArray(width * height)
        for (y in 0 until height) {
            val sourceRow = (patch.top + y) * sourceWidth + patch.left
            System.arraycopy(sourceArgb, sourceRow, copy, y * width, width)
        }
        return copy
    }

    private companion object {
        const val CHANNELS = 3

        /** Below this many ring pixels the offset is statistically meaningless. */
        const val MIN_EDGE_SAMPLES = 16

        /** "Light" colour match: at most 16 of 255 levels per channel. */
        const val MAX_EDGE_OFFSET = 16.0f
    }
}
