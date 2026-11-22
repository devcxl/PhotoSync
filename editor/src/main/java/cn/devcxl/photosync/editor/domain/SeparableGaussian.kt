package cn.devcxl.photosync.editor.domain

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Separable Gaussian blur for the single-channel masks and luminance guides of the portrait group.
 *
 * The kernel is normalized and rebuilt per call, which is cheap because the radii are tiny
 * (`3 px` mask feathering, a dozen pixels for skin frequency separation) compared with the pixel
 * count of a 256x256 mask.
 */
internal object SeparableGaussian {

    /** Largest radius [blur] accepts, so a corrupt size can never allocate a huge kernel. */
    const val MAX_RADIUS = 64

    /** Builds a normalized Gaussian kernel of `2 * radius + 1` taps with `sigma = radius / 2`. */
    fun kernel(radius: Int): FloatArray {
        val safeRadius = radius.coerceIn(0, MAX_RADIUS)
        val sigma = (safeRadius / 2.0f).coerceAtLeast(1e-3f)
        val taps = FloatArray(safeRadius * 2 + 1)
        var sum = 0.0f
        for (offset in -safeRadius..safeRadius) {
            val weight = exp(-(offset * offset) / (2.0f * sigma * sigma))
            taps[offset + safeRadius] = weight
            sum += weight
        }
        if (sum > 0.0f) {
            for (index in taps.indices) taps[index] /= sum
        }
        return taps
    }

    /**
     * Blurs [source] (row-major, `width x height`) with a Gaussian of [radiusPx] and clamps at the
     * borders, so a mask never fades towards an invented outside value.
     */
    fun blur(source: FloatArray, width: Int, height: Int, radiusPx: Int): FloatArray {
        require(source.size == width * height) {
            "a ${width}x$height frame needs ${width * height} samples, got ${source.size}"
        }
        val radius = radiusPx.coerceIn(0, MAX_RADIUS)
        if (radius == 0) return source.copyOf()
        val taps = kernel(radius)
        val horizontal = FloatArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0.0f
                for (offset in -radius..radius) {
                    val sampleX = (x + offset).coerceIn(0, width - 1)
                    sum += source[row + sampleX] * taps[offset + radius]
                }
                horizontal[row + x] = sum
            }
        }
        val out = FloatArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0f
                for (offset in -radius..radius) {
                    val sampleY = (y + offset).coerceIn(0, height - 1)
                    sum += horizontal[sampleY * width + x] * taps[offset + radius]
                }
                out[y * width + x] = sum
            }
        }
        return out
    }

    /** Radius that keeps a blur visually proportional to [shortEdge]. */
    fun radiusFor(shortEdge: Int, fraction: Float, minimum: Int, maximum: Int): Int =
        (shortEdge * fraction).roundToInt().coerceIn(minimum, maximum)
}
