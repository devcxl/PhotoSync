package cn.devcxl.photosync.editor.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: canvas zoom/pan state that two-finger gestures drive in every tool mode.
 */
class CanvasTransformTest {

    @Test
    fun shouldStayAtFullFrame_whenNoGestureHasHappened() {
        assertTrue(CanvasTransform().isIdentity)
    }

    @Test
    fun shouldClampScaleIntoTheSupportedRange_whenPinchingHard() {
        val zoomedIn = CanvasTransform().pinch(scaleFactor = 40.0f, focusX = 0.5f, focusY = 0.5f)
        assertEquals(CanvasTransform.MAX_SCALE, zoomedIn.scale, 1e-4f)

        val zoomedOut = CanvasTransform(scale = 2.0f).pinch(scaleFactor = 0.01f, focusX = 0.5f, focusY = 0.5f)
        assertEquals(CanvasTransform.MIN_SCALE, zoomedOut.scale, 1e-4f)
        assertEquals(0.0f, zoomedOut.offsetX, 1e-4f)
        assertEquals(0.0f, zoomedOut.offsetY, 1e-4f)
    }

    @Test
    fun shouldKeepTheFocusPointAnchored_whenPinchingOffCentre() {
        // A focus at the left edge must push the content right so that point stays put.
        val pinch = CanvasTransform().pinch(scaleFactor = 2.0f, focusX = 0.0f, focusY = 0.5f)
        assertEquals(2.0f, pinch.scale, 1e-4f)
        assertTrue("content must shift inward", pinch.offsetX < 0.0f)
        assertEquals(0.0f, pinch.offsetY, 1e-4f)
    }

    @Test
    fun shouldClampOffsets_whenPanningPastTheImageEdge() {
        val panned = CanvasTransform(scale = 2.0f).pan(deltaX = 5.0f, deltaY = -5.0f)
        assertEquals(0.5f, panned.offsetX, 1e-4f)
        assertEquals(-0.5f, panned.offsetY, 1e-4f)
    }

    @Test
    fun shouldReturnToFitToScreen_whenReset() {
        val transformed = CanvasTransform(scale = 3.0f, offsetX = 0.3f, offsetY = -0.2f)
        assertFalse(transformed.isIdentity)
        assertTrue(transformed.reset().isIdentity)
    }

    @Test
    fun shouldIgnoreNonFiniteGestures_whenPinchingOrPanning() {
        val base = CanvasTransform(scale = 2.0f, offsetX = 0.1f, offsetY = 0.1f)
        assertEquals(base, base.pinch(Float.NaN, 0.5f, 0.5f))
        assertEquals(base, base.pinch(0.0f, 0.5f, 0.5f))
        assertEquals(base, base.pan(Float.POSITIVE_INFINITY, 0.0f))
    }
}
