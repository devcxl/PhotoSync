package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ExposureShaderMath.encodeSrgb

/**
 * Seam under test: [ToneShaderMath] display stage — sRGB encode, zero-anchored blacks toe,
 * piecewise contrast, the first two Ambiance Glow terms and the gamut compression that keeps
 * saturated dark pixels from hue-shifting.
 *
 * Expected values are hand-evaluated from the Pass B specification (see the ticket and
 * `docs/research/2026-11-21-image-editor-pipeline.md`, Appendix B), not recomputed from the
 * implementation.
 */
class ToneShaderMathDisplayStageTest {

    @Test
    fun shouldAnchorPureBlackAtZero_whenBlacksArePositive() {
        assertEquals(0.0f, renderGrey(toneParams(blacks = 100), 0.0f), 1e-6f)
    }

    @Test
    fun shouldLiftTheShadowToeOnly_whenBlacksArePositive() {
        val params = toneParams(blacks = 100)
        val shadow = renderGrey(params, 0.02f)
        val midtone = renderGrey(params, 0.5f)
        val highlight = renderGrey(params, 0.9f)

        assertEquals(0.1714f, shadow, 2.0f / 255.0f)
        assertEquals(0.7446f, midtone, 2.0f / 255.0f)
        assertEquals(0.9542f, highlight, 2.0f / 255.0f)
        val shadowLift = shadow - encodeSrgb(0.02f)
        val highlightLift = highlight - encodeSrgb(0.9f)
        assertTrue("the toe lift must taper off towards white", shadowLift > highlightLift)
    }

    @Test
    fun shouldCrushTheShadowToe_whenBlacksAreNegative() {
        val params = toneParams(blacks = -100)

        assertEquals(0.0f, renderGrey(params, 0.0f), 1e-6f)
        assertEquals(0.0472f, renderGrey(params, 0.01f), 2.0f / 255.0f)
        assertTrue(renderGrey(params, 0.18f) < encodeSrgb(0.18f))
    }

    @Test
    fun shouldExpandTonalRangeAroundMidGrey_whenContrastIsPositive() {
        val params = toneParams(contrast = 100)

        assertEquals(0.5555f, renderGrey(params, 0.25f), 2.0f / 255.0f)
        assertEquals(0.9608f, renderGrey(params, 0.75f), 2.0f / 255.0f)
        assertEquals(0.4421f, renderGrey(params, 0.18f), 2.0f / 255.0f)
    }

    @Test
    fun shouldCompressTowardsMidGrey_whenContrastIsNegative() {
        val params = toneParams(contrast = -100)

        assertEquals(0.5185f, renderGrey(params, 0.25f), 2.0f / 255.0f)
        assertEquals(0.6904f, renderGrey(params, 0.75f), 2.0f / 255.0f)
    }

    @Test
    fun shouldKeepNegativeContrastFromFlippingEndpointSlopes() {
        val neutral = toneParams()
        val flattened = toneParams(contrast = -100)
        val lowStep = renderGrey(flattened, 0.2f) - renderGrey(flattened, 0.1f)
        val highStep = renderGrey(flattened, 0.9f) - renderGrey(flattened, 0.8f)
        val neutralLowStep = renderGrey(neutral, 0.2f) - renderGrey(neutral, 0.1f)

        assertTrue(
            "negative contrast must halve, not invert, the dark slope",
            lowStep in (0.3f * neutralLowStep)..(0.7f * neutralLowStep)
        )
        assertTrue("negative contrast must keep the slope monotonic", highStep > 0.0f)
    }

    @Test
    fun shouldLiftMidtonesAndAnchorBlack_whenGlowIsPositive() {
        val params = toneParams(glow = 100)

        assertEquals(0.0f, renderGrey(params, 0.0f), 1e-6f)
        assertEquals(0.5382f, renderGrey(params, 0.18f), 2.0f / 255.0f)
        assertEquals(0.7792f, renderGrey(params, 0.5f), 2.0f / 255.0f)
        val midtoneLift = renderGrey(params, 0.5f) - encodeSrgb(0.5f)
        val highlightLift = renderGrey(params, 1.0f) - encodeSrgb(1.0f)
        assertTrue("glow must lift midtones more than highlights", midtoneLift > highlightLift)
    }

    @Test
    fun shouldDarkenMidtones_whenGlowIsNegative() {
        val params = toneParams(glow = -100)

        assertTrue(renderGrey(params, 0.5f) < encodeSrgb(0.5f))
        assertEquals(0.0f, renderGrey(params, 0.0f), 1e-6f)
    }

    @Test
    fun shouldDesaturateTowardsLuminanceInsteadOfHardClipping_whenAChannelExceedsOne() {
        // One stop on a saturated shadow doubles (0.01, 0.025, 0.7) into (0.02, 0.05, 1.4).
        val rendered = render(toneParams(exposure = 1.0f), floatArrayOf(0.01f, 0.025f, 0.7f))

        // Linear luminance is 0.1411, so the compressed sample is
        // (0.0546, 0.0760, 1.0403) -> sRGB (0.2591, 0.3056, 1.0).
        assertEquals(0.2591f, rendered[0], 2.0f / 255.0f)
        assertEquals(0.3056f, rendered[1], 2.0f / 255.0f)
        assertEquals(1.0f, rendered[2], 1.0f / 255.0f)
        assertTrue("red must be lifted towards luminance, not clipped", rendered[0] > encodeSrgb(0.02f))
    }

    @Test
    fun shouldKeepChannelOrdering_whenSaturatedShadowIsLiftedHard() {
        val rendered = render(toneParams(exposure = 4.0f), floatArrayOf(0.005f, 0.01f, 0.4f))

        assertTrue("blue must stay dominant: ${rendered.toList()}", rendered[2] > rendered[1])
        assertTrue("green must stay above red: ${rendered.toList()}", rendered[1] > rendered[0])
        assertTrue("channels must stay inside the displayable range", rendered.all { it in 0.0f..1.0f })
    }

    @Test
    fun shouldExposeGlowCouplingsForPassB2AndPassC_whenGlowIsSet() {
        assertEquals(0.20f, ToneShaderMath.glowClarityBoost(glow = 100), 1e-6f)
        assertEquals(0.10f, ToneShaderMath.glowVibranceBoost(glow = 100), 1e-6f)
        assertEquals(0.10f, ToneShaderMath.glowClarityBoost(glow = 50), 1e-6f)
        assertEquals(0.0f, ToneShaderMath.glowClarityBoost(glow = 0), 1e-6f)
    }

    @Test
    fun shouldStayWithinOneEightBitStep_whenEverySliderIsCombined() {
        val params = toneParams(
            exposure = 0.6f,
            brightness = 25,
            contrast = 20,
            glow = 30,
            highlights = -20,
            shadows = 15,
            whites = -10,
            blacks = 12,
            temp = 18,
            tint = -7
        )
        val sample = floatArrayOf(0.03f, 0.11f, 0.5f)
        val rendered = render(params, sample)

        assertTrue(rendered.all { it.isFinite() && it in 0.0f..1.0f })
        assertTrue(
            "a combined edit must actually change the pixel",
            abs(rendered[2] - encodeSrgb(sample[2])) > 1.0f / 255.0f
        )
    }

    @Test
    fun shouldReturnThreeChannels_whenWritingIntoAReusedOutputArray() {
        val out = FloatArray(3) { -1.0f }
        ToneShaderMath.apply(toneParams(), floatArrayOf(0.18f, 0.18f, 0.18f), out)

        assertTrue(out.all { it > 0.0f })
    }
}
