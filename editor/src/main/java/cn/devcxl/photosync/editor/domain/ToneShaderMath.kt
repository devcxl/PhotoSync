package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * CPU reference for the upper half of Pass B (linear tone + white balance + display-domain tone),
 * mirroring the GLSL in `ExposureShaderSources` instruction for instruction.
 *
 * The pipeline is:
 * 1. **Linear stage** — Bradford white balance, exposure, middle-gray brightness, then highlight /
 *    shadow / whites gains. The trims run before any `[0, 1]` clamp, so negative values really do
 *    pull `Y > 1` RAW headroom back into range.
 * 2. **Shoulder** — `softShoulder(Y)` on the linear luminance, so highlight roll-off is applied on
 *    brightness instead of clipping channels independently.
 * 3. **Display stage** — sRGB encode, zero-anchored blacks toe, piecewise contrast, then the first
 *    two Ambiance Glow terms.
 * 4. **Recombine** — the linear channels are scaled by `decode(y) / Y` to keep hue, then compressed
 *    towards the luminance when a channel exceeds 1.0 and finally sRGB-encoded.
 *
 * All sliders at zero is a strict identity: the function short-circuits to a plain sRGB encode.
 */
object ToneShaderMath {

    /** Linear luminance of middle gray (18% reflectance). */
    const val MIDDLE_GRAY = 0.18f

    /** Pass C adds `GLOW_CLARITY_COUPLING * glow` to the clarity amount. */
    const val GLOW_CLARITY_COUPLING = 0.20f

    /** Pass B2 adds `GLOW_VIBRANCE_COUPLING * glow` to the vibrance amount. */
    const val GLOW_VIBRANCE_COUPLING = 0.10f

    private const val CHANNELS = 3
    private const val LUMINANCE_R = 0.2126f
    private const val LUMINANCE_G = 0.7152f
    private const val LUMINANCE_B = 0.0722f

    private const val BRIGHTNESS_LOG_SIGMA = 1.1f
    private const val MIN_LUMINANCE = 1e-5f
    private const val DIVISION_FLOOR = 1e-6f
    private const val HALF = 0.5f

    private const val HIGHLIGHT_WEIGHT_LOW = 0.5f
    private const val HIGHLIGHT_WEIGHT_HIGH = 1.0f
    private const val SHADOW_WEIGHT_HIGH = 0.5f
    private const val WHITES_WEIGHT_LOW = 0.2f
    private const val WHITES_WEIGHT_SCALE = 0.5f

    private const val BLACK_TOE_STRENGTH = 0.18f
    private const val BLACK_CRUSH_STRENGTH = 0.04f
    private const val GLOW_MIDTONE_POWER = 0.20f
    private const val GLOW_SHADOW_LIFT = 0.10f

    private const val LN_2 = 0.6931472f

    /**
     * Renders one linear-light sRGB sample through the Pass B upper half.
     *
     * @param params Slider bundle produced by [ToneShaderParams.from].
     * @param linear Linear-light input sample (post `BaseLook`), three channels read from index 0.
     * @param out Receives three sRGB display channels in `[0, 1]`, three slots from index 0.
     */
    fun apply(params: ToneShaderParams, linear: FloatArray, out: FloatArray) {
        require(linear.size >= CHANNELS) { "A linear sample needs $CHANNELS channels" }
        require(out.size >= CHANNELS) { "The output needs $CHANNELS channels" }
        if (params.isIdentity) {
            out[0] = encodeChannel(linear[0])
            out[1] = encodeChannel(linear[1])
            out[2] = encodeChannel(linear[2])
            return
        }
        applyLinearStage(params, linear, out)
        val linearLuminance = max(luminance(out[0], out[1], out[2]), 0.0f)
        val displayLuma = displayLuminance(params, linearLuminance)
        val lumaScale = ExposureShaderMath.decodeSrgb(displayLuma) / max(linearLuminance, DIVISION_FLOOR)
        val red = out[0] * lumaScale
        val green = out[1] * lumaScale
        val blue = out[2] * lumaScale
        val maxChannel = max(red, max(green, blue))
        if (maxChannel <= 1.0f) {
            out[0] = encodeChannel(red)
            out[1] = encodeChannel(green)
            out[2] = encodeChannel(blue)
            return
        }
        // Soft gamut compression: pull the channels towards their luminance instead of clipping a
        // single channel, which would rotate the hue of a lifted saturated shadow.
        val compressedLuminance = luminance(red, green, blue)
        out[0] = encodeChannel(compressedLuminance + (red - compressedLuminance) / maxChannel)
        out[1] = encodeChannel(compressedLuminance + (green - compressedLuminance) / maxChannel)
        out[2] = encodeChannel(compressedLuminance + (blue - compressedLuminance) / maxChannel)
    }

    /**
     * Linear-stage gain the glow slider contributes to clarity, in `-1..1` clarity units.
     */
    fun glowClarityBoost(glow: Int): Float = GLOW_CLARITY_COUPLING * glow / 100.0f

