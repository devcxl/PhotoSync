package cn.devcxl.photosync.editor.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Reusable scalar kernels for the Pass C CPU reference and the 1024px Pre-pass pyramid:
 * luminance extraction, area resampling, box / minimum filters and bilinear lookups.
 *
 * Every kernel clamps its taps to the image border (replicate edge), which is what the GLES
 * `CLAMP_TO_EDGE` sampler does, so the CPU preview and the GPU Pass C chain cannot disagree about
 * the value just outside the frame.
 */
internal object ImageFilters {

    private const val LUMINANCE_R = 0.2126f
    private const val LUMINANCE_G = 0.7152f
    private const val LUMINANCE_B = 0.0722f
    private const val CHANNELS = 3

    /** Rec.709 luminance of an interleaved RGB [rgb] buffer. */
    fun luminance(rgb: FloatArray): FloatArray {
        require(rgb.size % CHANNELS == 0) { "An interleaved RGB buffer needs a multiple of 3 samples" }
        return FloatArray(rgb.size / CHANNELS) { index ->
            val base = index * CHANNELS
            LUMINANCE_R * rgb[base] + LUMINANCE_G * rgb[base + 1] + LUMINANCE_B * rgb[base + 2]
        }
    }

    /** Copies channel [channel] of an interleaved RGB buffer into a planar buffer. */
    fun channel(rgb: FloatArray, channel: Int): FloatArray {
        require(channel in 0 until CHANNELS) { "Channel $channel is outside the RGB range" }
        return FloatArray(rgb.size / CHANNELS) { index -> rgb[index * CHANNELS + channel] }
    }

    /** Area-resamples an interleaved RGB buffer onto a [targetWidth] x [targetHeight] grid. */
    fun resampleRgb(
        rgb: FloatArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        val out = FloatArray(targetWidth * targetHeight * CHANNELS)
        for (channel in 0 until CHANNELS) {
            val plane = channel(rgb, channel)
            val resampled = resamplePlane(plane, width, height, targetWidth, targetHeight)
            for (index in resampled.indices) out[index * CHANNELS + channel] = resampled[index]
        }
        return out
    }

    /**
     * Resamples a planar image to [targetWidth] x [targetHeight].
     *
     * Downscaling averages the exact source footprint of every target pixel, which keeps the dark
     * channel of a 1024px frame faithful to a 256px analysis grid; upscaling interpolates bilinearly
     * so a coarse guide layer never shows blocky steps.
     */
    fun resamplePlane(
        plane: FloatArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        require(width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0) {
            "Image and target sizes must be positive"
        }
        if (width == targetWidth && height == targetHeight) return plane.copyOf()
        val horizontal = if (targetWidth > width) {
            bilinearPlane(plane, width, height, targetWidth, height)
        } else {
            areaResampleHorizontal(plane, width, height, targetWidth)
        }
        return if (targetHeight > height) {
            bilinearPlane(horizontal, targetWidth, height, targetWidth, targetHeight)
        } else {
            areaResampleVertical(horizontal, targetWidth, height, targetHeight)
        }
    }

    /** Bilinear sample of [plane] at normalized coordinates `u, v` in `[0, 1]`. */
    fun sampleBilinear(plane: FloatArray, width: Int, height: Int, u: Float, v: Float): Float {
        val x = (u.coerceIn(0.0f, 1.0f) * (width - 1)).coerceIn(0.0f, (width - 1).toFloat())
        val y = (v.coerceIn(0.0f, 1.0f) * (height - 1)).coerceIn(0.0f, (height - 1).toFloat())
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = mix(plane[y0 * width + x0], plane[y0 * width + x1], fx)
        val bottom = mix(plane[y1 * width + x0], plane[y1 * width + x1], fx)
        return mix(top, bottom, fy)
    }

