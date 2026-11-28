package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.max

import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SubjectImage

/**
 * The single Pre-pass colour delta shared by every preview and bake band of one `SkyOps`.
 *
 * `L/a/b` are OKLab deltas of `new sky mean - original sky mean`. Computing them once on the `1024px`
 * proxy is what removes the column-band colour seam: every band applies exactly the same three
 * numbers instead of deriving its own local statistics.
 *
 * @property deltaL OKLab lightness delta, unsigned; Pass B clamps it to ±[MAX_LUMA_DELTA].
 * @property deltaA OKLab green/red delta.
 * @property deltaB OKLab blue/yellow delta.
 */
data class SkyRelightStats(
    val deltaL: Float = 0.0f,
    val deltaA: Float = 0.0f,
    val deltaB: Float = 0.0f
) {

    /** True when the delta is numerically neutral and can skip the Pass B relight branch. */
    fun isIdentity(): Boolean =
        abs(deltaL) < EPSILON && abs(deltaA) < EPSILON && abs(deltaB) < EPSILON

    companion object {
        /** A neutral delta; the original sky already matches the replacement. */
        val NEUTRAL = SkyRelightStats()

        private const val EPSILON = 1e-5f
    }
}

/**
 * CPU reference of the Pass B foreground relight of a `SkyOps`.
 *
 * The shader (`ExposureShaderSources.SKY_RELIGHT_GLSL`) evaluates the same formula per pixel:
 * `weight = relight × (1 - skyMask) × smoothstep(0.08, 0.75, L_fg)`, a chroma migration derived from
 * `(Δa, Δb)` and a limited lightness gain derived from `clamp(ΔL, -0.12, +0.12)`. Because the weight
 * is zero on the new sky itself and on deep shadows, the ground never turns blue after a sunset swap
 * and a dark foreground never crushes to black.
 */
object SkyRelightMath {

    /** Largest lightness delta Pass B may apply at full strength. */
    const val MAX_LUMA_DELTA = 0.12f

    private const val SLIDER_SCALE = 100.0f
    private const val MASK_THRESHOLD = 0.5f
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
    private const val EIGHT_BIT = 255.0f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    /**
     * Computes the OKLab delta between the original and the replacement sky.
     *
     * @param originalSkyMeanLinear Scene-linear mean RGB of the pixels under the sky mask.
     * @param newSkyMeanLinear Scene-linear mean RGB of the replacement sky asset.
     */
    fun compute(originalSkyMeanLinear: FloatArray, newSkyMeanLinear: FloatArray): SkyRelightStats {
        val original = FloatArray(3)
        val replacement = FloatArray(3)
        OklabColorMath.linearSrgbToOklab(originalSkyMeanLinear, original)
        OklabColorMath.linearSrgbToOklab(newSkyMeanLinear, replacement)
        return SkyRelightStats(
            deltaL = replacement[0] - original[0],
            deltaA = replacement[1] - original[1],
            deltaB = replacement[2] - original[2]
        )
    }

    /**
     * Scene-linear mean of the original pixels whose mask probability is at least `0.5`.
     *
     * @return The mean, or `null` when the mask selects no pixel.
     */
    fun originalSkyMean(image: SubjectImage, mask: SkyMask): FloatArray? {
        val sum = FloatArray(3)
        var count = 0
        for (row in 0 until image.height) {
            val v = (row + 0.5f) / image.height
            for (column in 0 until image.width) {
                val u = (column + 0.5f) / image.width
                if (mask.sample(u, v) < MASK_THRESHOLD) continue
                accumulateLinear(image.argb[row * image.width + column], sum)
                count++
            }
        }
        if (count == 0) return null
        sum[0] /= count
        sum[1] /= count
        sum[2] /= count
        return sum
    }

    /**
     * Scene-linear mean of an entire decoded image.
     *
     * Used for the replacement asset, whose mean must not depend on the frame's mask: a sky pack
     * image contributes the same hue whether it fills 10 % or 80 % of the frame.
     */
    fun imageMean(image: SubjectImage): FloatArray {
        val sum = FloatArray(3)
        for (pixel in image.argb) accumulateLinear(pixel, sum)
        val count = max(image.argb.size, 1)
        return floatArrayOf(sum[0] / count, sum[1] / count, sum[2] / count)
    }

    /**
     * Applies the relight to one scene-linear sample.
     *
     * @param relight Slider value `0..100`.
     * @param skyCoverage Replacement-sky probability of this pixel in `0..1`.
     */
    fun apply(
        stats: SkyRelightStats,
        relight: Int,
        skyCoverage: Float,
        linear: FloatArray,
        cachedDeltaRgb: FloatArray? = null
    ) {
        if (relight <= 0 || stats.isIdentity()) return
        val weight = weightOf(relight, skyCoverage, luminance(linear))
        if (weight <= 0.0f) return
        val delta = cachedDeltaRgb ?: deltaRgb(stats.deltaA, stats.deltaB)
        linear[0] = max(0.0f, linear[0] + delta[0] * weight)
        linear[1] = max(0.0f, linear[1] + delta[1] * weight)
        linear[2] = max(0.0f, linear[2] + delta[2] * weight)
        val gain = 1.0f + stats.deltaL.coerceIn(-MAX_LUMA_DELTA, MAX_LUMA_DELTA) * weight
        linear[0] = max(0.0f, linear[0] * gain)
        linear[1] = max(0.0f, linear[1] * gain)
        linear[2] = max(0.0f, linear[2] * gain)
    }

    /**
     * Per-pixel relight weight, shared with the shader and with tests.
     *
     * @param luminance Scene-linear Rec.709 luminance of the un-relit pixel.
     */
    fun weightOf(relight: Int, skyCoverage: Float, luminance: Float): Float {
        val strength = (relight.coerceIn(0, 100) / SLIDER_SCALE)
        if (strength <= 0.0f) return 0.0f
        val foreground = 1.0f - skyCoverage.coerceIn(0.0f, 1.0f)
        val lit = ToneShaderMath.smoothStep(0.08f, 0.75f, luminance.coerceIn(0.0f, 1.0f))
        return foreground * lit * strength
    }

    /**
     * Linear-RGB offset of a pure OKLab chroma shift `(deltaA, deltaB)`.
     *
     * The offset is the inverse transform of `(0, Δa, Δb)`; applying it additively keeps the shader
     * branch to three multiplies and makes the CPU reference exactly reproduce it.
     */
    fun deltaRgb(deltaA: Float, deltaB: Float): FloatArray {
        val out = FloatArray(3)
        OklabColorMath.oklabToLinearSrgb(floatArrayOf(0.0f, deltaA, deltaB), out)
        return out
    }

    /** Rec.709 luminance of a linear sample. */
    fun luminance(linear: FloatArray): Float =
        LUMA_R * linear[0] + LUMA_G * linear[1] + LUMA_B * linear[2]

    private fun accumulateLinear(packed: Int, sum: FloatArray) {
        sum[0] += ExposureShaderMath.decodeSrgb(((packed ushr RED_SHIFT) and 0xFF) / EIGHT_BIT)
        sum[1] += ExposureShaderMath.decodeSrgb(((packed ushr GREEN_SHIFT) and 0xFF) / EIGHT_BIT)
        sum[2] += ExposureShaderMath.decodeSrgb((packed and 0xFF) / EIGHT_BIT)
    }
}
