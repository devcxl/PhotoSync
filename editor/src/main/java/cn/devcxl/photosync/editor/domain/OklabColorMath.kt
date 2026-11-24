package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin perceptual OKLab / OKLCH transforms (Björn Ottosson, 2020) and linear soft gamut
 * compression shared as the authoritative reference for Pass B2 GLSL shaders.
 */
object OklabColorMath {

    private const val CHANNELS = 3
    private const val CHROMA_EPSILON = 1e-6f
    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val PI_FLOAT = PI.toFloat()

    /** Center of the 45° OKLCH skin-tone protection band, in radians. */
    private const val SKIN_HUE_RAD = (45.0 * PI / 180.0).toFloat()
    private const val SKIN_SIGMA_RAD = (25.0 * PI / 180.0).toFloat()
    private const val SKIN_PROTECTION_DEPTH = 0.70f

    /**
     * Compresses out-of-range (`maxCh > 1.0`) linear RGB towards its luminance axis so lifted
     * saturated pixels desaturate gracefully instead of hard-clipping and shifting hue.
     */
    fun softGamutCompress(linearRgb: FloatArray, out: FloatArray) {
        val r = linearRgb[0]
        val g = linearRgb[1]
        val b = linearRgb[2]
        val maxCh = max(r, max(g, b))
        if (maxCh <= 1.0f) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        val luma = ToneShaderMath.luminance(r, g, b)
        out[0] = luma + (r - luma) / maxCh
        out[1] = luma + (g - luma) / maxCh
        out[2] = luma + (b - luma) / maxCh
    }

    /** Converts linear sRGB `(r, g, b)` to OKLab `(L, a, b)`. */
    fun linearSrgbToOklab(rgb: FloatArray, outLab: FloatArray) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val lRoot = signedCbrt(l)
        val mRoot = signedCbrt(m)
        val sRoot = signedCbrt(s)

        outLab[0] = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720468f * sRoot
        outLab[1] = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot
        outLab[2] = 0.0259040371f * lRoot + 0.7827717662f * mRoot - 0.8086757660f * sRoot
    }

    /** Converts OKLab `(L, a, b)` to linear sRGB `(r, g, b)`. */
    fun oklabToLinearSrgb(lab: FloatArray, outRgb: FloatArray) {
        val lVal = lab[0]
        val aVal = lab[1]
        val bVal = lab[2]

        val lRoot = lVal + 0.3963377774f * aVal + 0.2158037573f * bVal
        val mRoot = lVal - 0.1055613458f * aVal - 0.0638541728f * bVal
        val sRoot = lVal - 0.0894841775f * aVal - 1.2914855480f * bVal

        val l = lRoot * lRoot * lRoot
        val m = mRoot * mRoot * mRoot
        val s = sRoot * sRoot * sRoot

        outRgb[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        outRgb[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        outRgb[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    /** Converts OKLab `(L, a, b)` to OKLCH `(L, C, h)` with `h` in `[-pi, pi]`. */
    fun oklabToOklch(lab: FloatArray, outLch: FloatArray) {
        val chroma = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        val hue = if (chroma > CHROMA_EPSILON) atan2(lab[2], lab[1]) else 0.0f
        outLch[0] = lab[0]
        outLch[1] = chroma
        outLch[2] = hue
    }

    /** Converts OKLCH `(L, C, h)` to OKLab `(L, a, b)`. */
    fun oklchToOklab(lch: FloatArray, outLab: FloatArray) {
        outLab[0] = lch[0]
        outLab[1] = lch[1] * cos(lch[2])
        outLab[2] = lch[1] * sin(lch[2])
    }

    /** Wraps an angular difference in radians onto `[-pi, pi]`. */
    fun shortestAngleDiff(hueA: Float, hueB: Float): Float {
        var diff = (hueA - hueB) % TWO_PI
        if (diff > PI_FLOAT) diff -= TWO_PI
        if (diff < -PI_FLOAT) diff += TWO_PI
        return diff
    }

    /**
     * Skin-tone protection weight for Vibrance centered at 45° OKLCH (`0.30` at 45°, `1.0` away
     * from skin), matching `colorDisplay` GLSL.
     */
    fun skinProtect(hueRad: Float): Float {
        val diff = shortestAngleDiff(hueRad, SKIN_HUE_RAD) / SKIN_SIGMA_RAD
        return 1.0f - SKIN_PROTECTION_DEPTH * exp(-0.5f * diff * diff)
    }

    private fun signedCbrt(value: Float): Float {
        val mag = abs(value).pow(1.0f / 3.0f)
        return if (value < 0.0f) -mag else mag
    }
}
