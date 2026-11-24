package cn.devcxl.photosync.editor.domain

import kotlin.math.cos
import kotlin.math.sin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the noise-reduction half of [PassCDetailMath] — luminance `3 x 3` guided filtering
 * with a `detail` blend-back, and CbCr `5 x 5` edge-guided chroma smoothing.
 */
class PassCLumaNoiseReductionTest {

    private val support = SpatialDetailTestSupport

    @Test
    fun shouldAverageFlatSensorNoise_whenLuminanceNoiseReductionIsRaised() {
        val width = 48
        val height = 48
        val image = support.noisyGrayFrame(width, height)
        val before = support.meanAbsoluteLocalNoise(image, width, height)

        PassCDetailMath.apply(
            image,
            width,
            height,
            support.params(nrLuma = 1.0f, nrDetail = 0.0f)
        )

        val after = support.meanAbsoluteLocalNoise(image, width, height)
        assertTrue("NR must calm flat grain: before=$before after=$after", after < before * 0.6f)
        assertEquals(0.5f, support.meanLuma(image), 0.01f)
    }

    @Test
    fun shouldPreserveAHardEdge_whenLuminanceNoiseReductionIsRaised() {
        val width = 48
        val height = 48
        val image = support.hardEdgeFrame(width, height)
        val before = support.edgeContrast(image, width, height)

        PassCDetailMath.apply(
            image,
            width,
            height,
            support.params(nrLuma = 1.0f, nrDetail = 0.0f)
        )

        val after = support.edgeContrast(image, width, height)
        assertTrue("A hard edge must survive NR: before=$before after=$after", after > before * 0.98f)
    }

    @Test
    fun shouldReturnTheInputUntouched_whenNrDetailIsAtMaximum() {
        val width = 32
        val height = 32
        val image = support.noisyGrayFrame(width, height)
        val original = image.copyOf()

        PassCDetailMath.apply(
            image,
            width,
            height,
            support.params(nrLuma = 1.0f, nrDetail = 1.0f)
        )

        assertArrayEquals(original, image, 0.0f)
    }

    @Test
    fun shouldKeepMoreGrain_whenTheDetailBlendBackIsRaised() {
        val width = 48
        val height = 48
        val softImage = support.noisyGrayFrame(width, height)
        val detailedImage = support.noisyGrayFrame(width, height)

        PassCDetailMath.apply(softImage, width, height, support.params(nrLuma = 1f, nrDetail = 0f))
        PassCDetailMath.apply(detailedImage, width, height, support.params(nrLuma = 1f, nrDetail = 0.8f))

        val softNoise = support.meanAbsoluteLocalNoise(softImage, width, height)
        val detailedNoise = support.meanAbsoluteLocalNoise(detailedImage, width, height)
        assertTrue(
            "detail=0.8 must keep more grain than detail=0: soft=$softNoise detailed=$detailedNoise",
            detailedNoise > softNoise
        )
    }

    @Test
    fun shouldSmoothColourMottling_whenChromaNoiseReductionIsRaised() {
        val width = 32
        val height = 32
        val image = support.chromaMottledFrame(width, height)
        val before = support.chromaSpread(image)

        PassCDetailMath.apply(image, width, height, support.params(nrChroma = 1.0f))

        val after = support.chromaSpread(image)
        assertTrue("Chroma NR must reduce mottling: before=$before after=$after", after < before * 0.5f)
        assertEquals(0.5f, support.meanLuma(image), 0.02f)
    }

    @Test
    fun shouldKeepChromaBounded_whenNrRunsOnSaturatedNoise() {
        val width = 24
        val height = 24
        val image = FloatArray(width * height * 3) { index ->
            val noise = 0.25f * sin(index * 0.9f) * cos(index * 0.4f)
            if (index % 3 == 0) 0.9f else 0.2f + noise
        }

        PassCDetailMath.apply(
            image,
            width,
            height,
            support.params(nrLuma = 1.0f, nrChroma = 1.0f)
        )

        for (value in image) {
            assertTrue("NR must stay in gamut, got $value", value in 0.0f..1.0f)
        }
    }
}
