package cn.devcxl.photosync.editor.domain

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * CPU renderer that converts a 16-bit linear [LinearRawBuffer] into `ARGB_8888` pixels for the
 * gallery preview cache.
 *
 * Preserves linear highlight headroom (`> 1.0`) even when [BaseLook.CAMERA_DEFAULT] is active so
 * negative Highlights/Whites/Exposure sliders recover RAW detail identically to [ExposureShaderSources].
 */
internal object LinearRawPreviewRenderer {

    private const val LINEAR_LUT_SIZE = 65536
    private const val CHANNELS = 4
    private const val CHANNELS_RGB = 3

    /**
     * Renders [source] into packed `ARGB_8888` pixels, row-major.
     */
    fun renderArgb(source: LinearRawBuffer, graph: EditGraph): IntArray {
        val params = ToneShaderParams.from(graph)
        val gain = resolveGain(source.autoBrightGain)
        val lookLut = buildBaseLookLut(graph.baseLook, gain)
        val pixelCount = source.width * source.height
        val argb = IntArray(pixelCount)
        val sample = FloatArray(CHANNELS)
        val display = FloatArray(CHANNELS_RGB)
        val linear = FloatArray(CHANNELS_RGB)
        for (index in 0 until pixelCount) {
            source.readPixel(index, sample)
            computeBaseLookLinear(sample, gain, graph.baseLook, lookLut, linear, display)
            if (params.isIdentity) {
                argb[index] = Srgb8Packer.packRgb(
                    ExposureShaderMath.encodeSrgb(linear[0].coerceIn(0.0f, 1.0f)),
                    ExposureShaderMath.encodeSrgb(linear[1].coerceIn(0.0f, 1.0f)),
                    ExposureShaderMath.encodeSrgb(linear[2].coerceIn(0.0f, 1.0f))
                )
            } else {
                ToneShaderMath.apply(params, linear, display)
                argb[index] = Srgb8Packer.packRgb(display[0], display[1], display[2])
            }
        }
        return argb
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
 * Shared 8-bit ARGB quantization and packing helper used by both [LinearRawPreviewRenderer] and
 * [cn.devcxl.photosync.editor.render.PreviewCacheWriter].
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
}
