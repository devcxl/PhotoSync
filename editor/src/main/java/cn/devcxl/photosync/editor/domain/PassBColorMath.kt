package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * CPU reference for the full Pass B pipeline, matching `ExposureShaderSources` and
 * `ColorShaderGlsl` stage for stage:
 * 1. Pass B1 ([ToneShaderMath]): linear trims + soft shoulder + blacks/contrast/glow + pre-OKLCH
 *    soft gamut compression.
 * 2. 1024-entry 1D Curve LUT (`RGB` composite then individual `R`, `G`, `B`).
 * 3. OKLCH Saturation, Vibrance (`w_sat`, `w_shadow`, 45° skin protection), low-chroma-gated
 *    8-band HSL, and pre-artistic-toning edge Defringe.
 * 4. Cartesian OKLab Split Toning + post-grade soft gamut compression + sRGB encode.
 * 5. Film Fade (`y(1 - 0.15f) + 0.12f` + subtle desaturation).
 */
object PassBColorMath {

    private const val CHANNELS = 3
    private const val DEG_TO_RAD = (PI / 180.0).toFloat()
    private const val MAX_HUE_SHIFT_RAD = (PI / 6.0).toFloat()
    private const val HSL_LUMA_SCALE = 0.25f

    private const val VIBRANCE_CHROMA_CEIL = 0.32f
    private const val VIBRANCE_SHADOW_LO = 0.05f
    private const val VIBRANCE_SHADOW_HI = 0.20f
    private const val HSL_CHROMA_GATE_LO = 0.01f
    private const val HSL_CHROMA_GATE_HI = 0.05f
    private const val DEFRINGE_GRAD_LO = 0.04f
    private const val DEFRINGE_GRAD_HI = 0.20f
    private const val DEFRINGE_CHROMA_LO = 0.03f
    private const val DEFRINGE_CHROMA_HI = 0.12f
    private const val DEFRINGE_MAX_DESAT = 0.85f
    private const val SPLIT_BALANCE_SHIFT = 0.35f
    private const val SPLIT_HI_LO = 0.35f
    private const val SPLIT_HI_HI = 0.85f
    private const val SPLIT_SH_LO = 0.15f
    private const val SPLIT_SH_HI = 0.65f
    private const val FADE_SLOPE_ATTENUATION = 0.15f
    private const val FADE_BLACK_LIFT = 0.12f
    private const val FADE_DESATURATION = 0.18f

    /** OKLCH hue centers (radians) for Red, Orange, Yellow, Green, Aqua, Blue, Purple, Magenta. */
    internal val HSL_BAND_CENTERS_RAD = floatArrayOf(
        29.0f * DEG_TO_RAD,
        53.0f * DEG_TO_RAD,
        110.0f * DEG_TO_RAD,
        142.0f * DEG_TO_RAD,
        195.0f * DEG_TO_RAD,
        264.0f * DEG_TO_RAD,
        294.0f * DEG_TO_RAD,
        328.0f * DEG_TO_RAD
    )

    private const val HSL_SIGMA_RAD = 26.0f * DEG_TO_RAD
    private const val PURPLE_FRINGE_RAD = 315.0f * DEG_TO_RAD
    private const val PURPLE_FRINGE_SIGMA_RAD = 18.0f * DEG_TO_RAD
    private const val GREEN_FRINGE_RAD = 142.0f * DEG_TO_RAD
    private const val GREEN_FRINGE_SIGMA_RAD = 16.0f * DEG_TO_RAD

    /** Reusable 3-channel scratch arrays to avoid per-pixel allocations in CPU preview loops. */
    internal class Scratch {
        val working = FloatArray(CHANNELS)
        val linear = FloatArray(CHANNELS)
        val lab = FloatArray(CHANNELS)
        val lch = FloatArray(CHANNELS)
    }

    /** Runs Pass B1 + Pass B2 on one linear-light sRGB sample, writing display sRGB in `[0, 1]`. */
    internal fun apply(
        toneParams: ToneShaderParams,
        colorParams: ColorGradingParams,
        curveLut: FloatArray?,
        linear: FloatArray,
        outSrgb: FloatArray,
        edgeGradient: Float = 0.0f,
        scratch: Scratch = Scratch()
    ) {
        ToneShaderMath.apply(toneParams, linear, outSrgb)
        if (!colorParams.isIdentity) {
            applyDisplayColor(colorParams, curveLut, outSrgb, outSrgb, edgeGradient, scratch)
        }
    }

