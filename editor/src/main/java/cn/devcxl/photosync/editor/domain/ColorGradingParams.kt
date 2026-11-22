package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable uniform snapshot for the Pass B lower half (display-domain color pipeline): curve LUT
 * flag, saturation, vibrance (including Ambiance Glow vibrance boost), 8-band OKLCH HSL, Cartesian
 * OKLab split toning, longitudinal CA defringe and film fade.
 *
 * All arrays are encapsulated defensively so callers cannot mutate shared defaults across instances.
 */
data class ColorGradingParams(
    /** Normalized saturation trim in `-1..1`. */
    val saturation: Float = 0.0f,
    /** Normalized vibrance trim (including glow coupling) in `-1.1..1.1`. */
    val vibrance: Float = 0.0f,
    /** Normalized film fade amount in `0..1`. */
    val fade: Float = 0.0f,
    /** Normalized edge-defringe strength in `0..1`. */
    val defringe: Float = 0.0f,
    private val packedHslBands: FloatArray = FloatArray(HSL_PACKED_SIZE),
    /** Highlight split-toning OKLab `a` component. */
    val splitHiA: Float = 0.0f,
    /** Highlight split-toning OKLab `b` component. */
    val splitHiB: Float = 0.0f,
    /** Shadow split-toning OKLab `a` component. */
    val splitShA: Float = 0.0f,
    /** Shadow split-toning OKLab `b` component. */
    val splitShB: Float = 0.0f,
    /** Highlight/shadow tonal balance in `-1..1`. */
    val splitBalance: Float = 0.0f,
    /** True when any of the 4 tone-curve channels differs from identity. */
    val hasCurve: Boolean = false,
    /** True when any of the 8 HSL bands has a non-zero shift. */
    val hasHsl: Boolean = false,
    /** True when highlight or shadow split-toning saturation is non-zero. */
    val hasSplit: Boolean = false
) {

    /** True when the OKLab/OKLCH grading stage must run. */
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

    /** Returns a fresh defensive copy of the 24-float (`8 x vec3`) HSL band shifts. */
    fun hslBandsCopy(): FloatArray = packedHslBands.copyOf()

    /** Reads packed HSL band component at `[bandIndex * 3 + componentIndex]`. */
    internal fun hslComponent(bandIndex: Int, componentIndex: Int): Float =
        packedHslBands[bandIndex * HSL_COMPONENTS_PER_BAND + componentIndex]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorGradingParams) return false
        return saturation == other.saturation &&
            vibrance == other.vibrance &&
            fade == other.fade &&
            defringe == other.defringe &&
            packedHslBands.contentEquals(other.packedHslBands) &&
            splitHiA == other.splitHiA &&
            splitHiB == other.splitHiB &&
            splitShA == other.splitShA &&
            splitShB == other.splitShB &&
            splitBalance == other.splitBalance &&
            hasCurve == other.hasCurve &&
            hasHsl == other.hasHsl &&
            hasSplit == other.hasSplit
    }

    override fun hashCode(): Int {
        var result = saturation.hashCode()
        result = 31 * result + vibrance.hashCode()
        result = 31 * result + fade.hashCode()
        result = 31 * result + defringe.hashCode()
        result = 31 * result + packedHslBands.contentHashCode()
        result = 31 * result + splitHiA.hashCode()
        result = 31 * result + splitHiB.hashCode()
        result = 31 * result + splitShA.hashCode()
        result = 31 * result + splitShB.hashCode()
        result = 31 * result + splitBalance.hashCode()
        result = 31 * result + hasCurve.hashCode()
        result = 31 * result + hasHsl.hashCode()
        result = 31 * result + hasSplit.hashCode()
        return result
    }

    companion object {
        /** Number of OKLCH hue bands in [HslOps]. */
        const val HSL_BAND_COUNT = 8

        /** Components per band (`hue`, `saturation`, `luminance`). */
        const val HSL_COMPONENTS_PER_BAND = 3

        /** Total floats in the packed HSL uniform array (`8 * 3`). */
        const val HSL_PACKED_SIZE = HSL_BAND_COUNT * HSL_COMPONENTS_PER_BAND

        /** Maximum OKLab `(a, b)` chroma amplitude added at 100% split-toning saturation. */
        const val MAX_SPLIT_CHROMA = 0.12f

        private const val NEUTRAL_EPSILON = 1e-6f
        private const val SLIDER_SCALE = 100.0f
        private const val DEG_TO_RAD = (PI / 180.0).toFloat()

        /** Builds [ColorGradingParams] from [graph], including the Ambiance Glow vibrance coupling. */
        fun from(graph: EditGraph): ColorGradingParams {
            val c = graph.color
            val vibranceWithGlow = c.vibrance / SLIDER_SCALE + ToneShaderMath.glowVibranceBoost(graph.tone.glow)
            val hasHsl = !c.hsl.isIdentity()
            val hslBands = if (hasHsl) packHslBands(c.hsl) else FloatArray(HSL_PACKED_SIZE)
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
                packedHslBands = hslBands,
                splitHiA = if (hasSplit) cos(hiRad) * hiMag else 0.0f,
                splitHiB = if (hasSplit) sin(hiRad) * hiMag else 0.0f,
                splitShA = if (hasSplit) cos(shRad) * shMag else 0.0f,
                splitShB = if (hasSplit) sin(shRad) * shMag else 0.0f,
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
            val packed = FloatArray(HSL_PACKED_SIZE)
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
