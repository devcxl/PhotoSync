package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the Dehaze half of [PassCDetailMath] — global atmospheric light `A`, upsampled
 * `t_low` with the `max(t, 0.1)` floor, and the inverse haze synthesis for a negative slider.
 */
class PassCDehazeTest {

    private val support = SpatialDetailTestSupport

    @Test
    fun shouldNeverDivideBelowTheTransmissionFloor_whenCoarseTransmissionCollapsesToZero() {
        val width = 16
        val height = 16
        val image = FloatArray(width * height * 3) { 0.5f }
        val analysis = support.analysis(
            clarityBase = 0.0f,
            textureBase = 0.0f,
            transmission = 0.0f,
            atmosphericLight = floatArrayOf(0.9f, 0.9f, 0.9f)
        )

        PassCDetailMath.apply(image, width, height, support.params(dehaze = 1.0f), analysis)

        assertEquals(0.1f, PassCDetailMath.transmission(0.0f), 0.0f)
        assertEquals(0.1f, PassCDetailMath.transmission(-4.0f), 0.0f)
        assertEquals(0.6f, PassCDetailMath.transmission(0.6f), 1e-6f)
        for (value in image) {
            assertTrue("Dehaze must stay bounded, got $value", value in 0.0f..1.0f)
        }
        assertEquals(0.0f, image[0], 1e-5f)
    }

    @Test
    fun shouldRecoverTheHazeFreeFrame_whenTransmissionAndAtmosphereAreKnown() {
        val width = 8
        val height = 8
        val hazeFree = 0.4f
        val atmosphere = 0.8f
        val transmission = 0.5f
        val hazy = hazeFree * transmission + atmosphere * (1.0f - transmission)
        val image = FloatArray(width * height * 3) { hazy }
        val analysis = support.analysis(
            clarityBase = 0.0f,
            textureBase = 0.0f,
            transmission = transmission,
            atmosphericLight = floatArrayOf(atmosphere, atmosphere, atmosphere)
        )

        PassCDetailMath.apply(image, width, height, support.params(dehaze = 1.0f), analysis)

        assertEquals(hazeFree, image[0], 1e-5f)
    }

    @Test
    fun shouldInvertToHazeSynthesis_whenDehazeIsNegative() {
        val width = 16
        val height = 16
        val image = FloatArray(width * height * 3) { 0.3f }
        val analysis = support.analysis(
            clarityBase = 0.0f,
            textureBase = 0.0f,
            transmission = 0.5f,
            atmosphericLight = floatArrayOf(0.8f, 0.8f, 0.8f)
        )

        PassCDetailMath.apply(image, width, height, support.params(dehaze = -1.0f), analysis)

        val expected = 0.3f * 0.5f + 0.8f * 0.5f
        assertEquals(expected, image[0], 1e-5f)
        assertNotEquals(0.3f, image[0])
    }

    @Test
    fun shouldBlendTowardsTheHazeFreeFrame_whenDehazeStrengthIsPartial() {
        val width = 8
        val height = 8
        val hazyValue = 0.6f
        val image = FloatArray(width * height * 3) { hazyValue }
        val analysis = support.analysis(
            clarityBase = 0.0f,
            textureBase = 0.0f,
            transmission = 0.5f,
            atmosphericLight = floatArrayOf(0.8f, 0.8f, 0.8f)
        )

        PassCDetailMath.apply(image, width, height, support.params(dehaze = 0.5f), analysis)

        val hazeFree = (hazyValue - 0.8f * 0.5f) / 0.5f
        assertEquals(hazyValue + 0.5f * (hazeFree - hazyValue), image[0], 1e-5f)
    }

    @Test
    fun shouldIgnoreDehaze_whenNoPrepassAnalysisIsAvailable() {
        val image = FloatArray(4 * 4 * 3) { 0.42f }

        PassCDetailMath.apply(image, 4, 4, support.params(dehaze = 1.0f), analysis = null)

        for (value in image) assertEquals(0.42f, value, 0.0f)
    }
}
