package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.pow

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * CPU renderer that converts a 16-bit linear [LinearRawBuffer] into `ARGB_8888` pixels for the
 * gallery preview cache.
 *
 * The lookup-table chain mirrors the GL preview pipeline exactly (`gain -> camera_default look
 * -> exposure -> soft shoulder -> sRGB encode`) so the "Done" thumbnail matches what the user
 * saw in the editor. It never allocates per-pixel objects, so saving a 2048px preview stays fast.
 */
internal object LinearRawPreviewRenderer {

    private const val LINEAR_LUT_SIZE = 65536
    private const val DISPLAY_LUT_SIZE = 4096
    // `linear_neutral` keeps highlight headroom, so an encoded sample can exceed 1.0
    // (gain up to +2 EV pushes 1.0 linear to sRGB 1.64); the table must cover that range.
    private const val DISPLAY_LUT_MAX = 2.0f
    private const val EXPOSURE_EPSILON = 1e-4f
    private const val CHANNELS = 4
    private const val CHANNELS_RGB = 3
    private const val OPAQUE_ALPHA = 0xFF shl 24

    /**
     * Renders [source] into packed `ARGB_8888` pixels, row-major.
     *
     * @param source Decoded linear RAW pixels.
     * @param baseLook Baseline tone mapping; `camera_default` reproduces the browse page.
     * @param exposureEv Exposure adjustment in stops.
     */
    fun renderArgb(source: LinearRawBuffer, baseLook: BaseLook, exposureEv: Float): IntArray {
        val lookLut = buildLookLut(baseLook, resolveGain(source.autoBrightGain))
        val exposureLut = if (abs(exposureEv) >= EXPOSURE_EPSILON) {
            buildExposureLut(exposureEv)
        } else {
            null
        }
        val pixelCount = source.width * source.height
        val argb = IntArray(pixelCount)
        val sample = FloatArray(CHANNELS)
        val display = FloatArray(CHANNELS_RGB)
        for (index in 0 until pixelCount) {
            source.readPixel(index, sample)
            display[0] = lookLut[toLinearLutIndex(sample[0])]
            display[1] = lookLut[toLinearLutIndex(sample[1])]
            display[2] = lookLut[toLinearLutIndex(sample[2])]
            if (baseLook == BaseLook.CAMERA_DEFAULT) {
                CameraDefaultLookMath.applyVibrance(display[0], display[1], display[2], display)
            }
            argb[index] = OPAQUE_ALPHA or
                (quantizeChannel(display[0], exposureLut) shl 16) or
                (quantizeChannel(display[1], exposureLut) shl 8) or
                quantizeChannel(display[2], exposureLut)
        }
        return argb
    }

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
                // Keep linear headroom above 1.0 so the exposure stage can roll it off.
                ExposureShaderMath.encodeSrgb(linear)
            }
        }

    private fun buildExposureLut(exposureEv: Float): FloatArray {
        val scale = 2.0f.pow(exposureEv)
        return FloatArray(DISPLAY_LUT_SIZE) { index ->
            val display = index / (DISPLAY_LUT_SIZE - 1).toFloat() * DISPLAY_LUT_MAX
            val linear = ExposureShaderMath.decodeSrgb(display) * scale
            ExposureShaderMath.encodeSrgb(ExposureShaderMath.softShoulder(linear))
                .coerceIn(0.0f, 1.0f)
        }
    }

    private fun toLinearLutIndex(value: Float): Int =
        (value.coerceIn(0.0f, 1.0f) * 65535.0f + 0.5f).toInt().coerceIn(0, LINEAR_LUT_SIZE - 1)

    private fun quantizeChannel(display: Float, exposureLut: FloatArray?): Int {
        val mapped = if (exposureLut == null) {
            display.coerceIn(0.0f, 1.0f)
        } else {
            val normalized = display.coerceIn(0.0f, DISPLAY_LUT_MAX) / DISPLAY_LUT_MAX
            val bucket = (normalized * (DISPLAY_LUT_SIZE - 1)).toInt()
            exposureLut[bucket.coerceIn(0, DISPLAY_LUT_SIZE - 1)]
        }
        return (mapped * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    }
}