    /** Box mean with a `(2 * radius + 1)^2` window, edge clamped. */
    fun boxMean(plane: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return plane.copyOf()
        val horizontal = FloatArray(width * height)
        val window = 2 * radius + 1
        for (row in 0 until height) {
            var sum = 0.0
            for (offset in -radius..radius) sum += plane[row * width + offset.coerceIn(0, width - 1)]
            for (column in 0 until width) {
                horizontal[row * width + column] = (sum / window).toFloat()
                val leaving = plane[row * width + (column - radius).coerceIn(0, width - 1)]
                val entering = plane[row * width + (column + radius + 1).coerceIn(0, width - 1)]
                sum += entering - leaving
            }
        }
        val out = FloatArray(width * height)
        for (column in 0 until width) {
            var sum = 0.0
            for (offset in -radius..radius) sum += horizontal[offset.coerceIn(0, height - 1) * width + column]
            for (row in 0 until height) {
                out[row * width + column] = (sum / window).toFloat()
                val leaving = horizontal[(row - radius).coerceIn(0, height - 1) * width + column]
                val entering = horizontal[(row + radius + 1).coerceIn(0, height - 1) * width + column]
                sum += entering - leaving
            }
        }
        return out
    }

    /** Separable minimum filter with a `(2 * radius + 1)^2` window, edge clamped. */
    fun minFilter(plane: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return plane.copyOf()
        val horizontal = FloatArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                var value = Float.MAX_VALUE
                for (offset in -radius..radius) {
                    value = min(value, plane[row * width + (column + offset).coerceIn(0, width - 1)])
                }
                horizontal[row * width + column] = value
            }
        }
        val out = FloatArray(width * height)
        for (column in 0 until width) {
            for (row in 0 until height) {
                var value = Float.MAX_VALUE
                for (offset in -radius..radius) {
                    value = min(value, horizontal[(row + offset).coerceIn(0, height - 1) * width + column])
                }
                out[row * width + column] = value
            }
        }
        return out
    }

    private fun areaResampleHorizontal(
        plane: FloatArray,
        width: Int,
        height: Int,
        targetWidth: Int
    ): FloatArray {
        val out = FloatArray(targetWidth * height)
        val scale = width.toFloat() / targetWidth.toFloat()
        for (row in 0 until height) {
            for (targetX in 0 until targetWidth) {
                val start = targetX * scale
                val end = start + scale
                var sum = 0.0f
                var weight = 0.0f
                for (sourceX in floor(start).toInt() until ceil(end).toInt()) {
                    val overlap = min(end, (sourceX + 1).toFloat()) - max(start, sourceX.toFloat())
                    if (overlap <= 0.0f) continue
                    sum += plane[row * width + sourceX.coerceIn(0, width - 1)] * overlap
                    weight += overlap
                }
                out[row * targetWidth + targetX] = if (weight > 0.0f) sum / weight else 0.0f
            }
        }
        return out
    }

    private fun areaResampleVertical(
        plane: FloatArray,
        width: Int,
        height: Int,
        targetHeight: Int
    ): FloatArray {
        val out = FloatArray(width * targetHeight)
        val scale = height.toFloat() / targetHeight.toFloat()
        for (targetY in 0 until targetHeight) {
            val start = targetY * scale
            val end = start + scale
            for (column in 0 until width) {
                var sum = 0.0f
                var weight = 0.0f
                for (sourceY in floor(start).toInt() until ceil(end).toInt()) {
                    val overlap = min(end, (sourceY + 1).toFloat()) - max(start, sourceY.toFloat())
                    if (overlap <= 0.0f) continue
                    sum += plane[sourceY.coerceIn(0, height - 1) * width + column] * overlap
                    weight += overlap
                }
                out[targetY * width + column] = if (weight > 0.0f) sum / weight else 0.0f
            }
        }
        return out
    }

    private fun bilinearPlane(
        plane: FloatArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        val out = FloatArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val v = if (targetHeight == 1) 0.0f else targetY.toFloat() / (targetHeight - 1).toFloat()
            for (targetX in 0 until targetWidth) {
                val u = if (targetWidth == 1) 0.0f else targetX.toFloat() / (targetWidth - 1).toFloat()
                out[targetY * targetWidth + targetX] = sampleBilinear(plane, width, height, u, v)
            }
        }
        return out
    }

    private fun mix(from: Float, to: Float, weight: Float): Float = from + (to - from) * weight
}
