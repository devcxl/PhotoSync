package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [OklabColorMath] — linear sRGB <-> OKLab/OKLCH round-trip precision and
 * out-of-gamut soft compression.
 */
class OklabColorMathTest {

    @Test
    fun shouldRoundTripLinearSrgbThroughOklabAndOklch_whenColorsAreInsideGamut() {
        val rng = Random(4242)
        val samples = listOf(
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(1.0f, 1.0f, 1.0f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(1.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 1.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 1.0f)
        ) + List(100) {
            floatArrayOf(rng.nextFloat(), rng.nextFloat(), rng.nextFloat())
        }

        val lab = FloatArray(3)
        val lch = FloatArray(3)
        val labBack = FloatArray(3)
        val rgbBack = FloatArray(3)
        for (rgb in samples) {
            OklabColorMath.linearSrgbToOklab(rgb, lab)
            OklabColorMath.oklabToOklch(lab, lch)
            OklabColorMath.oklchToOklab(lch, labBack)
            OklabColorMath.oklabToLinearSrgb(labBack, rgbBack)

            for (c in 0..2) {
                assertEquals(
                    "round-trip channel $c for ${rgb.toList()}",
                    rgb[c],
                    rgbBack[c],
                    1e-5f
                )
            }
        }
    }

    @Test
    fun shouldPullOverRangeChannelsTowardLuminance_whenMaxChannelExceedsOne() {
        val input = floatArrayOf(0.02f, 0.05f, 1.4f)
        val out = FloatArray(3)
        OklabColorMath.softGamutCompress(input, out)

        val luma = ToneShaderMath.luminance(input[0], input[1], input[2])
        assertTrue(out[0] > input[0])
        assertTrue(out[1] > input[1])
        assertTrue(out[2] < input[2])
        assertTrue(out[2] > out[1] && out[1] > out[0])
        assertEquals(luma + (1.4f - luma) / 1.4f, out[2], 1e-6f)
        assertTrue(abs(out[0] - (luma + (0.02f - luma) / 1.4f)) < 1e-6f)
    }
}
