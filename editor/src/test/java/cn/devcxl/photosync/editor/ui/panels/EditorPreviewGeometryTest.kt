package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Seam under test: which frame the preview canvas shows for each active geometry tool, so the
 * on-canvas handles always live in the frame the user sees.
 */
class EditorPreviewGeometryTest {

    private val graph = EditGraph(
        geometry = GeometryOps(
            rotate90 = 1,
            flipH = true,
            straighten = 6.5f,
            crop = CropRect(0.2f, 0.15f, 0.85f, 0.9f),
            perspective = listOf(0.05f, 0.08f, 0.95f, 0.02f, 0.98f, 0.94f, 0.02f, 0.88f)
        )
    )

    @Test
    fun shouldRevealTheWholeFrame_whenTheCropToolIsActive() {
        val preview = EditorPreviewGeometry.previewGraph(graph, EditorTool.CROP)
        assertTrue(preview.geometry.crop.isIdentity())
        assertEquals(graph.geometry.straighten, preview.geometry.straighten, TOL)
        assertEquals(graph.geometry.perspective, preview.geometry.perspective)
        assertEquals(graph.geometry.rotate90, preview.geometry.rotate90)
        assertTrue(preview.geometry.flipH)
    }

    @Test
    fun shouldRevealTheStraightenedFrame_whenTheStraightenToolIsActive() {
        val preview = EditorPreviewGeometry.previewGraph(graph, EditorTool.STRAIGHTEN)
        assertTrue(preview.geometry.crop.isIdentity())
        assertEquals(6.5f, preview.geometry.straighten, TOL)
    }

    @Test
    fun shouldHideTheQuadWarp_whenThePerspectiveToolIsActive() {
        val preview = EditorPreviewGeometry.previewGraph(graph, EditorTool.PERSPECTIVE)
        assertTrue(preview.geometry.crop.isIdentity())
        assertEquals(GeometryOps.DEFAULT_PERSPECTIVE_QUAD, preview.geometry.perspective)
        assertEquals(6.5f, preview.geometry.straighten, TOL)
    }

    @Test
    fun shouldLeaveTheGraphUntouched_whenANonGeometryToolIsActive() {
        listOf(EditorTool.EXPOSURE, EditorTool.DISPERSION_RED_CYAN, EditorTool.NONE).forEach { tool ->
            assertEquals("tool=$tool", graph, EditorPreviewGeometry.previewGraph(graph, tool))
        }
    }

    @Test
    fun shouldReportThePreviewFrameAspect_whenAToolIsActive() {
        val sourceAspect = 1.5f
        val cropPreviewAspect = EditorPreviewGeometry.previewAspect(graph, EditorTool.CROP, sourceAspect)
        assertEquals(
            GeometryMath.outputAspect(
                graph.geometry.copy(crop = CropRect()),
                sourceAspect
            ),
            cropPreviewAspect,
            1e-4f
        )
        val perspectivePreviewAspect =
            EditorPreviewGeometry.previewAspect(graph, EditorTool.PERSPECTIVE, sourceAspect)
        assertEquals(
            GeometryMath.outputAspect(
                graph.geometry.copy(crop = CropRect(), perspective = GeometryOps.DEFAULT_PERSPECTIVE_QUAD),
                sourceAspect
            ),
            perspectivePreviewAspect,
            1e-4f
        )
        assertEquals(
            GeometryMath.outputAspect(graph.geometry, sourceAspect),
            EditorPreviewGeometry.previewAspect(graph, EditorTool.EXPOSURE, sourceAspect),
            1e-4f
        )
    }

    private companion object {
        private const val TOL = 1e-4f
    }
}
