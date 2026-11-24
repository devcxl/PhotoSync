package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CIE L*a*b* color sample under the D65 reference white used by sRGB.
 */
data class LabColor(
    val lightness: Double,
    val a: Double,
    val b: Double
)

/**
 * CIE Delta E 2000 color difference in pure Kotlin, used to verify that the GPU `camera_default`
 * base look reproduces the browse-page RAW decode without a visible tone shift.
 *
 * Reference: Sharma, Wu & Dalal (2005), *The CIEDE2000 Color-Difference Formula*.
 */
object ColorDeltaE2000 {

    private const val WHITE_X = 0.95047
    private const val WHITE_Y = 1.0
    private const val WHITE_Z = 1.08883
    private const val EPSILON = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0
    private const val POW_25_TO_7 = 6103515625.0 // 25^7, the CIEDE2000 chroma pivot

    /**
     * Converts a normalized sRGB triplet in `[0, 1]` into a [LabColor] sample.
     */
    fun fromSrgb(red: Float, green: Float, blue: Float): LabColor {
        val r = linearize(red)
        val g = linearize(green)
        val b = linearize(blue)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_X
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / WHITE_Y
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_Z
        val fx = pivot(x)
        val fy = pivot(y)
        val fz = pivot(z)
        return LabColor(
            lightness = 116.0 * fy - 16.0,
            a = 500.0 * (fx - fy),
            b = 200.0 * (fy - fz)
        )
    }

    /**
     * Converts 8-bit sRGB channel samples in `[0, 255]` into a [LabColor] sample.
     */
    fun fromSrgb8(red: Int, green: Int, blue: Int): LabColor =
        fromSrgb(red / 255.0f, green / 255.0f, blue / 255.0f)

    /**
     * Returns the CIEDE2000 difference between [first] and [second].
     */
    fun deltaE2000(first: LabColor, second: LabColor): Double {
        val c1 = hypot(first.a, first.b)
        val c2 = hypot(second.a, second.b)
        val cBar = (c1 + c2) / 2.0
        val g = 0.5 * (1.0 - sqrt(cBar.pow(7) / (cBar.pow(7) + POW_25_TO_7)))
        val a1Prime = (1.0 + g) * first.a
        val a2Prime = (1.0 + g) * second.a
        val c1Prime = hypot(a1Prime, first.b)
        val c2Prime = hypot(a2Prime, second.b)
        val h1Prime = hueDegrees(first.b, a1Prime)
        val h2Prime = hueDegrees(second.b, a2Prime)
        val deltaL = second.lightness - first.lightness
        val deltaC = c2Prime - c1Prime
        val deltaHue = deltaHueDegrees(h1Prime, h2Prime, c1Prime, c2Prime)
        val deltaH = 2.0 * sqrt(c1Prime * c2Prime) * sin(Math.toRadians(deltaHue / 2.0))
        val lBar = (first.lightness + second.lightness) / 2.0
        val cBarPrime = (c1Prime + c2Prime) / 2.0
        val hBarPrime = meanHueDegrees(h1Prime, h2Prime, c1Prime, c2Prime)

        val weighting = weightingFactors(lBar, cBarPrime, hBarPrime)
        val lightnessTerm = deltaL / weighting[0]
        val chromaTerm = deltaC / weighting[1]
        val hueTerm = deltaH / weighting[2]
        val rotation = rotationTerm(cBarPrime, hBarPrime)
        return sqrt(
            lightnessTerm * lightnessTerm +
                chromaTerm * chromaTerm +
                hueTerm * hueTerm +
                rotation * chromaTerm * hueTerm
        )
    }

    private fun linearize(channel: Float): Double {
        val value = channel.toDouble().coerceIn(0.0, 1.0)
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private fun pivot(value: Double): Double =
        if (value > EPSILON) value.pow(1.0 / 3.0) else (KAPPA * value + 16.0) / 116.0

    private fun hueDegrees(b: Double, a: Double): Double {
        if (abs(a) < 1e-12 && abs(b) < 1e-12) return 0.0
        val degrees = Math.toDegrees(atan2(b, a))
        return if (degrees < 0.0) degrees + 360.0 else degrees
    }

    private fun deltaHueDegrees(h1: Double, h2: Double, c1: Double, c2: Double): Double {
        if (c1 * c2 == 0.0) return 0.0
        val difference = h2 - h1
        return when {
            abs(difference) <= 180.0 -> difference
            difference > 180.0 -> difference - 360.0
            else -> difference + 360.0
        }
    }

    private fun meanHueDegrees(h1: Double, h2: Double, c1: Double, c2: Double): Double {
        if (c1 * c2 == 0.0) return h1 + h2
        val sum = h1 + h2
        return when {
            abs(h1 - h2) <= 180.0 -> sum / 2.0
            sum < 360.0 -> (sum + 360.0) / 2.0
            else -> (sum - 360.0) / 2.0
        }
    }

    private fun weightingFactors(lBar: Double, cBarPrime: Double, hBarPrime: Double): DoubleArray {
        val t = 1.0 -
            0.17 * cos(Math.toRadians(hBarPrime - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hBarPrime)) +
            0.32 * cos(Math.toRadians(3.0 * hBarPrime + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hBarPrime - 63.0))
        val lOffset = lBar - 50.0
        val sL = 1.0 + (0.015 * lOffset * lOffset) / sqrt(20.0 + lOffset * lOffset)
        val sC = 1.0 + 0.045 * cBarPrime
        val sH = 1.0 + 0.015 * cBarPrime * t
        return doubleArrayOf(sL, sC, sH)
    }

    private fun rotationTerm(cBarPrime: Double, hBarPrime: Double): Double {
        val deltaTheta = 30.0 * kotlin.math.exp(-((hBarPrime - 275.0) / 25.0).pow(2))
        val rC = 2.0 * sqrt(cBarPrime.pow(7) / (cBarPrime.pow(7) + POW_25_TO_7))
        return -sin(Math.toRadians(2.0 * deltaTheta)) * rC
    }
}
