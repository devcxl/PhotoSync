package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.HalfFloatBits
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
     * @param patchRect Full patch bbox in **buffer** pixel coordinates; its extent defines how the
     *   patch scales.
     * @param clip Sub-rectangle of [patchRect] that lies inside the buffer; defaults to the bbox.
     * @return Number of pixels whose alpha was greater than zero.
     */
    fun blendArgb(
        pixels: IntArray,
        stride: Int,
        patchRect: InpaintPixelRect,
        decoded: InpaintDecodedPatch,
        clip: InpaintPixelRect = patchRect
    ): Long {
        require(stride > 0) { "Stride must be positive" }
        val bufferHeight = pixels.size / stride
        val clipped = clip.clampedTo(stride, bufferHeight)
        if (clipped.isEmpty) return 0L
        var blended = 0L
        for (y in clipped.top until clipped.bottom) {
            val row = y * stride
            for (x in clipped.left until clipped.right) {
                val sampleX = x - patchRect.left
                val sampleY = y - patchRect.top
                val alpha = sampleAlpha(decoded, sampleX, sampleY, patchRect.width, patchRect.height)
                if (alpha == 0) continue
                blended++
                val index = row + x
                val source = pixels[index]
                val patch = sampleRgb(decoded, sampleX, sampleY, patchRect.width, patchRect.height)
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

    /**
     * Blends [decoded] into an interleaved `RGBA` half-float buffer holding scene-linear light.
     *
     * ## Why half floats
     * The linear RAW preview and the Tier 2 bake slice both keep their pixels as `GL_RGBA16F`
     * texels. Composing straight into that layout keeps the slice and the full frame on one code
     * path: the only difference between them is the window that is handed in.
     *
     * @param buffer Little-endian half-float texels covering at least [bufferWidth] x [bufferHeight].
     * @param rect Patch bbox in **buffer** pixel coordinates; its extent defines how the patch scales.
     * @param clip Sub-rectangle of [rect] that lies inside [buffer]; defaults to the whole bbox.
     * @return Number of pixels whose alpha was greater than zero.
     */
    fun blendLinearHalfFloat(
        buffer: ByteBuffer,
        bufferWidth: Int,
        bufferHeight: Int,
        rect: InpaintPixelRect,
        decoded: InpaintDecodedPatch,
        clip: InpaintPixelRect = rect
    ): Long {
        val clipped = clip.clampedTo(bufferWidth, bufferHeight)
        if (clipped.isEmpty) return 0L
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        var blended = 0L
        for (y in clipped.top until clipped.bottom) {
            for (x in clipped.left until clipped.right) {
                val alpha = sampleAlpha(decoded, x - rect.left, y - rect.top, rect.width, rect.height)
                if (alpha == 0) continue
                blended++
                val patch = sampleRgb(decoded, x - rect.left, y - rect.top, rect.width, rect.height)
                val texel = (y * bufferWidth + x) * RGBA_TEXEL_BYTES
                val weight = alpha / 255.0f
                for (channel in 0 until RGB_CHANNELS) {
                    val offset = texel + channel * HALF_BYTES
                    val current = HalfFloatBits.fromBits(view.getShort(offset))
                    val target = sRGB_LUT[(patch shr (16 - channel * 8)) and 0xFF]
                    val value = current + (target - current) * weight
                    // Half floats carry HDR highlights fine; only a negative sample is impossible.
                    view.putShort(offset, HalfFloatBits.toBits(value.coerceAtLeast(0.0f)))
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
        const val RGBA_TEXEL_BYTES = 8
        const val HALF_BYTES = 2

        /** sRGB-decoded 8-bit patch samples, the domain every `GL_RGBA16F` source lives in. */
        val sRGB_LUT = FloatArray(256) { sample ->
            ExposureShaderMath.decodeSrgb(sample / 255.0f)
        }
    }
}