    /**
     * Display-stage gain the glow slider contributes to vibrance, in `-1..1` vibrance units.
     */
    fun glowVibranceBoost(glow: Int): Float = GLOW_VIBRANCE_COUPLING * glow / 100.0f

    /** Linear luminance using the Rec.709 weights of the sRGB primaries. */
    fun luminance(red: Float, green: Float, blue: Float): Float =
        LUMINANCE_R * red + LUMINANCE_G * green + LUMINANCE_B * blue

    /**
     * White balance, exposure, middle-gray brightness and the three highlight trims, all on linear
     * light. The result is allowed to exceed 1.0 so the display stage can still roll it off.
     *
     * @param out Receives the three linear channels; it may alias [linear] because every input is
     *   read before the first write.
     */
    internal fun applyLinearStage(params: ToneShaderParams, linear: FloatArray, out: FloatArray) {
        var red = linear[0]
        var green = linear[1]
        var blue = linear[2]
        if (params.hasWhiteBalance) {
            val matrix = params.wbMatrix
            val sourceRed = red
            val sourceGreen = green
            val sourceBlue = blue
            red = matrix[0] * sourceRed + matrix[3] * sourceGreen + matrix[6] * sourceBlue
            green = matrix[1] * sourceRed + matrix[4] * sourceGreen + matrix[7] * sourceBlue
            blue = matrix[2] * sourceRed + matrix[5] * sourceGreen + matrix[8] * sourceBlue
        }
        val exposureScale = exp2(params.exposureEv)
        red = max(red, 0.0f) * exposureScale
        green = max(green, 0.0f) * exposureScale
        blue = max(blue, 0.0f) * exposureScale

        val brightnessScale = exp2(params.brightness * brightnessWeight(luminance(red, green, blue)))
        red *= brightnessScale
        green *= brightnessScale
        blue *= brightnessScale

        val encodedLuminance = ExposureShaderMath.encodeSrgb(
            luminance(red, green, blue).coerceIn(0.0f, 1.0f)
        )
        val highlightWeight = smoothStep(
            HIGHLIGHT_WEIGHT_LOW,
            HIGHLIGHT_WEIGHT_HIGH,
            encodedLuminance
        )
        val shadowWeight = 1.0f - smoothStep(0.0f, SHADOW_WEIGHT_HIGH, encodedLuminance)
        val whitesWeight = smoothStep(WHITES_WEIGHT_LOW, HIGHLIGHT_WEIGHT_HIGH, encodedLuminance)
        val trimScale = exp2(
            params.highlights * highlightWeight +
                params.shadows * shadowWeight +
                WHITES_WEIGHT_SCALE * params.whites * whitesWeight
        )
        out[0] = red * trimScale
        out[1] = green * trimScale
        out[2] = blue * trimScale
    }

    /**
     * Maps a linear luminance onto the final display luminance: shoulder, sRGB encode, blacks toe,
     * piecewise contrast and the first two glow terms.
     */
    internal fun displayLuminance(params: ToneShaderParams, linearLuminance: Float): Float {
        val shoulder = ExposureShaderMath.softShoulder(linearLuminance)
        var y = ExposureShaderMath.encodeSrgb(shoulder)
        y += BLACK_TOE_STRENGTH * params.blacks * y * (1.0f - y) * (1.0f - y)
        if (params.blacks < 0.0f) {
            val crush = BLACK_CRUSH_STRENGTH * params.blacks
            y = max(0.0f, (y + crush) / (1.0f + crush))
        }
        y = y.coerceIn(0.0f, 1.0f)
        y = if (params.contrast >= 0.0f) {
            mix(y, smoothStep(0.0f, 1.0f, y), params.contrast)
        } else {
            mix(y, HALF + HALF * (y - HALF), -params.contrast)
        }
        val base = y.coerceIn(0.0f, 1.0f)
        val midtone = base.pow(1.0f / (1.0f + GLOW_MIDTONE_POWER * params.glow))
        val shadowLift = GLOW_SHADOW_LIFT * params.glow * base * (1.0f - base) * (1.0f - base)
        return (midtone + shadowLift).coerceIn(0.0f, 1.0f)
    }

    /** Log-normal weight that confines brightness to the midtones around [MIDDLE_GRAY]. */
    internal fun brightnessWeight(luminance: Float): Float {
        val ratio = log2(max(luminance, MIN_LUMINANCE) / MIDDLE_GRAY) / BRIGHTNESS_LOG_SIGMA
        return exp(-HALF * ratio * ratio)
    }

    /** GLSL `smoothstep`, clamped at both edges. */
    internal fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val span = edge1 - edge0
        if (abs(span) < DIVISION_FLOOR) return if (value < edge0) 0.0f else 1.0f
        val t = ((value - edge0) / span).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }

    private fun encodeChannel(value: Float): Float =
        ExposureShaderMath.encodeSrgb(value.coerceAtLeast(0.0f)).coerceIn(0.0f, 1.0f)

    private fun mix(from: Float, to: Float, weight: Float): Float = from + (to - from) * weight

    private fun exp2(exponent: Float): Float = exp(exponent * LN_2)
}
