package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.hypot

import android.view.MotionEvent

import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform

/**
 * Tracks the two-finger zoom/pan gesture of the P2 brush overlay.
 *
 * The brush view deliberately does not use the shared gesture router: it must keep owning the
 * single-finger stroke so it can discard exactly that stroke when the second finger lands. This
 * small helper keeps the pinch arithmetic out of the view's touch switch.
 */
internal class InpaintPinchTracker {

    /** True while two fingers own the canvas. */
    var isActive: Boolean = false
        private set

    private var startTransform = CanvasTransform()
    private var startSpan = 1.0f
    private var startFocusX = 0.5f
    private var startFocusY = 0.5f

    /** Starts a pinch from the current canvas state. */
    fun begin(transform: CanvasTransform, event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        isActive = true
        startTransform = transform
        startSpan = spanOf(event).coerceAtLeast(1.0f)
        startFocusX = focusXOf(event, viewWidth)
        startFocusY = focusYOf(event, viewHeight)
    }

    /** Applies the current event relative to the gesture start. */
    fun update(event: MotionEvent, viewWidth: Int, viewHeight: Int): CanvasTransform {
        if (!isActive) return startTransform
        val span = spanOf(event).coerceAtLeast(1.0f)
        val focusX = focusXOf(event, viewWidth)
        val focusY = focusYOf(event, viewHeight)
        return startTransform
            .pinch(span / startSpan, focusX, focusY)
            .pan(focusX - startFocusX, focusY - startFocusY)
    }

    /** Clears the gesture state without producing a transform. */
    fun reset() {
        isActive = false
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < TWO_FINGERS) return 0.0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun focusXOf(event: MotionEvent, viewWidth: Int): Float {
        if (event.pointerCount < TWO_FINGERS) return 0.5f
        return ((event.getX(0) + event.getX(1)) / 2.0f) / viewWidth.coerceAtLeast(1)
    }

    private fun focusYOf(event: MotionEvent, viewHeight: Int): Float {
        if (event.pointerCount < TWO_FINGERS) return 0.5f
        return ((event.getY(0) + event.getY(1)) / 2.0f) / viewHeight.coerceAtLeast(1)
    }

    private companion object {
        const val TWO_FINGERS = 2
    }
}
