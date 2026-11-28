package cn.devcxl.photosync.editor.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Seam under test: [BakeSubjectSupport] — the band mapping between the upright source-space mask and
 * one post-crop bake band, which is what keeps the full-resolution cut-out seam-free.
 */
class BakeSubjectSupportTest {

    @Test
    fun shouldMapTheMaskThroughTheBandTransform_whenComposingABand() {
        val width = 64
        val height = 16
        val mask = SubjectMask(
            width = width,
            height = height,
            alpha = FloatArray(width * height) { index ->
                if (index % width < width / 2) 1.0f else 0.0f
            }
        )
        val graph = EditGraph(
            subject = SubjectOps(
                maskRef = "mask.png",
                bgMode = SubjectBackgroundMode.SOLID
            )
        )
        val support = BakeSubjectSupport(
            graph = graph,
            mask = mask,
            initial = null,
            warpField = null,
            inverseMatrix = Mat3.identity()
        )
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        val band = ColumnBandTiling.fullFrame(width, height)

        val composite = support.composeBand(pixels, width, height, band)

        assertNotNull(composite)
        assertEquals(width, composite!!.width)
        val left = composite.alpha[(height / 2) * width + 8]
        val right = composite.alpha[(height / 2) * width + 56]
        assertTrue("left=$left right=$right", left > 0.6f)
        assertTrue("left=$left right=$right", right < 0.4f)
        assertTrue(composite.foreground.all { it.isFinite() })
    }

    @Test
    fun shouldKeepTheCutout_whenTheBandFallsBackWithoutPixels() {
        val width = 32
        val height = 8
        val mask = SubjectMask(
            width = width,
            height = height,
            alpha = FloatArray(width * height) { index ->
                if (index % width < width / 2) 1.0f else 0.0f
            }
        )
        val graph = EditGraph(
            subject = SubjectOps(maskRef = "mask.png", bgMode = SubjectBackgroundMode.TRANSPARENT)
        )
        val support = BakeSubjectSupport(graph, mask, null, null, Mat3.identity())

        val composite = support.composeFallbackBand(
            width = width,
            height = height,
            band = ColumnBandTiling.fullFrame(width, height)
        )

        assertNotNull(composite)
        assertTrue(composite!!.alpha[(height / 2) * width + 4] > 0.5f)
        assertTrue(composite.alpha[(height / 2) * width + 28] < 0.5f)
    }
}
