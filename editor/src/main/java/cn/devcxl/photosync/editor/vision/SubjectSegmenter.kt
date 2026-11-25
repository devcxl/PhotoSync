package cn.devcxl.photosync.editor.vision

import java.io.File

import kotlin.math.floor
import kotlin.math.min

/**
 * One upright source image handed to a [SubjectSegmenter].
 *
 * Pixels are packed ARGB (`0xAARRGGBB`), top row first, exactly as `Bitmap.getPixels` returns them.
 * The segmenter never owns or recycles [argb], so callers may reuse their bitmap storage.
 *
 * @property width Image width in pixels.
 * @property height Image height in pixels.
 * @property argb Packed ARGB pixels in row-major order.
 */
data class SubjectImage(
    val width: Int,
    val height: Int,
    val argb: IntArray
) {

    init {
        require(width > 0 && height > 0) { "Subject image dimensions must be positive" }
        require(argb.size >= width * height) { "Subject image buffer is smaller than its dimensions" }
    }

    /** Bilinear sample of the red channel at normalized [u], [v] in `[0, 1]`. */
    fun redAt(u: Float, v: Float): Float = channelAt(u, v, RED_SHIFT)

    /** Bilinear sample of the green channel at normalized [u], [v] in `[0, 1]`. */
    fun greenAt(u: Float, v: Float): Float = channelAt(u, v, GREEN_SHIFT)

    /** Bilinear sample of the blue channel at normalized [u], [v] in `[0, 1]`. */
    fun blueAt(u: Float, v: Float): Float = channelAt(u, v, BLUE_SHIFT)

    private fun channelAt(u: Float, v: Float, shift: Int): Float {
        val x = (u.coerceIn(0.0f, 1.0f) * (width - 1)).coerceIn(0.0f, (width - 1).toFloat())
        val y = (v.coerceIn(0.0f, 1.0f) * (height - 1)).coerceIn(0.0f, (height - 1).toFloat())
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = mix(pixelChannel(y0 * width + x0, shift), pixelChannel(y0 * width + x1, shift), fx)
        val bottom = mix(pixelChannel(y1 * width + x0, shift), pixelChannel(y1 * width + x1, shift), fx)
        return mix(top, bottom, fy)
    }

    private fun pixelChannel(index: Int, shift: Int): Float =
        ((argb[index] ushr shift) and 0xFF) / 255.0f

    /** Downscales this image so its longest edge is at most [maxEdge], preserving its aspect ratio. */
    fun scaledToMaxEdge(maxEdge: Int): SubjectImage {
        val safeEdge = maxEdge.coerceAtLeast(1)
        val longest = maxOf(width, height)
        if (longest <= safeEdge) return this
        val scale = safeEdge.toFloat() / longest
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return SubjectImage(targetWidth, targetHeight, resampleArgb(this, targetWidth, targetHeight))
    }

    private companion object {
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val BLUE_SHIFT = 0

        fun mix(a: Float, b: Float, weight: Float): Float = a + (b - a) * weight

        /** Area-aware bilinear resample of [source] onto a [width] x [height] grid. */
        fun resampleArgb(source: SubjectImage, width: Int, height: Int): IntArray {
            val out = IntArray(width * height)
            for (row in 0 until height) {
                val v = (row + 0.5f) / height
                for (column in 0 until width) {
                    val u = (column + 0.5f) / width
                    val r = source.redAt(u, v)
                    val g = source.greenAt(u, v)
                    val b = source.blueAt(u, v)
                    out[row * width + column] = pack(r, g, b)
                }
            }
            return out
        }

        fun pack(r: Float, g: Float, b: Float): Int {
            val red = (r.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
            val green = (g.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
            val blue = (b.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
            return (0xFF shl 24) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
        }
    }
}

/**
 * Continuous `0..1` subject foreground probability map returned by a [SubjectSegmenter].
 *
 * Values are straight (non-premultiplied) alpha: `0` is guaranteed background, `1` is guaranteed
 * foreground and the transition band carries the hair detail. The grid is the upright pre-crop
 * source space, so the renderer can map it through the same Pass A transform as the pixels.
 *
 * @property width Mask width in pixels.
 * @property height Mask height in pixels.
 * @property alpha Row-major alpha values; length is [width] x [height].
 */
data class SubjectMask(
    val width: Int,
    val height: Int,
    val alpha: FloatArray
) {

    init {
        require(width > 0 && height > 0) { "Subject mask dimensions must be positive" }
        require(alpha.size == width * height) { "Subject mask buffer must match its dimensions" }
    }

    /**
     * Bilinear sample at normalized [u], [v], clamped to the mask border.
     *
     * @return The alpha in `[0, 1]`, or `0` for a non-finite coordinate.
     */
    fun sample(u: Float, v: Float): Float {
        if (!u.isFinite() || !v.isFinite()) return 0.0f
        val x = (u.coerceIn(0.0f, 1.0f) * (width - 1)).coerceIn(0.0f, (width - 1).toFloat())
        val y = (v.coerceIn(0.0f, 1.0f) * (height - 1)).coerceIn(0.0f, (height - 1).toFloat())
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = sampleAt(y0 * width + x0, y0 * width + x1, fx)
        val bottom = sampleAt(y1 * width + x0, y1 * width + x1, fx)
        return (top + (bottom - top) * fy).coerceIn(0.0f, 1.0f)
    }

    /** Resamples this mask onto [targetWidth] x [targetHeight] with bilinear interpolation. */
    fun resampled(targetWidth: Int, targetHeight: Int): SubjectMask {
        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        if (safeWidth == width && safeHeight == height) return this
        val out = FloatArray(safeWidth * safeHeight)
        for (row in 0 until safeHeight) {
            val v = (row + 0.5f) / safeHeight
            for (column in 0 until safeWidth) {
                val u = (column + 0.5f) / safeWidth
                out[row * safeWidth + column] = sample(u, v)
            }
        }
        return SubjectMask(safeWidth, safeHeight, out)
    }

    private fun sampleAt(left: Int, right: Int, weight: Float): Float =
        alpha[left] + (alpha[right] - alpha[left]) * weight
}

/**
 * Produces a [SubjectMask] for one image on the camera-roll device.
 *
 * The interface is deliberately independent of any model runtime so a future still-life/pet
 * segmenter can be added without touching the renderer, the bake or the editor UI. Implementations
 * must be safe to call from a background dispatcher and must serialize their own native sessions.
 */
interface SubjectSegmenter {

    /**
     * Segments [image] with the verified [modelFile].
     *
     * @return A mask in upright pre-crop source space, or a failure carrying the recoverable reason.
     */
    fun segment(image: SubjectImage, modelFile: File): Result<SubjectMask>
}
