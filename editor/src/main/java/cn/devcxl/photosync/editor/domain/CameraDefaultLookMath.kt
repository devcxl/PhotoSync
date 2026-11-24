package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Pure reference math for the RAW `camera_default` base look.
 *
 * It reproduces the browse-page tone pipeline in `libraw/src/main/cpp/raw_postprocess.cpp`
 * (shadow/midtone power curve blended with a smoothstep S-curve, then shadow-faded vibrance)
 * so a RAW photo opened in the editor with every slider at zero renders exactly like the
 * main gallery decode. The GLSL shader in `ExposureGlProgram` mirrors this object; both are
 * kept in lock-step by [CameraDefaultLookMathTest].
 */
object CameraDefaultLookMath {

    /** Power exponent applied to gamma-encoded sRGB samples (`ToneCurveParams::shadowMidtoneGamma`). */
    const val SHADOW_MIDTONE_GAMMA = 0.78f

    /** Blend weight of the smoothstep S-curve (`ToneCurveParams::sCurveStrength`). */
    const val S_CURVE_STRENGTH = 0.12f

    /** Adaptive saturation boost for muted tones (`ToneCurveParams::vibrance`). */
    const val VIBRANCE = 0.18f

    private const val LUMINANCE_R = 0.2126f
    private const val LUMINANCE_G = 0.7152f
    private const val LUMINANCE_B = 0.0722f

    // The C++ vibrance fade operates on 0..255 display samples; these are the normalized bounds.
    private const val SHADOW_FADE_START = 12.0f / 255.0f
    private const val SHADOW_FADE_SPAN = 48.0f / 255.0f

    private const val EXPOSURE_EPSILON = 1e-4f

    /**
     * Evaluates the browse-page tone curve for a normalized display-domain sample in `[0, 1]`.
     */
    fun applyToneCurve(x: Float): Float {
        val clamped = x.coerceIn(0.0f, 1.0f)
        val lifted = clamped.pow(SHADOW_MIDTONE_GAMMA)
        val sCurve = lifted * lifted * (3.0f - 2.0f * lifted)
        val k = S_CURVE_STRENGTH
        return (1.0f - k) * lifted + k * sCurve
    }

    /**
     * Applies the browse-page vibrance boost to display-domain channels in `[0, 1]`.
     *
     * Deep shadows (`luma <= 12/255`) are left untouched so lifted shadow noise is never
     * colorized, and already-saturated colors receive proportionally less boost.
     */
    fun applyVibrance(red: Float, green: Float, blue: Float): FloatArray {
        val out = FloatArray(3)
        applyVibrance(red, green, blue, out)
        return out
    }

    /**
     * Allocation-free variant of [applyVibrance] that writes the result into [out] (`RGB`).
     *
     * @param out Destination array with at least three slots.
     */
    fun applyVibrance(red: Float, green: Float, blue: Float, out: FloatArray) {
        val luma = LUMINANCE_R * red + LUMINANCE_G * green + LUMINANCE_B * blue
        val shadowFade = ((luma - SHADOW_FADE_START) / SHADOW_FADE_SPAN).coerceIn(0.0f, 1.0f)
        if (shadowFade <= 0.0f) {
            out[0] = red
            out[1] = green
            out[2] = blue
            return
        }
        val saturation = maxOf(red, green, blue) - minOf(red, green, blue)
        val scale = 1.0f + VIBRANCE * shadowFade * max(0.0f, 1.0f - saturation)
        out[0] = luma + (red - luma) * scale
        out[1] = luma + (green - luma) * scale
        out[2] = luma + (blue - luma) * scale
    }

    /**
     * Applies the full `camera_default` display-domain look (tone curve then vibrance) to an
     * sRGB-encoded sample, clamping the result back into the displayable `[0, 1]` range.
     */
    fun applyDisplayLook(red: Float, green: Float, blue: Float): FloatArray {
        val toned = applyVibrance(
            applyToneCurve(red),
            applyToneCurve(green),
            applyToneCurve(blue)
        )
        return floatArrayOf(
            toned[0].coerceIn(0.0f, 1.0f),
            toned[1].coerceIn(0.0f, 1.0f),
            toned[2].coerceIn(0.0f, 1.0f)
        )
    }

    /**
     * Renders a linear-light RGB sample into an sRGB display triplet, mirroring the GL pipeline
     * used by the editor preview.
     *
     * @param linearRed Linear-light red sample, where `1.0` is the diffuse white point and
     *   values above `1.0` are retained highlight headroom.
     * @param autoBrightGain Histogram auto-brightening gain reported by the native decoder.
     * @param exposureEv Exposure adjustment in stops.
     * @param baseLook Active [BaseLook]; `camera_default` inserts the browse-page base look
     *   before exposure so a zero-parameter look matches the gallery decode.
     * @return Three sRGB display-domain channels in `[0, 1]`.
     */
    fun renderLinearToSrgb(
        linearRed: Float,
        linearGreen: Float,
        linearBlue: Float,
        autoBrightGain: Float,
        exposureEv: Float,
        baseLook: BaseLook
    ): FloatArray {
        val gain = if (autoBrightGain.isFinite() && autoBrightGain > 0.0f) autoBrightGain else 1.0f
        var red = max(linearRed, 0.0f) * gain
        var green = max(linearGreen, 0.0f) * gain
        var blue = max(linearBlue, 0.0f) * gain

        if (baseLook == BaseLook.CAMERA_DEFAULT) {
            val display = applyDisplayLook(
                ExposureShaderMath.encodeSrgb(red.coerceIn(0.0f, 1.0f)),
                ExposureShaderMath.encodeSrgb(green.coerceIn(0.0f, 1.0f)),
                ExposureShaderMath.encodeSrgb(blue.coerceIn(0.0f, 1.0f))
            )
            red = ExposureShaderMath.decodeSrgb(display[0])
            green = ExposureShaderMath.decodeSrgb(display[1])
            blue = ExposureShaderMath.decodeSrgb(display[2])
        }

        if (abs(exposureEv) >= EXPOSURE_EPSILON) {
            val scale = 2.0f.pow(exposureEv)
            red = ExposureShaderMath.softShoulder(red * scale)
            green = ExposureShaderMath.softShoulder(green * scale)
            blue = ExposureShaderMath.softShoulder(blue * scale)
        }

        return floatArrayOf(
            ExposureShaderMath.encodeSrgb(red).coerceIn(0.0f, 1.0f),
            ExposureShaderMath.encodeSrgb(green).coerceIn(0.0f, 1.0f),
            ExposureShaderMath.encodeSrgb(blue).coerceIn(0.0f, 1.0f)
        )
    }
}
