package cn.devcxl.photosync.editor.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CropRect

/**
 * Seam under test: the pure rect algebra behind [cn.devcxl.photosync.editor.ui.widgets.CropOverlayView],
 * which must keep the crop inside the frame, respect the aspect lock, and never produce a
 * degenerate rectangle.
 */
class CropHandleMathTest {

    @Test
    fun shouldPreferCornersThenEdgesThenBody_whenHitTestingTheCropRect() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(CropHandle.TOP_LEFT, CropHandleMath.hitTest(rect, 0.2f, 0.2f, 0.05f))
        assertEquals(CropHandle.TOP_RIGHT, CropHandleMath.hitTest(rect, 0.79f, 0.21f, 0.05f))
        assertEquals(CropHandle.BOTTOM_RIGHT, CropHandleMath.hitTest(rect, 0.81f, 0.79f, 0.05f))
        assertEquals(CropHandle.BOTTOM_LEFT, CropHandleMath.hitTest(rect, 0.2f, 0.8f, 0.05f))
        assertEquals(CropHandle.TOP, CropHandleMath.hitTest(rect, 0.5f, 0.2f, 0.05f))
        assertEquals(CropHandle.BOTTOM, CropHandleMath.hitTest(rect, 0.5f, 0.8f, 0.05f))
        assertEquals(CropHandle.LEFT, CropHandleMath.hitTest(rect, 0.2f, 0.5f, 0.05f))
        assertEquals(CropHandle.RIGHT, CropHandleMath.hitTest(rect, 0.8f, 0.5f, 0.05f))
        assertEquals(CropHandle.MOVE, CropHandleMath.hitTest(rect, 0.5f, 0.5f, 0.05f))
        assertNull(CropHandleMath.hitTest(rect, 0.05f, 0.05f, 0.02f))
    }

    @Test
    fun shouldMoveOnlyTheDraggedCorner_whenDraggingWithoutAnAspectLock() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val dragged = CropHandleMath.drag(rect, CropHandle.TOP_LEFT, 0.1f, 0.05f, null, 1.5f)
        assertEquals(0.3f, dragged.left, TOL)
        assertEquals(0.25f, dragged.top, TOL)
        assertEquals(0.8f, dragged.right, TOL)
        assertEquals(0.8f, dragged.bottom, TOL)
    }

    @Test
    fun shouldStayInsideTheFrame_whenDraggingBeyondAnEdge() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val dragged = CropHandleMath.drag(rect, CropHandle.BOTTOM_RIGHT, 0.9f, 0.9f, null, 1.5f)
        assertTrue(dragged.right <= 1.0f + TOL)
        assertTrue(dragged.bottom <= 1.0f + TOL)
        assertEquals(1.0f, dragged.right, TOL)
        assertEquals(1.0f, dragged.bottom, TOL)

        val draggedOut = CropHandleMath.drag(rect, CropHandle.TOP_LEFT, -0.9f, -0.9f, null, 1.5f)
        assertEquals(0.0f, draggedOut.left, TOL)
        assertEquals(0.0f, draggedOut.top, TOL)
    }

    @Test
    fun shouldPreserveTheLockedPhysicalAspect_whenDraggingACorner() {
        // A square crop inside a 3:2 frame needs a normalized width/height ratio of 1 / 1.5.
        val rect = CropRect(0.1f, 0.1f, 0.7667f, 0.9f)
        val dragged = CropHandleMath.drag(rect, CropHandle.BOTTOM_RIGHT, 0.2f, 0.4f, 1.0f, 1.5f)
        assertEquals(1.0f, CropHandleMath.physicalAspect(dragged, 1.5f), 1e-3f)
        assertEquals(rect.left, dragged.left, TOL)
        assertEquals(rect.top, dragged.top, TOL)
    }

    @Test
    fun shouldKeepTheLockedAspect_whenOnlyAnEdgeIsDragged() {
        val rect = CropRect(0.1f, 0.1f, 0.5f, 0.7f)
        val dragged = CropHandleMath.drag(rect, CropHandle.RIGHT, 0.15f, 0.0f, 1.0f, 1.5f)
        assertEquals(1.0f, CropHandleMath.physicalAspect(dragged, 1.5f), 1e-3f)
        assertTrue("the crop must actually grow", dragged.right > rect.right)
        assertTrue(dragged.bottom <= 1.0f + TOL)
    }

    @Test
    fun shouldTranslateWithoutResizing_whenDraggingTheBody() {
        val rect = CropRect(0.3f, 0.3f, 0.7f, 0.7f)
        val moved = CropHandleMath.drag(rect, CropHandle.MOVE, 0.1f, -0.1f, null, 1.0f)
        assertEquals(0.4f, moved.left, TOL)
        assertEquals(0.2f, moved.top, TOL)
        assertEquals(0.8f, moved.right, TOL)
        assertEquals(0.6f, moved.bottom, TOL)
        assertEquals(rect.right - rect.left, moved.right - moved.left, TOL)
    }

    @Test
    fun shouldClampTheTranslation_whenTheBodyIsDraggedPastTheFrameEdge() {
        val rect = CropRect(0.3f, 0.3f, 0.7f, 0.7f)
        val moved = CropHandleMath.drag(rect, CropHandle.MOVE, 0.9f, 0.9f, null, 1.0f)
        assertEquals(1.0f, moved.right, TOL)
        assertEquals(1.0f, moved.bottom, TOL)
        assertEquals(0.6f, moved.left, TOL)
        assertEquals(0.6f, moved.top, TOL)
    }

    @Test
    fun shouldNeverCollapseBelowTheMinimumSpan_whenDraggingPastTheOppositeEdge() {
        val rect = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val collapsed = CropHandleMath.drag(rect, CropHandle.TOP_LEFT, 5.0f, 5.0f, null, 1.0f)
        assertTrue(collapsed.right - collapsed.left >= CropHandleMath.MIN_SPAN - TOL)
        assertTrue(collapsed.bottom - collapsed.top >= CropHandleMath.MIN_SPAN - TOL)
    }

    @Test
    fun shouldInstallTheLargestCentredRect_whenApplyingAnAspectPreset() {
        val square = CropHandleMath.centredWithAspect(1.0f, frameAspect = 1.5f)
        assertEquals(1.0f / 1.5f, square.right - square.left, TOL)
        assertEquals(1.0f, square.bottom - square.top, TOL)
        assertEquals(0.5f, (square.left + square.right) / 2.0f, TOL)
        assertEquals(0.5f, (square.top + square.bottom) / 2.0f, TOL)

        val widescreen = CropHandleMath.centredWithAspect(16.0f / 9.0f, frameAspect = 1.5f)
        assertEquals(1.0f, widescreen.right - widescreen.left, TOL)
        assertEquals(1.5f / (16.0f / 9.0f), widescreen.bottom - widescreen.top, TOL)
        assertEquals(16.0f / 9.0f, CropHandleMath.physicalAspect(widescreen, 1.5f), 1e-3f)
    }

    @Test
    fun shouldSelectTheWholeFrame_whenTheOriginalRatioPresetIsApplied() {
        listOf(0.6f, 1.0f, 1.5f, 16.0f / 9.0f).forEach { frameAspect ->
            val original = CropHandleMath.centredWithAspect(frameAspect, frameAspect)
            assertEquals(0.0f, original.left, TOL)
            assertEquals(0.0f, original.top, TOL)
            assertEquals(1.0f, original.right, TOL)
            assertEquals(1.0f, original.bottom, TOL)
            assertEquals(frameAspect, CropHandleMath.physicalAspect(original, frameAspect), 1e-3f)
        }
    }

    @Test
    fun shouldOffsetTheDraggedPerspectiveCornerOnly_whenDraggingOnePin() {
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val dragged = CropHandleMath.dragPerspectiveCorner(quad, cornerIndex = 0, deltaU = 0.1f, deltaV = 0.05f)
        assertEquals(0.1f, dragged[0], TOL)
        assertEquals(0.05f, dragged[1], TOL)
        for (index in 2 until dragged.size) {
            assertEquals(quad[index], dragged[index], TOL)
        }
    }

    @Test
    fun shouldClampThePerspectiveCornerInsideTheSourceFrame_whenDraggingPastTheEdge() {
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val dragged = CropHandleMath.dragPerspectiveCorner(quad, cornerIndex = 2, deltaU = 5f, deltaV = -0.2f)
        assertEquals(1.0f, dragged[4], TOL)
        assertEquals(0.8f, dragged[5], TOL)
    }

    @Test
    fun shouldKeepPreviousValidQuad_whenPerspectiveDragWouldBecomeConcaveOrDegenerate() {
        val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val dragged = CropHandleMath.dragPerspectiveCorner(quad, cornerIndex = 2, deltaU = -0.9f, deltaV = -0.9f)
        for (i in quad.indices) {
            assertEquals(quad[i], dragged[i], TOL)
        }
    }

    private companion object {
        private const val TOL = 1e-3f
    }
}
