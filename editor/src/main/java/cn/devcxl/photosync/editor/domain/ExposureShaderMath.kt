package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Pure reference math for linear-light exposure scaling and soft highlight shoulder roll-off,
 * shared between the GLSL ES 3.00 fragment shader and the deterministic preview cache writer.
 */
object ExposureShaderMath {

    private const val SHOULDER_START = 0.85f
    private const val SHOULDER_SPAN = 1.0f - SHOULDER_START

    /**
     * Applies linear-light exposure scaling (`× 2^EV`) with a soft shoulder roll-off to a
     * single 8-bit sRGB channel sample in `[0, 255]`.
     *
     * Strictly identity when `abs(exposureEv) < 1e-4f`.
     */
    fun applyExposureToSrgb8(sample8: Int, exposureEv: Float): Int {
        val clamped = sample8.coerceIn(0, 255)
        if (abs(exposureEv) < 1e-4f) {
            return clamped
        }
        val srgb = clamped / 255.0f
        val linear = decodeSrgb(srgb) * 2.0f.pow(exposureEv)
        val rolled = softShoulder(linear)
        val encoded = encodeSrgb(rolled.coerceIn(0.0f, 1.0f))
        return (encoded * 255.0f).roundToInt().coerceIn(0, 255)
    }

    /**
     * Builds a 256-entry 8-bit lookup table for fast preview cache generation when only
     * point exposure is active.
     */
    fun buildExposureLut8(exposureEv: Float): IntArray {
        return IntArray(256) { i -> applyExposureToSrgb8(i, exposureEv) }
    }

    fun decodeSrgb(v: Float): Float {
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    fun encodeSrgb(v: Float): Float {
        return if (v <= 0.0031308f) {
            v * 12.92f
        } else {
            1.055f * v.pow(1.0f / 2.4f) - 0.055f
        }
    }

    fun softShoulder(x: Float): Float {
        if (x <= SHOULDER_START) return x.coerceAtLeast(0.0f)
        return SHOULDER_START + SHOULDER_SPAN * tanh((x - SHOULDER_START) / SHOULDER_SPAN)
    }
}
