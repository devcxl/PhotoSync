package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.max

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * CPU renderer that converts a 16-bit linear [LinearRawBuffer] into `ARGB_8888` pixels for the
 * gallery preview cache.
 *
 * Preserves linear highlight headroom (`> 1.0`) even when [BaseLook.CAMERA_DEFAULT] is active so
 * negative Highlights/Whites/Exposure sliders recover RAW detail identically to `ExposureShaderSources`.
 */
internal object LinearRawPreviewRenderer {

    private const val LINEAR_LUT_SIZE = 65536
    private const val CHANNELS = 4
    private const val CHANNELS_RGB = 3

    /**
     * Renders [source] into packed `ARGB_8888` pixels, row-major.
     */
    fun renderArgb(source: LinearRawBuffer, graph: EditGraph): IntArray {
        val toneParams = ToneShaderParams.from(graph)
        val colorParams = ColorGradingParams.from(graph)
        val curveLut = if (colorParams.hasCurve) CurveLutBuilder.buildFloatLut(graph.color.curve) else null
        val gain = resolveGain(source.autoBrightGain)
        val lookLut = buildBaseLookLut(graph.baseLook, gain)
        val width = source.width
        val height = source.height
        val pixelCount = width * height
        val lumaMap = if (colorParams.defringe > 0.0f) {
            buildBaseLumaMap(source, gain, graph.baseLook, lookLut, pixelCount)
        } else {
            null
        }
        val argb = IntArray(pixelCount)
        val sample = FloatArray(CHANNELS)
        val display = FloatArray(CHANNELS_RGB)
        val linear = FloatArray(CHANNELS_RGB)
        for (index in 0 until pixelCount) {
            source.readPixel(index, sample)
            computeBaseLookLinear(sample, gain, graph.baseLook, lookLut, linear, display)
            if (toneParams.isIdentity && colorParams.isIdentity) {
                argb[index] = Srgb8Packer.packRgb(
                    ExposureShaderMath.encodeSrgb(linear[0].coerceIn(0.0f, 1.0f)),
                    ExposureShaderMath.encodeSrgb(linear[1].coerceIn(0.0f, 1.0f)),
                    ExposureShaderMath.encodeSrgb(linear[2].coerceIn(0.0f, 1.0f))
                )
            } else {
                val grad = lumaMap?.let { Srgb8Packer.neighborGradient(it, width, height, index) } ?: 0.0f
                PassBColorMath.apply(toneParams, colorParams, curveLut, linear, display, grad)
                argb[index] = Srgb8Packer.packRgb(display[0], display[1], display[2])
            }
        }
        return argb
    }

    private fun buildBaseLumaMap(
        source: LinearRawBuffer,
        gain: Float,
        baseLook: BaseLook,
        lookLut: FloatArray,
        pixelCount: Int
    ): FloatArray {
        val luma = FloatArray(pixelCount)
        val sample = FloatArray(CHANNELS)
        val linear = FloatArray(CHANNELS_RGB)
        val scratch = FloatArray(CHANNELS_RGB)
        for (i in 0 until pixelCount) {
            source.readPixel(i, sample)
            computeBaseLookLinear(sample, gain, baseLook, lookLut, linear, scratch)
            luma[i] = ToneShaderMath.luminance(
                ExposureShaderMath.encodeSrgb(linear[0].coerceIn(0.0f, 1.0f)),
                ExposureShaderMath.encodeSrgb(linear[1].coerceIn(0.0f, 1.0f)),
                ExposureShaderMath.encodeSrgb(linear[2].coerceIn(0.0f, 1.0f))
            )
        }
        return luma
    }

    private fun computeBaseLookLinear(
        sample: FloatArray,
        gain: Float,
        baseLook: BaseLook,
        lookLut: FloatArray,
        outLinear: FloatArray,
        scratchDisplay: FloatArray
    ) {
        if (baseLook == BaseLook.LINEAR_NEUTRAL) {
            for (c in 0 until CHANNELS_RGB) {
                outLinear[c] = (sample[c] * gain).coerceAtLeast(0.0f)
            }
            return
        }
        for (c in 0 until CHANNELS_RGB) {
            scratchDisplay[c] = lookLut[toLinearLutIndex(sample[c])]
        }
        CameraDefaultLookMath.applyVibrance(
            scratchDisplay[0],
            scratchDisplay[1],
            scratchDisplay[2],
            scratchDisplay
        )
        for (c in 0 until CHANNELS_RGB) {
            val clampedDisplay = scratchDisplay[c].coerceIn(0.0f, 1.0f)
            val scaledLinear = (sample[c] * gain).coerceAtLeast(0.0f)
            val headroom = (scaledLinear - 1.0f).coerceAtLeast(0.0f)
            outLinear[c] = ExposureShaderMath.decodeSrgb(clampedDisplay) + headroom
        }
    }

    private fun resolveGain(autoBrightGain: Float): Float =
        if (autoBrightGain.isFinite() && autoBrightGain > 0.0f) autoBrightGain else 1.0f

    private fun buildBaseLookLut(baseLook: BaseLook, gain: Float): FloatArray {
        if (baseLook == BaseLook.LINEAR_NEUTRAL) return FloatArray(0)
        return FloatArray(LINEAR_LUT_SIZE) { index ->
            val linear = (index / 65535.0f * gain).coerceIn(0.0f, 1.0f)
            CameraDefaultLookMath.applyToneCurve(ExposureShaderMath.encodeSrgb(linear))
        }
    }

    private fun toLinearLutIndex(value: Float): Int =
        (value.coerceIn(0.0f, 1.0f) * 65535.0f + 0.5f).toInt().coerceIn(0, LINEAR_LUT_SIZE - 1)
}

/**
 * Shared 8-bit ARGB quantization, packing and neighbor luminance gradient helper used by both
 * [LinearRawPreviewRenderer] and [cn.devcxl.photosync.editor.render.PreviewCacheWriter].
 */
internal object Srgb8Packer {
    const val OPAQUE_ALPHA = -0x1000000
    private const val EIGHT_BIT_MAX = 255

    fun quantize(display: Float): Int =
        (display.coerceIn(0.0f, 1.0f) * EIGHT_BIT_MAX + 0.5f).toInt().coerceIn(0, EIGHT_BIT_MAX)

    fun packRgb(r: Float, g: Float, b: Float, alphaBits: Int = OPAQUE_ALPHA): Int =
        (alphaBits and OPAQUE_ALPHA) or
            (quantize(r) shl 16) or
            (quantize(g) shl 8) or
            quantize(b)

    fun neighborGradient(lumaMap: FloatArray, width: Int, height: Int, index: Int): Float {
        if (width < 2 || height < 2) return 0.0f
        val x = index % width
        val y = index / width
        val left = lumaMap[y * width + (x - 1).coerceAtLeast(0)]
        val right = lumaMap[y * width + (x + 1).coerceAtMost(width - 1)]
        val up = lumaMap[(y - 1).coerceAtLeast(0) * width + x]
        val down = lumaMap[(y + 1).coerceAtMost(height - 1) * width + x]
        return max(abs(right - left), abs(down - up))
    }
}
