package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seam under test: [InpaintWorkRegion], the bridge between a full-resolution window and the patch
 * boxes that are persisted in pre-crop source space.
 */
class InpaintWorkRegionTest {

    @Test
    fun shouldReturnTheWindowToFullSourceCoordinates_whenAPatchIsRemapped() {
        val region = InpaintWorkRegion(
            originX = 1200,
            originY = 800,
            width = 600,
            height = 400,
            sourceWidth = 3000,
            sourceHeight = 2000
        )
        val local = InpaintBBox(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)

        val source = region.toSourceBBox(local)

        assertEquals(0.45f, source.left, 1e-5f)
        assertEquals(0.45f, source.top, 1e-5f)
        assertEquals(0.55f, source.right, 1e-5f)
        assertEquals(0.55f, source.bottom, 1e-5f)
    }

    @Test
    fun shouldKeepThePhysicalRadius_whenFullSourceStrokesAreLocalized() {
        val region = InpaintWorkRegion(
            originX = 1200,
            originY = 800,
            width = 600,
            height = 400,
            sourceWidth = 3000,
            sourceHeight = 2000
        )
        val stroke = InpaintStroke(
            erase = false,
            radiusNorm = 0.01f,
            points = listOf(InpaintStrokePoint(0.5f, 0.5f))
        )

        val local = region.localize(listOf(stroke)).single()

        assertEquals(0.05f, local.radiusNorm, 1e-6f)
        assertEquals(0.5f, local.points.single().u, 1e-6f)
        assertEquals(0.5f, local.points.single().v, 1e-6f)
    }
}
