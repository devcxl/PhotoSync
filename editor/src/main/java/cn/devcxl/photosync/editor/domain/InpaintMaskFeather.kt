package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Feathered blending alpha of one inpainting patch.
 *
 * Mirrors the official MI-GAN pipeline: the hole mask is grown with a 3 x 3 max-pool (stride 1,
 * padding 1) and then smoothed with a 5-tap Gaussian (`sigma = 1.0`). The resulting ramp is about
 * 3 px wide past the painted edge, which is the 3–5 px feather the design asks for and is what hides
 * a 1–2 px seam without turning the repair into a blur.
 */
internal object InpaintMaskFeather {

    /** Values within this many pixels of the hole are used to match the edge colour. */
    const val EDGE_MATCH_RADIUS_PX: Int = 8

    private val GAUSSIAN_KERNEL = floatArrayOf(
        0.05448868f,
        0.24420134f,
        0.40261996f,
        0.24420134f,
        0.05448868f
    )

    /**
     * Computes the feathered alpha of [rect] from [mask].
     *
     * @return Row-major alpha bytes of `rect.width * rect.height`, `0..255`.
     */
    fun feather(mask: InpaintMaskBuffer, rect: InpaintPixelRect): ByteArray {
        val width = rect.width
        val height = rect.height
        var current = FloatArray(width * height) { index ->
            val x = rect.left + index % width
            val y = rect.top + index / width
            if (mask.coverageAt(x, y) >= PAINTED_THRESHOLD) 1.0f else 0.0f
        }
        current = maxPool3x3(current, width, height)
        val horizontal = FloatArray(width * height)
        gaussianPass(current, horizontal, width, height, horizontal = true)
        val vertical = FloatArray(width * height)
        gaussianPass(horizontal, vertical, width, height, horizontal = false)
        return ByteArray(width * height) { index ->
            (vertical[index] * 255.0f).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    /**
     * Grows [alpha] by [radius] pixels without allocating a full-image buffer.
     *
     * The ring used for colour matching is exactly `dilated && !alpha`, so it lies outside the
     * feathered hole and can never change a destination pixel.
     */
    fun dilate(alpha: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        if (radius <= 0) return alpha.copyOf()
        val horizontal = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = 0
                val from = max(0, x - radius)
                val to = minOf(width - 1, x + radius)
                for (sample in from..to) {
                    val candidate = alpha[row + sample].toInt() and 0xFF
                    if (candidate > value) value = candidate
                }
                horizontal[row + x] = value.toByte()
            }
        }
        val dilated = ByteArray(width * height)
        for (y in 0 until height) {
            val from = max(0, y - radius)
            val to = minOf(height - 1, y + radius)
            for (x in 0 until width) {
                var value = 0
                for (sample in from..to) {
                    val candidate = horizontal[sample * width + x].toInt() and 0xFF
                    if (candidate > value) value = candidate
                }
                dilated[y * width + x] = value.toByte()
            }
        }
        return dilated
    }

    private fun maxPool3x3(source: FloatArray, width: Int, height: Int): FloatArray {
        val horizontal = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = source[row + x]
                if (x > 0) value = max(value, source[row + x - 1])
                if (x + 1 < width) value = max(value, source[row + x + 1])
                horizontal[row + x] = value
            }
        }
        val pooled = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            val above = if (y > 0) row - width else row
            val below = if (y + 1 < height) row + width else row
            for (x in 0 until width) {
                pooled[row + x] = max(max(horizontal[row + x], horizontal[above + x]), horizontal[below + x])
            }
        }
        return pooled
    }

    private fun gaussianPass(
        source: FloatArray,
        destination: FloatArray,
        width: Int,
        height: Int,
        horizontal: Boolean
    ) {
        val radius = GAUSSIAN_KERNEL.size / 2
        if (horizontal) {
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    var sum = 0.0f
                    for (tap in GAUSSIAN_KERNEL.indices) {
                        val sampleX = (x + tap - radius).coerceIn(0, width - 1)
                        sum += source[row + sampleX] * GAUSSIAN_KERNEL[tap]
                    }
                    destination[row + x] = sum
                }
            }
        } else {
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    var sum = 0.0f
                    for (tap in GAUSSIAN_KERNEL.indices) {
                        val sampleY = (y + tap - radius).coerceIn(0, height - 1)
                        sum += source[sampleY * width + x] * GAUSSIAN_KERNEL[tap]
                    }
                    destination[row + x] = sum
                }
            }
        }
    }

    private const val PAINTED_THRESHOLD = 128
}
