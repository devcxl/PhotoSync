package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [InpaintStrokeLayer] — the ordered paint/erase vector layer the brush view fills
 * and the inpainting run rasterizes.
 */
class InpaintStrokeLayerTest {

    @Test
    fun shouldRasterizeARoundDabAndReportItsBounds_whenOneStrokeIsCommitted() {
        val layer = InpaintStrokeLayer()
        layer.add(stroke(erase = false, u = 0.5f, v = 0.5f))

        val mask = layer.rasterize(100, 100)
        val bounds = layer.bounds(100, 100)

        assertNotNull(bounds)
        assertEquals(InpaintPixelRect(40, 40, 60, 60), bounds)
        assertEquals(255, mask.coverageAt(50, 50))
        assertTrue(mask.coverageAt(50, 59) in 1..254)
        assertEquals(0, mask.coverageAt(0, 0))
    }

    @Test
    fun shouldPunchAHole_whenAnEraserStrokeRunsOverPaint() {
        val layer = InpaintStrokeLayer()
        layer.add(stroke(erase = false, u = 0.5f, v = 0.5f))
        layer.add(stroke(erase = true, u = 0.5f, v = 0.5f))

        val mask = layer.rasterize(100, 100)

        assertEquals(0, mask.coverageAt(50, 50))
        assertEquals(0, mask.coverageAt(50, 41))
    }

    @Test
    fun shouldKeepTheNewestPaint_whenEraseAndPaintAlternate() {
        val layer = InpaintStrokeLayer()
        layer.add(stroke(erase = false, u = 0.5f, v = 0.5f))
        layer.add(stroke(erase = true, u = 0.5f, v = 0.5f))
        layer.add(stroke(erase = false, u = 0.5f, v = 0.5f))

        val mask = layer.rasterize(100, 100)

        assertEquals(255, mask.coverageAt(50, 50))
    }

    @Test
    fun shouldFillTheCapsuleBetweenSamples_whenAStrokeHasTwoPoints() {
        val layer = InpaintStrokeLayer()
        layer.add(
            InpaintStroke(
                erase = false,
                radiusNorm = 0.05f,
                points = listOf(
                    InpaintStrokePoint(0.2f, 0.5f),
                    InpaintStrokePoint(0.8f, 0.5f)
                )
            )
        )

        val mask = layer.rasterize(100, 100)

        assertEquals(255, mask.coverageAt(50, 50))
        assertEquals(255, mask.coverageAt(20, 50))
        assertEquals(255, mask.coverageAt(80, 50))
        assertEquals(0, mask.coverageAt(50, 10))
    }

    @Test
    fun shouldClearEveryStroke_whenCleared() {
        val layer = InpaintStrokeLayer()
        layer.add(stroke(erase = false, u = 0.5f, v = 0.5f))

        layer.clear()

        assertTrue(layer.isEmpty)
        assertEquals(0, layer.strokeCount)
        assertEquals(null, layer.bounds(100, 100))
    }

    private fun stroke(erase: Boolean, u: Float, v: Float): InpaintStroke = InpaintStroke(
        erase = erase,
        radiusNorm = 0.1f,
        points = listOf(InpaintStrokePoint(u, v))
    )
}