    /** Public convenience overload for tests and single-sample callers. */
    fun apply(
        toneParams: ToneShaderParams,
        colorParams: ColorGradingParams,
        curveLut: FloatArray?,
        linear: FloatArray,
        outSrgb: FloatArray,
        edgeGradient: Float = 0.0f
    ) {
        apply(toneParams, colorParams, curveLut, linear, outSrgb, edgeGradient, Scratch())
    }

    /** Runs only Pass B2 (display-domain color) on an sRGB `[0, 1]` sample. */
    fun applyDisplayColor(
        colorParams: ColorGradingParams,
        curveLut: FloatArray?,
        srgbIn: FloatArray,
        outSrgb: FloatArray,
        edgeGradient: Float = 0.0f
    ) {
        applyDisplayColor(colorParams, curveLut, srgbIn, outSrgb, edgeGradient, Scratch())
    }

    internal fun applyDisplayColor(
        colorParams: ColorGradingParams,
        curveLut: FloatArray?,
        srgbIn: FloatArray,
        outSrgb: FloatArray,
        edgeGradient: Float,
        scratch: Scratch
    ) {
        if (colorParams.isIdentity) {
            outSrgb[0] = srgbIn[0].coerceIn(0.0f, 1.0f)
            outSrgb[1] = srgbIn[1].coerceIn(0.0f, 1.0f)
            outSrgb[2] = srgbIn[2].coerceIn(0.0f, 1.0f)
            return
        }
        val working = scratch.working
        for (c in 0 until CHANNELS) working[c] = srgbIn[c].coerceIn(0.0f, 1.0f)
        if (colorParams.hasCurve && curveLut != null) {
            CurveLutBuilder.applyCurve(curveLut, working, working)
        }
        if (colorParams.needsOklabStage) {
            runOklabStage(colorParams, working, edgeGradient, scratch)
        }
        applyFade(colorParams.fade, working, outSrgb)
    }

    private fun runOklabStage(
        params: ColorGradingParams,
        workingSrgb: FloatArray,
        edgeGradient: Float,
        scratch: Scratch
    ) {
        val linear = scratch.linear
        val lab = scratch.lab
        val lch = scratch.lch
        for (c in 0 until CHANNELS) linear[c] = ExposureShaderMath.decodeSrgb(workingSrgb[c])
        OklabColorMath.linearSrgbToOklab(linear, lab)
        OklabColorMath.oklabToOklch(lab, lch)

        applySaturationAndVibrance(params, lch)
        if (params.hasHsl) applyHslBands(params, lch)
        if (params.defringe > 0.0f && edgeGradient > 0.0f) applyDefringe(params.defringe, lch, edgeGradient)

        OklabColorMath.oklchToOklab(lch, lab)
        applySplitToning(params, lab)

        OklabColorMath.oklabToLinearSrgb(lab, linear)
        OklabColorMath.softGamutCompress(linear, linear)
        for (c in 0 until CHANNELS) {
            workingSrgb[c] = ExposureShaderMath.encodeSrgb(linear[c].coerceIn(0.0f, 1.0f))
                .coerceIn(0.0f, 1.0f)
        }
    }

    private fun applySaturationAndVibrance(params: ColorGradingParams, lch: FloatArray) {
        lch[1] *= max(0.0f, 1.0f + params.saturation)
        if (abs(params.vibrance) > 1e-6f) {
            val wSat = (1.0f - lch[1] / VIBRANCE_CHROMA_CEIL).coerceIn(0.0f, 1.0f)
            val wShadow = ToneShaderMath.smoothStep(VIBRANCE_SHADOW_LO, VIBRANCE_SHADOW_HI, lch[0])
            val gain = 1.0f + params.vibrance * wSat * wShadow * OklabColorMath.skinProtect(lch[2])
            lch[1] *= max(0.0f, gain)
        }
    }

