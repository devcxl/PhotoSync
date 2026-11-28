package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [BandGuidedFilter] — the fast guided filter that lifts a 512px MODNet alpha onto
 * a preview proxy or a bake band without the 48 B/px cost of the textbook implementation.
 */
class BandGuidedFilterTest {

    @Test
    fun shouldPreserveTheGuideEdge_whenAlphaEqualsTheGuide() {
        val width = 64
        val height = 16
        val guide = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.1f else 0.9f
        }

        val refined = BandGuidedFilter.refine(
            guide = guide,
            alpha = guide.copyOf(),
            width = width,
            height = height,
            radius = 8,
            epsilon = 1e-4f
        )

        assertEquals(width * height, refined.size)
        val left = refined[8 * width + 8]
        val right = refined[8 * width + 56]
        assertTrue("left=$left right=$right", left < 0.3f)
        assertTrue("left=$left right=$right", right > 0.7f)
        assertTrue(refined.all { it.isFinite() && it in 0.0f..1.0f })
    }

    @Test
    fun shouldFeatherTheTransitionBand_whenAllocationFails() {
        val width = 64
        val height = 8
        val guide = FloatArray(width * height) { 0.5f }
        val alpha = FloatArray(width * height) { index ->
            ((index % width).toFloat() / (width - 1))
        }

        val fallback = BandGuidedFilter.refine(
            guide = guide,
            alpha = alpha,
            width = width,
            height = height,
            radius = 8,
            epsilon = 1e-4f,
            allocate = { throw OutOfMemoryError("simulated") }
        )

        assertEquals(width * height, fallback.size)
        assertTrue(fallback.all { it.isFinite() && it in 0.0f..1.0f })
        // A fully transparent pixel stays close to transparent and an opaque one close to opaque.
        assertTrue(fallback[0] < 0.1f)
        assertTrue(fallback[width - 1] > 0.9f)
    }

    @Test
    fun shouldScaleTheRadiusToTheTargetGrid_whenTheBandIsWiderThanTheProxy() {
        assertEquals(14, BandGuidedFilter.scaledRadius(1024))
        assertEquals(28, BandGuidedFilter.scaledRadius(2048))
        assertEquals(8, BandGuidedFilter.scaledRadius(320))
    }
}
