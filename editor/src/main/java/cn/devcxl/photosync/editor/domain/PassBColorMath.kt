package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * CPU reference for the full Pass B pipeline (Pass B1 linear/display tone + Pass B2 curve LUT,
 * OKLCH saturation/vibrance, low-chroma-gated 8-band HSL, edge defringe, Cartesian OKLab split
 * toning, soft gamut compression, and film fade), matching the GLSL shader stage for stage.
 */
object PassBColorMath {

    private const val CHANNELS = 3
    private const val DEG_TO_RAD = (PI / 180.0).toFloat()
    private const val MAX_HUE_SHIFT_RAD = (PI / 6.0).toFloat()
    private const val HSL_LUMA_SCALE = 0.25f

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

    /**
     * Runs Pass B1 + Pass B2 on a single linear-light sRGB sample, writing display sRGB in `[0, 1]`.
     */
    fun apply(
        toneParams: ToneShaderParams,
        colorParams: ColorGradingParams,
        curveLut: FloatArray?,
        linear: FloatArray,
        outSrgb: FloatArray,
        edgeGradient: Float = 0.0f
    ) {
        ToneShaderMath.apply(toneParams, linear, outSrgb)
        if (!colorParams.isIdentity) {
            applyDisplayColor(colorParams, curveLut, outSrgb, outSrgb, edgeGradient)
        }
    }

    /**
     * Runs only Pass B2 (display-domain color) on an sRGB `[0, 1]` sample.
     */
    fun applyDisplayColor(
        colorParams: ColorGradingParams,
        curveLut: FloatArray?,
        srgbIn: FloatArray,
        outSrgb: FloatArray,
        edgeGradient: Float = 0.0f
    ) {
        if (colorParams.isIdentity) {
            outSrgb[0] = srgbIn[0].coerceIn(0.0f, 1.0f)
            outSrgb[1] = srgbIn[1].coerceIn(0.0f, 1.0f)
            outSrgb[2] = srgbIn[2].coerceIn(0.0f, 1.0f)
            return
        }

        val workingSrgb = FloatArray(CHANNELS) { srgbIn[it].coerceIn(0.0f, 1.0f) }
        if (colorParams.hasCurve && curveLut != null) {
            CurveLutBuilder.applyCurve(curveLut, workingSrgb, workingSrgb)
        }

        if (colorParams.needsOklabStage) {
            val linear = FloatArray(CHANNELS) { ExposureShaderMath.decodeSrgb(workingSrgb[it]) }
            val lab = FloatArray(CHANNELS)
            val lch = FloatArray(CHANNELS)
            OklabColorMath.linearSrgbToOklab(linear, lab)
            OklabColorMath.oklabToOklch(lab, lch)

            applyOklchAdjustments(colorParams, lch, edgeGradient)
            OklabColorMath.oklchToOklab(lch, lab)
            applySplitToning(colorParams, lab)

            OklabColorMath.oklabToLinearSrgb(lab, linear)
            OklabColorMath.softGamutCompress(linear, linear)
            for (c in 0 until CHANNELS) {
                workingSrgb[c] = ExposureShaderMath.encodeSrgb(linear[c].coerceIn(0.0f, 1.0f))
                    .coerceIn(0.0f, 1.0f)
            }
        }

        applyFade(colorParams.fade, workingSrgb, outSrgb)
    }

