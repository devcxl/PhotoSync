package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryOps

/**
 * Seam under test: [OffscreenPreviewFrames] — the geometry and read-back contract the offscreen GPU
 * preview shares with the CPU preview writer.
 */
class OffscreenPreviewFramesTest {

    @Test
    fun shouldFitLandscapeSourceInto1280LongEdge_whenGeometryIsNeutral() {
        val size = OffscreenPreviewFrames.outputSize(EditGraph(), sourceWidth = 4000, sourceHeight = 3000)

        assertEquals(1280 to 960, size)
    }

    @Test
    fun shouldFitPortraitSourceInto1280LongEdge_whenGeometryIsNeutral() {
        val size = OffscreenPreviewFrames.outputSize(EditGraph(), sourceWidth = 3000, sourceHeight = 4000)

        assertEquals(960 to 1280, size)
    }

    @Test
    fun shouldUseSquareFrame_whenSourceAspectIsDegenerate() {
        val size = OffscreenPreviewFrames.outputSize(EditGraph(), sourceWidth = 0, sourceHeight = 0)

        assertEquals(1280 to 1280, size)
    }

    @Test
    fun shouldFollowCropAspect_whenGeometryCropsTheFrame() {
        // A 1000x1000 crop of a 4000x3000 source is square, so the preview must be square too.
        val crop = CropRect(left = 0.0f, top = 0.0f, right = 0.25f, bottom = 1.0f / 3.0f)
        val graph = EditGraph(geometry = GeometryOps(crop = crop))

        val size = OffscreenPreviewFrames.outputSize(graph, sourceWidth = 4000, sourceHeight = 3000)

        assertEquals(1280 to 1280, size)
    }

    @Test
    fun shouldSwapPreviewEdges_whenGeometryRotatesByNinetyDegrees() {
        val graph = EditGraph(geometry = GeometryOps(rotate90 = 1))

        val size = OffscreenPreviewFrames.outputSize(graph, sourceWidth = 4000, sourceHeight = 3000)

        assertEquals(960 to 1280, size)
    }

    @Test
    fun shouldPackOpaqueArgbAndKeepRowOrder_whenReadBackIsTopDown() {
        val width = 2
        val height = 2
        val rgba = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        // Row 0 is the image top: one red and one green pixel. Row 1: one blue and one white.
        put(rgba, 255, 0, 0, 0)
        put(rgba, 0, 255, 0, 0)
        put(rgba, 0, 0, 255, 0)
        put(rgba, 10, 20, 30, 0)

        val pixels = OffscreenPreviewFrames.toArgb(rgba, width, height)

        assertEquals(0xFF_FF_00_00.toInt(), pixels[0])
        assertEquals(0xFF_00_FF_00.toInt(), pixels[1])
        assertEquals(0xFF_00_00_FF.toInt(), pixels[2])
        assertEquals(0xFF_0A_14_1E.toInt(), pixels[3])
        assertTrue("every preview pixel must be opaque", pixels.all { (it ushr 24) == 0xFF })
    }

    private fun put(buffer: ByteBuffer, red: Int, green: Int, blue: Int, alpha: Int) {
        buffer.put(red.toByte())
        buffer.put(green.toByte())
        buffer.put(blue.toByte())
        buffer.put(alpha.toByte())
    }
}
