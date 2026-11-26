package cn.devcxl.photosync.editor.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [ClassicSkySegmenter] — the zero-model P3 segmenter.
 *
 * The cases pin the three behaviours the ticket requires: a clear outdoor frame yields a large sky
 * mask, an indoor frame stays under the `< 3 %` warning threshold, and the guided refinement keeps a
 * hard building edge in place instead of bleeding the mask across it.
 */
class ClassicSkySegmenterTest {

    private val segmenter = ClassicSkySegmenter()

    @Test
    fun shouldDetectALargeSky_whenTheTopIsBlueAndTheBottomIsGround() {
        val image = syntheticImage(96, 72) { u, v ->
            if (v < 0.58f) {
                val depth = v / 0.58f
                argb(
                    red = 70 + (depth * 80).toInt(),
                    green = 130 + (depth * 70).toInt(),
                    blue = 200 + (depth * 40).toInt()
                )
            } else {
                argb(red = 92, green = 84, blue = 58)
            }
        }

        val result = segmenter.segment(image).getOrThrow()

        assertTrue(result.hasSky)
        assertTrue("coverage was ${result.mask.coverageRatio}", result.mask.coverageRatio > 0.35f)
        assertTrue(result.mask.sample(0.5f, 0.15f) > 0.6f)
        assertTrue(result.mask.sample(0.5f, 0.9f) < 0.2f)
    }

    @Test
    fun shouldReportNoSky_whenTheFrameIsAnIndoorScene() {
        val image = syntheticImage(80, 60) { u, v ->
            val wall = if (u < 0.5f) 118 else 96
            val furnishing = if (v > 0.62f) 70 else wall
            argb(red = furnishing, green = furnishing - 6, blue = furnishing - 12)
        }

        val result = segmenter.segment(image).getOrThrow()

        assertFalse(result.hasSky)
        assertTrue(result.mask.coverageRatio < SkyMask.MIN_USABLE_COVERAGE)
    }

    @Test
    fun shouldKeepTheMaskOffTheBuilding_whenTheGuideHasAHardVerticalEdge() {
        val image = syntheticImage(96, 64) { u, _ ->
            if (u < 0.62f) {
                argb(red = 88, green = 148, blue = 214)
            } else {
                argb(red = 28, green = 26, blue = 24)
            }
        }

        val result = segmenter.segment(image).getOrThrow()

        assertTrue(result.mask.sample(0.35f, 0.25f) > 0.55f)
        assertTrue(result.mask.sample(0.78f, 0.25f) < 0.25f)
    }

    private fun syntheticImage(
        width: Int,
        height: Int,
        painter: (u: Float, v: Float) -> Int
    ): SubjectImage {
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val v = (row + 0.5f) / height
            for (column in 0 until width) {
                val u = (column + 0.5f) / width
                pixels[row * width + column] = painter(u, v)
            }
        }
        return SubjectImage(width, height, pixels)
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)
}