    private fun applyHslBands(params: ColorGradingParams, lch: FloatArray) {
        val chromaGate = ToneShaderMath.smoothStep(HSL_CHROMA_GATE_LO, HSL_CHROMA_GATE_HI, lch[1])
        var weightSum = 0.0f
        var dHue = 0.0f
        var dSat = 0.0f
        var dLuma = 0.0f
        for (i in 0 until ColorGradingParams.HSL_BAND_COUNT) {
            val diff = OklabColorMath.shortestAngleDiff(lch[2], HSL_BAND_CENTERS_RAD[i]) / HSL_SIGMA_RAD
            val w = exp(-0.5f * diff * diff)
            weightSum += w
            dHue += w * params.hslComponent(i, 0)
            dSat += w * params.hslComponent(i, 1)
            dLuma += w * params.hslComponent(i, 2)
        }
        val invWeight = 1.0f / max(weightSum, 1.0f)
        lch[2] += dHue * invWeight * MAX_HUE_SHIFT_RAD * chromaGate
        lch[1] *= max(0.0f, 1.0f + dSat * invWeight)
        lch[0] = (lch[0] + dLuma * invWeight * HSL_LUMA_SCALE * chromaGate).coerceIn(0.0f, 1.0f)
    }

    private fun applyDefringe(defringe: Float, lch: FloatArray, edgeGradient: Float) {
        val edgeWeight = ToneShaderMath.smoothStep(DEFRINGE_GRAD_LO, DEFRINGE_GRAD_HI, edgeGradient)
        val chromaGate = ToneShaderMath.smoothStep(DEFRINGE_CHROMA_LO, DEFRINGE_CHROMA_HI, lch[1])
        val pDiff = OklabColorMath.shortestAngleDiff(lch[2], PURPLE_FRINGE_RAD) / PURPLE_FRINGE_SIGMA_RAD
        val gDiff = OklabColorMath.shortestAngleDiff(lch[2], GREEN_FRINGE_RAD) / GREEN_FRINGE_SIGMA_RAD
        val fringeHue = max(exp(-0.5f * pDiff * pDiff), exp(-0.5f * gDiff * gDiff))
        val amount = (defringe * edgeWeight * chromaGate * fringeHue).coerceIn(0.0f, 1.0f)
        lch[1] *= 1.0f - amount * DEFRINGE_MAX_DESAT
    }

    private fun applySplitToning(params: ColorGradingParams, lab: FloatArray) {
        if (!params.hasSplit) return
        val lightness = lab[0].coerceIn(0.0f, 1.0f)
        val shiftedL = (lightness + SPLIT_BALANCE_SHIFT * params.splitBalance * (1.0f - abs(2.0f * lightness - 1.0f)))
            .coerceIn(0.0f, 1.0f)
        val wHi = ToneShaderMath.smoothStep(SPLIT_HI_LO, SPLIT_HI_HI, shiftedL)
        val wSh = 1.0f - ToneShaderMath.smoothStep(SPLIT_SH_LO, SPLIT_SH_HI, shiftedL)
        lab[1] += wHi * params.splitHiA + wSh * params.splitShA
        lab[2] += wHi * params.splitHiB + wSh * params.splitShB
    }

    private fun applyFade(fade: Float, srgb: FloatArray, outSrgb: FloatArray) {
        if (fade <= 0.0f) {
            outSrgb[0] = srgb[0]
            outSrgb[1] = srgb[1]
            outSrgb[2] = srgb[2]
            return
        }
        val r = srgb[0] * (1.0f - FADE_SLOPE_ATTENUATION * fade) + FADE_BLACK_LIFT * fade
        val g = srgb[1] * (1.0f - FADE_SLOPE_ATTENUATION * fade) + FADE_BLACK_LIFT * fade
        val b = srgb[2] * (1.0f - FADE_SLOPE_ATTENUATION * fade) + FADE_BLACK_LIFT * fade
        val luma = ToneShaderMath.luminance(r, g, b)
        val desat = FADE_DESATURATION * fade
        outSrgb[0] = (r + (luma - r) * desat).coerceIn(0.0f, 1.0f)
        outSrgb[1] = (g + (luma - g) * desat).coerceIn(0.0f, 1.0f)
        outSrgb[2] = (b + (luma - b) * desat).coerceIn(0.0f, 1.0f)
    }
}
