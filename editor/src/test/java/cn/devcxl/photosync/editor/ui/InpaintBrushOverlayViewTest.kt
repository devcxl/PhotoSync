package cn.devcxl.photosync.editor.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintTileEta
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.InpaintBrushOverlayView
import cn.devcxl.photosync.editor.ui.widgets.InpaintPanelView
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Seam under test: the two P2 brush views. Robolectric dispatches real `MotionEvent`s so the
 * two-finger preemption is verified against the actual touch code, and drives the real panel to
 * check the source-pixel brush, quick presets, quality chips and the workload line.
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
    }

    @Test
    fun shouldKeepAPaintedStroke_whenTheBboxExceedsTheOld256PxAdvice() {
        val overlay = createOverlay()
        val listener = RecordingListener()
        overlay.listener = listener

        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 100f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_MOVE, 900f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_UP, 900f, 500f))

        val bbox = checkNotNull(listener.lastBbox())
        assertTrue("bbox width ${bbox.first} must exceed 256", bbox.first > 256)
        assertEquals(1, overlay.strokeLayer.strokeCount)
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
    fun shouldReplaceStrokesAndNotKeepTheInFlightOne_whenTheControllerShrinksThem() {
        val overlay = createOverlay()
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 500f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_UP, 500f, 500f))
        overlay.dispatchTouchEvent(single(overlay, MotionEvent.ACTION_DOWN, 700f, 500f))

        overlay.replaceStrokes(emptyList())

        assertTrue(overlay.strokeLayer.isEmpty)
    }

    @Test
    fun shouldExposePixelDiameterQuickSizesQualityAndWorkload_whenThePanelIsDriven() {
        val panel = InpaintPanelView(RuntimeEnvironment.getApplication())
        panel.layout(0, 0, 1080, 480)
        val listener = RecordingPanelListener()
        panel.listener = listener
        val value = panel.findViewById<TextView>(R.id.inpaintBrushValue)
        val hint = panel.findViewById<TextView>(R.id.inpaintWorkloadHint)
        val note = panel.findViewById<TextView>(R.id.inpaintWorkloadNote)

        panel.setBrushDiameterPx(64)
        assertEquals("64 px", value.text.toString())

        panel.findViewById<TextView>(R.id.inpaintBrushLargeChip).performClick()
        assertEquals(InpaintBrushSize.LARGE_PRESET_PX, listener.diameterPx)
        assertEquals(InpaintBrushSize.LARGE_PRESET_PX, panel.brushDiameterPx)
        assertEquals("160 px", value.text.toString())

        panel.findViewById<TextView>(R.id.inpaintBrushMediumChip).performClick()
        assertEquals(InpaintBrushSize.MEDIUM_PRESET_PX, panel.brushDiameterPx)

        panel.setQualityMode(InpaintQualityMode.FINE)
        panel.findViewById<TextView>(R.id.inpaintQualityFastChip).performClick()
        assertEquals(InpaintQualityMode.FAST, listener.qualityMode)
        assertEquals(InpaintQualityMode.FAST, panel.qualityMode)

        panel.setBrushDiameterPx(InpaintBrushSize.MAX_DIAMETER_PX + 400)
        assertEquals(InpaintBrushSize.MAX_DIAMETER_PX, panel.brushDiameterPx)
        assertEquals("1200 px", value.text.toString())

        panel.setWorkload(InpaintTileEta.of(12, DeviceMemoryTier.STANDARD, null))
        assertEquals(View.VISIBLE, hint.visibility)
        assertTrue(hint.text.toString().contains("12"))
        assertEquals(View.GONE, note.visibility)

        panel.setWorkload(InpaintTileEta.of(12, DeviceMemoryTier.LOW, null))
        assertEquals(View.VISIBLE, note.visibility)

        panel.setWorkload(null)
        assertEquals(View.GONE, hint.visibility)
        assertEquals(View.GONE, note.visibility)
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

        fun lastBbox(): Pair<Int, Int>? {
            val bounds = lastStrokes.map { it.bounds(OVERLAY_SIZE, OVERLAY_SIZE) }
                .filterNot { it.isEmpty }
                .reduceOrNull { first, second -> first.union(second) }
                ?: return null
            return bounds.width to bounds.height
        }

        private companion object {
            const val OVERLAY_SIZE = 1000
        }
    }

    private class RecordingPanelListener : InpaintPanelView.Listener {
        var diameterPx: Int? = null
        var qualityMode: InpaintQualityMode? = null

        override fun onInpaintModeChanged(mode: InpaintBrushMode) = Unit

        override fun onInpaintBrushDiameterChanged(diameterPx: Int) {
            this.diameterPx = diameterPx
        }

        override fun onInpaintQualityChanged(mode: InpaintQualityMode) {
            qualityMode = mode
        }

        override fun onInpaintClearRequested() = Unit

        override fun onInpaintConfirmRequested() = Unit
    }
}
