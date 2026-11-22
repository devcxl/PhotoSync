package cn.devcxl.photosync.editor.domain

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * CPU renderer that converts a 16-bit linear [LinearRawBuffer] into `ARGB_8888` pixels for the
 * gallery preview cache.
 *
 * The chain mirrors the GL preview pipeline exactly: auto-bright gain → `camera_default` look →
 * [ToneShaderMath] (white balance, linear tone, display tone) → sRGB encode, so the "Done"
 * thumbnail matches what the user saw in the editor. It never allocates per-pixel objects, so
 * saving a 2048px preview stays fast.
 */
internal object LinearRawPreviewRenderer {

    private const val LINEAR_LUT_SIZE = 65536
    private const val CHANNELS = 4
    private const val CHANNELS_RGB = 3

    /**
     * Renders [source] into packed `ARGB_8888` pixels, row-major.
     *
     * @param source Decoded linear RAW pixels.
     * @param graph Edit graph providing the base look, tone sliders and white balance.
     */
    fun renderArgb(source: LinearRawBuffer, graph: EditGraph): IntArray {
        val params = ToneShaderParams.from(graph)
        val lookLut = buildLookLut(graph.baseLook, resolveGain(source.autoBrightGain))
        val pixelCount = source.width * source.height
        val argb = IntArray(pixelCount)
        val sample = FloatArray(CHANNELS)
        val display = FloatArray(CHANNELS_RGB)
        val linear = FloatArray(CHANNELS_RGB)
        for (index in 0 until pixelCount) {
            source.readPixel(index, sample)
            display[0] = lookLut[toLinearLutIndex(sample[0])]
            display[1] = lookLut[toLinearLutIndex(sample[1])]
            display[2] = lookLut[toLinearLutIndex(sample[2])]
            if (graph.baseLook == BaseLook.CAMERA_DEFAULT) {
                CameraDefaultLookMath.applyVibrance(display[0], display[1], display[2], display)
                // The shader clamps inside `cameraDefaultLook`; do the same before decoding.
                display[0] = display[0].coerceIn(0.0f, 1.0f)
                display[1] = display[1].coerceIn(0.0f, 1.0f)
                display[2] = display[2].coerceIn(0.0f, 1.0f)
            }
            argb[index] = if (params.isIdentity) {
                pack(display)
            } else {
                linear[0] = ExposureShaderMath.decodeSrgb(display[0])
                linear[1] = ExposureShaderMath.decodeSrgb(display[1])
                linear[2] = ExposureShaderMath.decodeSrgb(display[2])
                ToneShaderMath.apply(params, linear, display)
                pack(display)
            }
        }
        return argb
    }

    private fun pack(display: FloatArray): Int =
        Srgb8Packer.packRgb(display[0], display[1], display[2])

    private fun resolveGain(autoBrightGain: Float): Float =
        if (autoBrightGain.isFinite() && autoBrightGain > 0.0f) autoBrightGain else 1.0f

    private fun buildLookLut(baseLook: BaseLook, gain: Float): FloatArray =
        FloatArray(LINEAR_LUT_SIZE) { index ->
            val linear = index / 65535.0f * gain
            if (baseLook == BaseLook.CAMERA_DEFAULT) {
                // The browse-page curve is display-referred, so highlights clip there.
                CameraDefaultLookMath.applyToneCurve(
                    ExposureShaderMath.encodeSrgb(linear.coerceIn(0.0f, 1.0f))
                )
            } else {
                // Keep linear headroom above 1.0 so the tone stage can roll it off.
                ExposureShaderMath.encodeSrgb(linear)
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
