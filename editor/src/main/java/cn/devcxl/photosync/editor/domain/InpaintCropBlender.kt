package cn.devcxl.photosync.editor.domain

import kotlin.math.floor

/**
 * Pure-JVM geometry and tensor math of the P2 crop-inpaint-blend pipeline.
 *
 * ## Model contract (verified bit-exactly in the P0 gate record, MAE = 0)
 * For every model pixel the input tensor is
 * ```
 * m = known / 255f                       // 1 = keep, 0 = hole
 * x = (rgb / 255f * 2f - 1f) * m         // hole pixels are zeroed
 * input[0]   = m - 0.5f
 * input[1..3]= x.r / x.g / x.b           // NCHW, float32 [1,4,512,512]
 * ```
 * and the output channel is decoded with
 * ```
 * o = clamp(out * 0.5f + 0.5f, 0f, 1f) * 255f
 * ```
 * which is the exact arithmetic of the official MI-GAN pipeline, so a Kotlin-side run matches the
 * Python reference byte for byte.
 *
 * The class itself owns no Android types: sampling, planning and blending run in JVM unit tests and
 * on `Dispatchers.Default` in production. PNG codecs and ORT live one layer above.
 */
class InpaintCropBlender {

    private val planner = InpaintCropPlanner()

    /** Builds the crop/tile plan of [mask]; an empty mask yields an empty plan. */
    fun plan(mask: InpaintMaskBuffer): InpaintCropPlan = planner.plan(mask)

    /**
     * Builds the `[1,4,512,512]` float input of [tile].
     *
     * @param sourceArgb Opaque `ARGB_8888` pixels of the whole source image, row-major.
     * @throws IllegalArgumentException when the buffers do not match [plan]'s source size.
     */
    fun preprocessTile(
        sourceArgb: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        mask: InpaintMaskBuffer,
        tile: InpaintTilePlan
    ): FloatArray {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(sourceArgb.size == sourceWidth * sourceHeight) { "Source buffer does not match its size" }
        require(mask.width == sourceWidth && mask.height == sourceHeight) {
            "Mask and source dimensions differ"
        }
        require(tile.crop.width > 0 && tile.crop.height > 0) { "Tile crop must not be empty" }
        val modelPixels = MODEL_SIDE * MODEL_SIDE
        val input = FloatArray(MODEL_INPUT_CHANNELS * modelPixels)
        val crop = tile.crop
        for (modelY in 0 until MODEL_SIDE) {
            val sourceY = crop.top + ((modelY + 0.5f) * crop.height / MODEL_SIDE) - 0.5f
            val maskY = (crop.top + (modelY * crop.height) / MODEL_SIDE)
                .coerceIn(crop.top, crop.bottom - 1)
            for (modelX in 0 until MODEL_SIDE) {
                val sourceX = crop.left + ((modelX + 0.5f) * crop.width / MODEL_SIDE) - 0.5f
                val maskX = (crop.left + (modelX * crop.width) / MODEL_SIDE)
                    .coerceIn(crop.left, crop.right - 1)
                val known = (255 - mask.coverageAt(maskX, maskY)) / 255.0f
                val red = sampleChannel(sourceArgb, sourceWidth, sourceHeight, sourceX, sourceY, 16)
                val green = sampleChannel(sourceArgb, sourceWidth, sourceHeight, sourceX, sourceY, 8)
                val blue = sampleChannel(sourceArgb, sourceWidth, sourceHeight, sourceX, sourceY, 0)
                val index = modelY * MODEL_SIDE + modelX
                input[index] = known - 0.5f
                input[modelPixels + index] = (red / 255.0f * 2.0f - 1.0f) * known
                input[2 * modelPixels + index] = (green / 255.0f * 2.0f - 1.0f) * known
                input[3 * modelPixels + index] = (blue / 255.0f * 2.0f - 1.0f) * known
            }
        }
        return input
    }

    /**
     * Starts accumulating one component's generated tiles.
     *
     * @param plan Plan the component belongs to.
     * @param mask Source-resolution mask used by the plan.
     * @param componentIndex Index into [InpaintCropPlan.components].
     * @param sourceArgb Original pixels; the builder copies only the component patch region.
     * @throws IllegalArgumentException when the index or the buffers do not match [plan].
     */
    fun beginComponent(
        plan: InpaintCropPlan,
        mask: InpaintMaskBuffer,
        componentIndex: Int,
        sourceArgb: IntArray
    ): InpaintComponentBlend {
        require(componentIndex in plan.components.indices) { "Unknown inpaint component index" }
        require(sourceArgb.size == plan.sourceWidth * plan.sourceHeight) {
            "Source buffer does not match the plan"
        }
        require(mask.width == plan.sourceWidth && mask.height == plan.sourceHeight) {
            "Mask and plan dimensions differ"
        }
        return InpaintComponentBlend(
            plan = plan,
            component = plan.components[componentIndex],
            mask = mask,
            sourceArgb = sourceArgb
        )
    }

    companion object {

        /** Spatial size of the MI-GAN generator input and output. */
        const val MODEL_SIDE: Int = 512

        /** `[1,4,512,512]`: mask plus RGB. */
        const val MODEL_INPUT_CHANNELS: Int = 4

        /** `[1,3,512,512]`: RGB only. */
        const val MODEL_OUTPUT_CHANNELS: Int = 3

        /** Number of floats one model request must contain. */
        const val MODEL_INPUT_ELEMENTS: Int = MODEL_INPUT_CHANNELS * MODEL_SIDE * MODEL_SIDE

        /** Number of floats one model response must contain. */
        const val MODEL_OUTPUT_ELEMENTS: Int = MODEL_OUTPUT_CHANNELS * MODEL_SIDE * MODEL_SIDE

        /**
         * Decodes one MI-GAN output channel into an 8-bit sample.
         *
         * The official pipeline truncates (`astype(uint8)`) after scaling, so this uses `toInt()`
         * rather than rounding to stay byte-identical.
         */
        fun encodeOutputChannel(value: Float): Int {
            val normalized = (value * 0.5f + 0.5f).coerceIn(0.0f, 1.0f)
            return (normalized * 255.0f).toInt().coerceIn(0, 255)
        }

        /** Bilinear sample of one channel from a packed ARGB buffer. */
        internal fun sampleChannel(
            argb: IntArray,
            width: Int,
            height: Int,
            sampleX: Float,
            sampleY: Float,
            shift: Int
        ): Float {
            val x0 = floor(sampleX).toInt().coerceIn(0, width - 1)
            val y0 = floor(sampleY).toInt().coerceIn(0, height - 1)
            val x1 = (x0 + 1).coerceAtMost(width - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fx = (sampleX - x0).coerceIn(0.0f, 1.0f)
            val fy = (sampleY - y0).coerceIn(0.0f, 1.0f)
            val topLeft = (argb[y0 * width + x0] shr shift) and 0xFF
            val topRight = (argb[y0 * width + x1] shr shift) and 0xFF
            val bottomLeft = (argb[y1 * width + x0] shr shift) and 0xFF
            val bottomRight = (argb[y1 * width + x1] shr shift) and 0xFF
            val top = topLeft + (topRight - topLeft) * fx
            val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
            return top + (bottom - top) * fy
        }
    }
}