    private fun applyOklchAdjustments(
        params: ColorGradingParams,
        lch: FloatArray,
        edgeGradient: Float
    ) {
        var lightness = lch[0]
        var chroma = lch[1]
        var hue = lch[2]

        chroma *= max(0.0f, 1.0f + params.saturation)

        if (abs(params.vibrance) > 1e-6f) {
            val wSat = (1.0f - chroma / 0.32f).coerceIn(0.0f, 1.0f)
            val wShadow = ToneShaderMath.smoothStep(0.05f, 0.20f, lightness)
            val gain = 1.0f + params.vibrance * wSat * wShadow * OklabColorMath.skinProtect(hue)
            chroma *= max(0.0f, gain)
        }

        if (params.hasHsl) {
            val chromaGate = ToneShaderMath.smoothStep(0.01f, 0.05f, chroma)
            var weightSum = 0.0f
            var dHue = 0.0f
            var dSat = 0.0f
            var dLuma = 0.0f
            for (i in 0 until ColorGradingParams.HSL_BAND_COUNT) {
                val diff = OklabColorMath.shortestAngleDiff(hue, HSL_BAND_CENTERS_RAD[i]) / HSL_SIGMA_RAD
                val w = exp(-0.5f * diff * diff)
                weightSum += w
                val base = i * ColorGradingParams.HSL_COMPONENTS_PER_BAND
                dHue += w * params.hslBands[base]
                dSat += w * params.hslBands[base + 1]
                dLuma += w * params.hslBands[base + 2]
            }
            val invWeight = 1.0f / max(weightSum, 1.0f)
            hue += dHue * invWeight * MAX_HUE_SHIFT_RAD * chromaGate
            chroma *= max(0.0f, 1.0f + dSat * invWeight)
            lightness = (lightness + dLuma * invWeight * HSL_LUMA_SCALE * chromaGate).coerceIn(0.0f, 1.0f)
        }

        if (params.defringe > 0.0f && edgeGradient > 0.0f) {
            val edgeWeight = ToneShaderMath.smoothStep(0.04f, 0.20f, edgeGradient)
            val chromaGate = ToneShaderMath.smoothStep(0.03f, 0.12f, chroma)
            val pDiff = OklabColorMath.shortestAngleDiff(hue, PURPLE_FRINGE_RAD) / PURPLE_FRINGE_SIGMA_RAD
            val gDiff = OklabColorMath.shortestAngleDiff(hue, GREEN_FRINGE_RAD) / GREEN_FRINGE_SIGMA_RAD
            val fringeHue = max(exp(-0.5f * pDiff * pDiff), exp(-0.5f * gDiff * gDiff))
            val amount = (params.defringe * edgeWeight * chromaGate * fringeHue).coerceIn(0.0f, 1.0f)
            chroma *= 1.0f - amount * 0.85f
        }

        lch[0] = lightness
        lch[1] = chroma
        lch[2] = hue
    }

    private fun applySplitToning(params: ColorGradingParams, lab: FloatArray) {
        if (!params.hasSplit) return
        val lightness = lab[0].coerceIn(0.0f, 1.0f)
        val shiftedL = (lightness + 0.35f * params.splitBalance * (1.0f - abs(2.0f * lightness - 1.0f)))
            .coerceIn(0.0f, 1.0f)
        val wHi = ToneShaderMath.smoothStep(0.35f, 0.85f, shiftedL)
        val wSh = 1.0f - ToneShaderMath.smoothStep(0.15f, 0.65f, shiftedL)
        lab[1] += wHi * params.splitHiAb[0] + wSh * params.splitShAb[0]
        lab[2] += wHi * params.splitHiAb[1] + wSh * params.splitShAb[1]
    }

    private fun applyFade(fade: Float, srgb: FloatArray, outSrgb: FloatArray) {
        if (fade <= 0.0f) {
            outSrgb[0] = srgb[0]
            outSrgb[1] = srgb[1]
            outSrgb[2] = srgb[2]
            return
        }
        val r = srgb[0] * (1.0f - 0.15f * fade) + 0.12f * fade
        val g = srgb[1] * (1.0f - 0.15f * fade) + 0.12f * fade
        val b = srgb[2] * (1.0f - 0.15f * fade) + 0.12f * fade
        val luma = ToneShaderMath.luminance(r, g, b)
        val desat = 0.18f * fade
        outSrgb[0] = (r + (luma - r) * desat).coerceIn(0.0f, 1.0f)
        outSrgb[1] = (g + (luma - g) * desat).coerceIn(0.0f, 1.0f)
        outSrgb[2] = (b + (luma - b) * desat).coerceIn(0.0f, 1.0f)
    }
}
