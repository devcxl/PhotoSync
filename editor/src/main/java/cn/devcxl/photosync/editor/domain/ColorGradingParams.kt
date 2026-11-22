package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable uniform bundle for Pass B lower half (display-domain color pipeline): curve LUT flag,
 * saturation, vibrance (including Ambiance Glow vibrance boost), 8-band OKLCH HSL, Cartesian
 * OKLab split toning, longitudinal CA defringe and film fade.
 */
class ColorGradingParams(
    val saturation: Float = 0.0f,
    val vibrance: Float = 0.0f,
    val fade: Float = 0.0f,
    val defringe: Float = 0.0f,
    val hslBands: FloatArray = ZERO_HSL_BANDS,
    val splitHiAb: FloatArray = ZERO_AB,
    val splitShAb: FloatArray = ZERO_AB,
    val splitBalance: Float = 0.0f,
    val hasCurve: Boolean = false,
    val hasHsl: Boolean = false,
    val hasSplit: Boolean = false
) {

    /** True when the OKLab/OKLCH stage must run. */
    val needsOklabStage: Boolean =
        abs(saturation) >= NEUTRAL_EPSILON ||
            abs(vibrance) >= NEUTRAL_EPSILON ||
            abs(defringe) >= NEUTRAL_EPSILON ||
            hasHsl ||
            hasSplit

    /** True when Pass B2 leaves every pixel completely untouched. */
    val isIdentity: Boolean =
        !hasCurve &&
            !needsOklabStage &&
            abs(fade) < NEUTRAL_EPSILON

    companion object {
        const val HSL_BAND_COUNT = 8
        const val HSL_COMPONENTS_PER_BAND = 3
        const val MAX_SPLIT_CHROMA = 0.12f
        private const val NEUTRAL_EPSILON = 1e-6f
        private const val SLIDER_SCALE = 100.0f
        private const val DEG_TO_RAD = (PI / 180.0).toFloat()

        val ZERO_HSL_BANDS = FloatArray(HSL_BAND_COUNT * HSL_COMPONENTS_PER_BAND)
        val ZERO_AB = floatArrayOf(0.0f, 0.0f)

        /**
         * Builds [ColorGradingParams] from [graph], including the Ambiance Glow vibrance coupling.
         */
        fun from(graph: EditGraph): ColorGradingParams {
            val c = graph.color
            val vibranceWithGlow = c.vibrance / SLIDER_SCALE + ToneShaderMath.glowVibranceBoost(graph.tone.glow)
            val hasHsl = !c.hsl.isIdentity()
            val hslBands = if (hasHsl) packHslBands(c.hsl) else ZERO_HSL_BANDS
            val hasSplit = !c.split.isIdentity()
            val hiMag = (c.split.hiSat / SLIDER_SCALE) * MAX_SPLIT_CHROMA
            val shMag = (c.split.shSat / SLIDER_SCALE) * MAX_SPLIT_CHROMA
            val hiRad = c.split.hiHue * DEG_TO_RAD
            val shRad = c.split.shHue * DEG_TO_RAD

            return ColorGradingParams(
                saturation = c.saturation / SLIDER_SCALE,
                vibrance = vibranceWithGlow,
                fade = c.fade / SLIDER_SCALE,
                defringe = graph.effects.ca.defringe / SLIDER_SCALE,
                hslBands = hslBands,
                splitHiAb = if (hasSplit) floatArrayOf(cos(hiRad) * hiMag, sin(hiRad) * hiMag) else ZERO_AB,
                splitShAb = if (hasSplit) floatArrayOf(cos(shRad) * shMag, sin(shRad) * shMag) else ZERO_AB,
                splitBalance = c.split.balance / SLIDER_SCALE,
                hasCurve = !c.curve.isIdentity(),
                hasHsl = hasHsl,
                hasSplit = hasSplit
            )
        }

        private fun packHslBands(hsl: HslOps): FloatArray {
            val bands = listOf(
                hsl.red, hsl.orange, hsl.yellow, hsl.green,
                hsl.aqua, hsl.blue, hsl.purple, hsl.magenta
            )
            val packed = FloatArray(HSL_BAND_COUNT * HSL_COMPONENTS_PER_BAND)
            bands.forEachIndexed { idx, band ->
                val base = idx * HSL_COMPONENTS_PER_BAND
                packed[base] = band.hue / SLIDER_SCALE
                packed[base + 1] = band.saturation / SLIDER_SCALE
                packed[base + 2] = band.luminance / SLIDER_SCALE
            }
            return packed
        }
    }
}
