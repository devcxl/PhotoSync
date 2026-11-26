package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.pow

/**
 * CPU reference for the Pass B foreground/background relative grading of [SubjectOps].
 *
 * The shader (`ColorShaderGlsl.SUBJECT_RELIGHT`) evaluates the same three offsets per pixel:
 * - **exposure** as a linear-light gain of `2^(ev)`, with the slider at `±100` meaning `±2 EV`;
 * - **temperature** as a red/blue channel gain around the green channel, `±100` meaning `±15 %`;
 * - **saturation** as a chroma scale around the luma, `±100` meaning `±80 %`.
 *
 * The foreground and background offsets are mixed by the refined subject alpha, so the transition
 * band of a hair edge receives a proportional blend instead of a hard step. This keeps the
 * operation a *relative* adjustment on top of the global tone/colour grade.
 */
object SubjectRelightMath {

    /** Exposure at the `±100` slider end, in EV. */
    const val EXPOSURE_EV_AT_FULL = 2.0f

    /** Relative red/blue gain at the `±100` temperature slider end. */
    const val TEMPERATURE_GAIN_AT_FULL = 0.15f

    /** Relative chroma scale at the `±100` saturation slider end. */
    const val SATURATION_GAIN_AT_FULL = 0.8f

    private const val SLIDER_SCALE = 100.0f
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    /**
     * True when [subject] carries at least one foreground/background offset.
     *
     * A neutral group must stay a zero-cost branch: no per-pixel work and no extra shader branch.
     */
    fun isActive(subject: SubjectOps): Boolean = subject.hasGradingAdjustments()

    /**
     * Applies the foreground/background relative grade to one linear-light sRGB sample.
     *
     * @param subject Subject group with sanitized slider values.
     * @param weight Refined subject alpha in `[0, 1]`; `1` is pure foreground.
     * @param linear Scene-linear RGB, modified in place and clamped to `>= 0`.
     */
    fun apply(subject: SubjectOps, weight: Float, linear: FloatArray) {
        if (!subject.hasGradingAdjustments()) return
        val foreground = weight.coerceIn(0.0f, 1.0f)
        val ev = mix(subject.bgExposure, subject.fgExposure, foreground) / SLIDER_SCALE
        val temperature = mix(subject.bgTemp, subject.fgTemp, foreground) / SLIDER_SCALE
        val saturation = mix(subject.bgSat, subject.fgSat, foreground) / SLIDER_SCALE
        applyOffsets(ev, temperature, saturation, linear)
    }

    /** Applies already-normalized offsets in `[-1, 1]`; shared with tests of the individual axes. */
    fun applyOffsets(ev: Float, temperature: Float, saturation: Float, linear: FloatArray) {
        val gain = 2.0f.pow(ev * EXPOSURE_EV_AT_FULL)
        val warm = 1.0f + temperature * TEMPERATURE_GAIN_AT_FULL
        val cool = 1.0f - temperature * TEMPERATURE_GAIN_AT_FULL
        var red = max(linear[0], 0.0f) * gain * warm
        var green = max(linear[1], 0.0f) * gain
        var blue = max(linear[2], 0.0f) * gain * cool
        val luma = LUMA_R * red + LUMA_G * green + LUMA_B * blue
        val scale = max(0.0f, 1.0f + saturation * SATURATION_GAIN_AT_FULL)
        red = max(luma + (red - luma) * scale, 0.0f)
        green = max(luma + (green - luma) * scale, 0.0f)
        blue = max(luma + (blue - luma) * scale, 0.0f)
        linear[0] = red
        linear[1] = green
        linear[2] = blue
    }

    private fun mix(from: Int, to: Int, weight: Float): Float =
        from + (to - from) * weight
}
