package cn.devcxl.photosync.editor.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.InpaintBrushOverlayView
import cn.devcxl.photosync.editor.ui.widgets.InpaintPanelView

/**
 * Seam under test: the two P2 brush views. Robolectric dispatches real `MotionEvent`s so the
 * two-finger preemption and the >256 px advice are verified against the actual touch code rather
 * than a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InpaintBrushOverlayViewTest {

    @Test
    fun shouldDiscardTheInFlightStroke_whenASecondFingerTouches() {
        val overlay = createOverlay()
        val listener = RecordingListener()
        overlay.listener = listener

        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 500f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_MOVE, 530f, 500f))
        overlay.dispatchTouchEvent(twoFingers(overlay, 400f, 500f, 600f, 500f))

        assertEquals(0, overlay.strokeLayer.strokeCount)
        assertEquals(1, listener.preempted)
        assertTrue(listener.lastStrokes.isEmpty())
    }

    @Test
    fun shouldCommitAStrokeAndEstimateItsBbox_whenTheFingerLifts() {
        val overlay = createOverlay()
        val listener = RecordingListener()
        overlay.listener = listener

        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 500f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_UP, 500f, 500f))

        assertEquals(1, overlay.strokeLayer.strokeCount)
        val bbox = checkNotNull(listener.lastBbox())
        assertTrue("dab must be ~40 px wide, got ${bbox.first}", bbox.first in 38..42)
        assertFalse(bbox.third)
    }

    @Test
    fun shouldReportOversize_whenThePaintedStrokeExceeds256Px() {
        val overlay = createOverlay()
        val listener = RecordingListener()
        overlay.listener = listener

        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 100f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_MOVE, 900f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_UP, 900f, 500f))

        val bbox = checkNotNull(listener.lastBbox())
        assertTrue("bbox width ${bbox.first} must exceed 256", bbox.first > 256)
        assertTrue(bbox.third)
    }

    @Test
    fun shouldClearCommittedStrokesOnly_whenClearIsRequested() {
        val overlay = createOverlay()
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 500f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_UP, 500f, 500f))

        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 600f, 500f))
        overlay.abortActiveStroke()
        assertEquals(1, overlay.strokeLayer.strokeCount)

        overlay.clearStrokes()
        assertTrue(overlay.strokeLayer.isEmpty)
    }

    @Test
    fun shouldExposeTheAmberWarningAndMode_whenThePanelIsDriven() {
        val panel = InpaintPanelView(RuntimeEnvironment.getApplication())
        panel.layout(0, 0, 1080, 320)
        val warning = panel.findViewById<TextView>(R.id.inpaintOversizeWarning)

        panel.showOversizeWarning(320, 180, 2)

        assertEquals(View.VISIBLE, warning.visibility)
        assertTrue(warning.text.toString().contains("320"))
        assertTrue(warning.text.toString().contains("180"))

        panel.setBrushMode(InpaintBrushMode.ERASE)
        assertEquals(InpaintBrushMode.ERASE, panel.brushMode)

        panel.hideOversizeWarning()
        assertEquals(View.GONE, warning.visibility)
    }

    private fun createOverlay(): InpaintBrushOverlayView =
        InpaintBrushOverlayView(RuntimeEnvironment.getApplication()).apply {
            frameAspect = 1.0f
            sourceWidth = 1000
            sourceHeight = 1000
            canvasTransform = CanvasTransform()
            layout(0, 0, 1000, 1000)
        }

    private fun single(
        overlay: InpaintBrushOverlayView,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent {
        @Suppress("UNUSED_PARAMETER")
        val now = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    private fun twoFingers(
        overlay: InpaintBrushOverlayView,
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float
    ): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = firstX
                y = firstY
                pressure = 1.0f
                size = 1.0f
            },
            MotionEvent.PointerCoords().apply {
                x = secondX
                y = secondY
                pressure = 1.0f
                size = 1.0f
            }
        )
        val action = MotionEvent.ACTION_POINTER_DOWN or
            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            now,
            now,
            action,
            2,
            properties,
            coordinates,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    private class RecordingListener : InpaintBrushOverlayView.Listener {
        var lastStrokes: List<InpaintStroke> = emptyList()
        var preempted = 0

        override fun onInpaintStrokesChanged(strokes: List<InpaintStroke>) {
            lastStrokes = strokes
        }

        override fun onInpaintTwoFingerPreempted() {
            preempted++
        }

        fun lastBbox(): Triple<Int, Int, Boolean>? {
            val bounds = lastStrokes.map { it.bounds(OVERLAY_SIZE, OVERLAY_SIZE) }
                .filterNot { it.isEmpty }
                .reduceOrNull { first, second -> first.union(second) }
                ?: return null
            return Triple(bounds.width, bounds.height, bounds.width > 256 || bounds.height > 256)
        }

        private companion object {
            const val OVERLAY_SIZE = 1000
        }
    }
}
