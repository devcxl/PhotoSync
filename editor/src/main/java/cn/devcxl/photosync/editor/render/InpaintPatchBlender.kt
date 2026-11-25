package cn.devcxl.photosync.editor.render

import kotlin.math.floor
import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.vision.InpaintDecodedPatch

/**
 * Pixel-level blending of one decoded inpainting patch.
 *
 * Kept separate from [InpaintSourceCompositor] so the placement/verification policy and the sample
 * arithmetic can be read independently. Every method writes only inside the supplied bbox window;
 * no full-image scratch buffer is ever allocated.
 */
internal class InpaintPatchBlender {

    /**
     * Blends [decoded] over [pixels], a packed ARGB buffer with [stride] ints per row.
     *
     * @param originX First bbox column inside [pixels].
     * @param originY First bbox row inside [pixels].
     * @return Number of pixels whose alpha was greater than zero.
     */
    fun blendArgb(
        pixels: IntArray,
        stride: Int,
        originX: Int,
        originY: Int,
        rect: InpaintPixelRect,
        decoded: InpaintDecodedPatch
    ): Long {
        var blended = 0L
        for (y in 0 until rect.height) {
            val row = (originY + y) * stride + originX
            for (x in 0 until rect.width) {
                val alpha = sampleAlpha(decoded, x, y, rect.width, rect.height)
                if (alpha == 0) continue
                blended++
                val index = row + x
                val source = pixels[index]
                val patch = sampleRgb(decoded, x, y, rect.width, rect.height)
                val weight = alpha / 255.0f
                val red = lerp((source shr 16) and 0xFF, (patch shr 16) and 0xFF, weight)
                val green = lerp((source shr 8) and 0xFF, (patch shr 8) and 0xFF, weight)
                val blue = lerp(source and 0xFF, patch and 0xFF, weight)
                pixels[index] =
                    (source and 0xFF000000.toInt()) or (red shl 16) or (green shl 8) or blue
            }
        }
        return blended
    }

    /**
     * Blends [decoded] into interleaved RGB 16-bit [pixels] in [domain].
     *
     * @return Number of pixels whose alpha was greater than zero.
     */
    fun blendLinear16(
        pixels: ShortArray,
        width: Int,
        rect: InpaintPixelRect,
        decoded: InpaintDecodedPatch,
        domain: Inpaint16BitDomain
    ): Long {
        val conversion = ChannelConversion(domain)
        var blended = 0L
        for (y in 0 until rect.height) {
            for (x in 0 until rect.width) {
                val alpha = sampleAlpha(decoded, x, y, rect.width, rect.height)
                if (alpha == 0) continue
                blended++
                val patch = sampleRgb(decoded, x, y, rect.width, rect.height)
                val pixelIndex = ((rect.top + y) * width + rect.left + x) * RGB_CHANNELS
                val weight = alpha / 255.0f
                for (channel in 0 until RGB_CHANNELS) {
                    val target = conversion.toDomain((patch shr (16 - channel * 8)) and 0xFF)
                    val current = pixels[pixelIndex + channel].toInt() and 0xFFFF
                    val value = (current + (target - current) * weight)
                        .roundToInt()
                        .coerceIn(0, 0xFFFF)
                    pixels[pixelIndex + channel] = (value and 0xFFFF).toShort()
                }
            }
        }
        return blended
    }

    /** Nearest-neighbour alpha sample; the mask is already feathered, so interpolating is waste. */
    private fun sampleAlpha(
        decoded: InpaintDecodedPatch,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Int {
        val sourceX = (x.toLong() * decoded.width / width).toInt().coerceIn(0, decoded.width - 1)
        val sourceY = (y.toLong() * decoded.height / height).toInt().coerceIn(0, decoded.height - 1)
        return decoded.alpha[sourceY * decoded.width + sourceX].toInt() and 0xFF
    }

    /** Bilinear RGB sample, so a one-pixel bbox rounding difference cannot shift the patch. */
    private fun sampleRgb(
        decoded: InpaintDecodedPatch,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Int {
        val sampleX = (x + 0.5f) * decoded.width / width - 0.5f
        val sampleY = (y + 0.5f) * decoded.height / height - 0.5f
        val x0 = floor(sampleX).toInt().coerceIn(0, decoded.width - 1)
        val y0 = floor(sampleY).toInt().coerceIn(0, decoded.height - 1)
        val x1 = (x0 + 1).coerceAtMost(decoded.width - 1)
        val y1 = (y0 + 1).coerceAtMost(decoded.height - 1)
        val fx = (sampleX - x0).coerceIn(0.0f, 1.0f)
        val fy = (sampleY - y0).coerceIn(0.0f, 1.0f)
        var packed = 0
        for (channel in 0 until RGB_CHANNELS) {
            val shift = 16 - channel * 8
            val topLeft = (decoded.argb[y0 * decoded.width + x0] shr shift) and 0xFF
            val topRight = (decoded.argb[y0 * decoded.width + x1] shr shift) and 0xFF
            val bottomLeft = (decoded.argb[y1 * decoded.width + x0] shr shift) and 0xFF
            val bottomRight = (decoded.argb[y1 * decoded.width + x1] shr shift) and 0xFF
            val top = topLeft + (topRight - topLeft) * fx
            val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
            val value = (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
            packed = packed or (value shl shift)
        }
        return packed
    }

    private fun lerp(from: Int, to: Int, weight: Float): Int =
        (from + (to - from) * weight).roundToInt().coerceIn(0, 255)

    /** Converts 8-bit sRGB patch samples into the target 16-bit buffer domain. */
    private class ChannelConversion(domain: Inpaint16BitDomain) {
        private val lut = IntArray(256) { sample ->
            val display = sample / 255.0f
            val normalized = when (domain) {
                Inpaint16BitDomain.DISPLAY -> display
                Inpaint16BitDomain.LINEAR -> ExposureShaderMath.decodeSrgb(display)
            }
            (normalized * 65535.0f).roundToInt().coerceIn(0, 0xFFFF)
        }

        fun toDomain(sample8: Int): Int = lut[sample8.coerceIn(0, 255)]
    }

    private companion object {
        const val RGB_CHANNELS = 3
    }
}
