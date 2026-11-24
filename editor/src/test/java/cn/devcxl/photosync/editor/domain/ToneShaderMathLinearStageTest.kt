package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ExposureShaderMath.decodeSrgb
import cn.devcxl.photosync.editor.domain.ExposureShaderMath.encodeSrgb

/**
 * Seam under test: [ToneShaderMath] linear stage — the white-balance matrix, exposure, middle-gray
 * brightness and the highlight / shadow / whites trims that must all run before `[0, 1]` clipping
 * so the 16-bit RAW headroom above diffuse white stays recoverable.
 */
class ToneShaderMathLinearStageTest {

    @Test
    fun shouldReturnPlainSrgbEncoding_whenEverySliderIsZero() {
        val samples = listOf(
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(0.18f, 0.18f, 0.18f),
            floatArrayOf(0.62f, 0.24f, 0.08f),
            floatArrayOf(0.02f, 0.05f, 1.4f)
        )

        for (sample in samples) {
            val rendered = render(neutralParams(), sample)
            for (channel in 0..2) {
                assertEquals(
                    "channel $channel of ${sample.toList()}",
                    encodeSrgb(sample[channel]).coerceIn(0.0f, 1.0f),
                    rendered[channel],
                    1e-6f
                )
            }
        }
    }

    @Test
    fun shouldDoubleLinearLight_whenExposureIsRaisedOneStop() {
        val rendered = render(
            toneParams(exposure = 1.0f),
            floatArrayOf(0.18f, 0.18f, 0.18f)
        )

        assertEquals(encodeSrgb(0.36f), rendered[0], 1.0f / 255.0f)
        assertEquals(encodeSrgb(0.36f), rendered[2], 1.0f / 255.0f)
    }

    @Test
    fun shouldKeepHeadroomDetailInsteadOfHardClipping_whenExposureMeetsTheShoulder() {
        val params = toneParams(exposure = 1.0f)
        val midHighlight = render(params, floatArrayOf(0.5f, 0.5f, 0.5f))[0]
        val clippedHighlight = render(params, floatArrayOf(1.0f, 1.0f, 1.0f))[0]

        assertTrue("a 1.0 linear highlight must stay below pure white", clippedHighlight < 1.0f)
        assertTrue(
            "a doubled 0.5 sample must stay darker than a doubled 1.0 sample",
            midHighlight < clippedHighlight - 1.0f / 255.0f
        )
    }

    @Test
    fun shouldLiftMiddleGrayOnly_whenBrightnessIsRaised() {
        val params = toneParams(brightness = 100)
        val midGain = linearGain(params, 0.18f)
        val whiteGain = linearGain(params, 0.9f)
        val shadowGain = linearGain(params, 0.005f)

        assertTrue("middle gray must gain roughly a stop, was $midGain", midGain in 1.8f..2.1f)
        assertTrue("near-white must barely move, was $whiteGain", whiteGain in 1.0f..1.1f)
        assertTrue("deep shadow must stay put, was $shadowGain", abs(shadowGain - 1.0f) < 0.01f)
    }

    @Test
    fun shouldRecoverAboveWhiteHeadroom_whenHighlightsAreNegative() {
        val params = toneParams(highlights = -100)
        val clipped = render(neutralParams(), floatArrayOf(1.2f, 1.2f, 1.2f))[0]
        val recovered = render(params, floatArrayOf(1.2f, 1.2f, 1.2f))[0]

        assertTrue("the neutral pipeline must clip 1.2 linear to white", clipped > 0.99f)
        assertTrue("negative highlights must pull Y > 1 back below white, was $recovered", recovered < 0.9f)
        assertTrue("negative highlights must not crush the sample to grey, was $recovered", recovered > 0.7f)
    }

    @Test
    fun shouldRescueHeadroomAndKeepNormalWhiteBright_whenWhitesAreNegative() {
        val params = toneParams(whites = -100)
        val headroom = render(params, floatArrayOf(1.2f, 1.2f, 1.2f))[0]
        val normalWhite = render(params, floatArrayOf(1.0f, 1.0f, 1.0f))[0]

        assertTrue("negative whites must recover the overexposed sample, was $headroom", headroom < 0.97f)
        assertTrue("negative whites must keep a 1.0 white bright, was $normalWhite", normalWhite > 0.84f)
    }

    @Test
    fun shouldLeaveMidtonesAlone_whenHighlightsAndWhitesArePulledDown() {
        val params = toneParams(highlights = -100, whites = -100)
        val midGrey = render(params, floatArrayOf(0.02f, 0.02f, 0.02f))[0]

        assertEquals(encodeSrgb(0.02f), midGrey, 1.0f / 255.0f)
    }

    @Test
    fun shouldLiftDeepShadowOnly_whenShadowsArePositive() {
        val params = toneParams(shadows = 100)
        val shadowGain = linearGain(params, 0.02f)
        val highlightGain = linearGain(params, 0.9f)

        assertTrue("shadows must lift a deep shadow, was $shadowGain", shadowGain > 1.2f)
        assertTrue("shadows must leave highlights alone, was $highlightGain", abs(highlightGain - 1.0f) < 0.01f)
    }

    @Test
    fun shouldNeutraliseWarmCast_whenTemperatureIsPulledCool() {
        val warmPixel = floatArrayOf(0.22f, 0.18f, 0.12f)
        val coolParams = toneParams(temp = -100)
        val cooled = render(coolParams, warmPixel)

        val originalRatio = warmPixel[0] / warmPixel[2]
        val cooledRatio = decodeSrgb(cooled[0]) / decodeSrgb(cooled[2])
        assertTrue("cooling must reduce the red/blue ratio", cooledRatio < originalRatio)
    }

    @Test
    fun shouldAddWarmCast_whenTemperatureIsPulledWarm() {
        val neutralPixel = floatArrayOf(0.18f, 0.18f, 0.18f)
        val warm = render(toneParams(temp = 100), neutralPixel)
        val cool = render(toneParams(temp = -100), neutralPixel)

        assertTrue("warm must raise red above blue", warm[0] > warm[2])
        assertTrue("cool must raise blue above red", cool[2] > cool[0])
    }

    /**
     * Reads the linear-light gain that the linear stage applied to a grey sample.
     *
     * A grey stays grey through the whole pass, so decoding the display result recovers the linear
     * value the stage produced without reaching into private helpers.
     */
    private fun linearGain(params: ToneShaderParams, grey: Float): Float {
        val rendered = render(params, floatArrayOf(grey, grey, grey))
        return decodeSrgb(rendered[0]) / grey
    }
}
