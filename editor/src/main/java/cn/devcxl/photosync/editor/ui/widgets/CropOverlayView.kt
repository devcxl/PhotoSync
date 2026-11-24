package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.hypot


import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.CropHandle
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.gesture.GestureRoute
import cn.devcxl.photosync.editor.ui.gesture.GestureRouter

/**
 * Interactive geometry overlay drawn on top of the GLES preview canvas.
 *
 * - **Crop**: four corners, four edges and the body are draggable, honouring the aspect-ratio lock.
 * - **Straighten**: a rule-of-thirds grid plus a horizontal drag that feeds the angle dial.
 * - **Perspective**: four pins dragged onto the subject corners.
 *
 * Two-finger gestures always preempt the active tool and are forwarded as canvas zoom/pan, and a
 * long press anywhere outside a drag target still performs Before/After comparison.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Receives geometry edits produced by the overlay.
     */
    interface Listener {
        fun onCropChanged(rect: CropRect) {}
        fun onCropChangeFinished() {}
        fun onPerspectiveChanged(quad: FloatArray) {}
        fun onPerspectiveChangeFinished() {}
        fun onStraightenDrag(deltaDegrees: Float) {}
        fun onStraightenDragFinished() {}
        fun onCanvasTransformChanged(transform: CanvasTransform) {}
        fun onCompareHold(active: Boolean) {}
    }

    private val painter = CropOverlayPainter(context)
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())

    var listener: Listener? = null

    /** Active geometry tool; drives both drawing and touch ownership. */
    var tool: EditorTool = EditorTool.NONE
        set(value) {
            if (field == value) return
            field = value
            cancelActiveDrag()
            invalidate()
        }

    /** Crop rectangle in the pre-crop normalized frame. */
    var cropRect: CropRect = CropRect()
        set(value) {
            field = value
            invalidate()
        }

    /** Perspective quad in the straightened normalized frame. */
    var perspectiveQuad: FloatArray = DEFAULT_QUAD.copyOf()
        set(value) {
            field = value.copyOf()
            invalidate()
        }

    /** Physical aspect ratio the user locked the crop to, or `null` for free cropping. */
    var lockedAspect: Float? = null

    /** Width/height ratio of the frame currently rendered by the GL canvas. */
    var frameAspect: Float = 1.0f
        set(value) {
            field = if (value.isFinite() && value > 0.0f) value else 1.0f
            refreshProjection(canvasTransform)
        }

    /** Current canvas zoom/pan state. */
    var canvasTransform: CanvasTransform = CanvasTransform()

    var group: EditorGroup = EditorGroup.GEOMETRY

    private val projection = OverlayProjection()

    private var activeHandle: CropHandle? = null
    private var activePin: Int = -1
    private var lastTouchX: Float = 0.0f
    private var lastTouchY: Float = 0.0f
    private var dragStarted: Boolean = false

    private var gestureStartTransform: CanvasTransform = CanvasTransform()
    private var gestureStartSpan: Float = 1.0f
    private var gestureStartFocusX: Float = 0.5f
    private var gestureStartFocusY: Float = 0.5f

    private val compareRunnable = Runnable { listener?.onCompareHold(true) }

    init {
        setWillNotDraw(false)
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshProjection(canvasTransform)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (projection.imageRect.isEmpty) return
        when (tool) {
            EditorTool.CROP -> {
                val cropFrame = projection.rectToPixels(cropRect)
                painter.drawScrim(canvas, cropFrame)
                painter.drawCropFrame(canvas, cropFrame, withHandles = true)
            }
            EditorTool.STRAIGHTEN -> painter.drawThirdsGrid(canvas, projection.rectToPixels(cropRect))
            EditorTool.PERSPECTIVE ->
                painter.drawPerspectiveQuad(canvas, perspectiveQuad, projection.imageRect)
            else -> Unit
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val route = GestureRouter.resolveGestureRoute(group, tool, event.pointerCount)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onPointerDown(event, route)
            MotionEvent.ACTION_POINTER_DOWN -> return onExtraPointerDown(event)
            MotionEvent.ACTION_MOVE -> return onPointerMove(event, route)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                return onPointerRelease(event)
            else -> return true
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onPointerDown(event: MotionEvent, route: GestureRoute): Boolean {
        mainHandler.removeCallbacks(compareRunnable)
        lastTouchX = event.x
        lastTouchY = event.y
        dragStarted = false
        if (route == GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE) {
            mainHandler.postDelayed(compareRunnable, COMPARE_HOLD_DELAY_MS)
            return true
        }
        val touchRadiusPx = TOUCH_RADIUS_DP * density
        when (tool) {
            EditorTool.CROP ->
                activeHandle = projection.hitTestCrop(cropRect, event.x, event.y, touchRadiusPx)
            EditorTool.PERSPECTIVE ->
                activePin = projection.hitTestPin(perspectiveQuad, event.x, event.y, touchRadiusPx)
            else -> Unit
        }
        return true
    }

    private fun onExtraPointerDown(event: MotionEvent): Boolean {
        mainHandler.removeCallbacks(compareRunnable)
        listener?.onCompareHold(false)
        finishActiveDrag()
        gestureStartTransform = canvasTransform
        gestureStartSpan = spanOf(event).coerceAtLeast(1.0f)
        gestureStartFocusX = focusXOf(event)
        gestureStartFocusY = focusYOf(event)
        return true
    }

    private fun onPointerMove(event: MotionEvent, route: GestureRoute): Boolean {
        if (route == GestureRoute.CANVAS_TWO_FINGER_PAN_ZOOM && event.pointerCount >= 2) {
            applyTwoFingerMove(event)
            return true
        }
        val delta = hypot(event.x - lastTouchX, event.y - lastTouchY)
        if (!dragStarted && delta < touchSlop) return true
        dragStarted = true
        mainHandler.removeCallbacks(compareRunnable)
        val stepX = event.x - lastTouchX
        val stepY = event.y - lastTouchY
        lastTouchX = event.x
        lastTouchY = event.y
        when {
            route == GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE && canvasTransform.scale > 1.0f ->
                panCanvas(stepX, stepY)
            tool == EditorTool.CROP -> dragCrop(stepX, stepY)
            tool == EditorTool.PERSPECTIVE -> dragPin(stepX, stepY)
            tool == EditorTool.STRAIGHTEN ->
                listener?.onStraightenDrag(-stepX / width.coerceAtLeast(1) * STRAIGHTEN_SWEEP_DEGREES)
        }
        return true
    }

    private fun onPointerRelease(event: MotionEvent): Boolean {
        mainHandler.removeCallbacks(compareRunnable)
        listener?.onCompareHold(false)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        finishActiveDrag()
        return true
    }

    private fun panCanvas(stepX: Float, stepY: Float) {
        val transformed = canvasTransform.pan(
            stepX / width.coerceAtLeast(1),
            -stepY / height.coerceAtLeast(1)
        )
        canvasTransform = transformed
        refreshProjection(transformed)
        listener?.onCanvasTransformChanged(transformed)
    }

    private fun applyTwoFingerMove(event: MotionEvent) {
        val span = spanOf(event).coerceAtLeast(1.0f)
        val focusX = focusXOf(event)
        val focusY = focusYOf(event)
        val transformed = gestureStartTransform
            .pinch(span / gestureStartSpan, focusX, focusY)
            .pan(focusX - gestureStartFocusX, focusY - gestureStartFocusY)
        canvasTransform = transformed
        refreshProjection(transformed)
        listener?.onCanvasTransformChanged(transformed)
    }

    private fun dragCrop(stepX: Float, stepY: Float) {
        val handle = activeHandle ?: return
        val updated = projection.dragCrop(
            cropRect = cropRect,
            handle = handle,
            stepX = stepX,
            stepY = stepY,
            lockedAspect = lockedAspect,
            frameAspect = frameAspect
        ) ?: return
        cropRect = updated
        listener?.onCropChanged(updated)
    }

    private fun dragPin(stepX: Float, stepY: Float) {
        if (activePin < 0) return
        val updated = projection.dragPin(perspectiveQuad, activePin, stepX, stepY) ?: return
        perspectiveQuad = updated
        listener?.onPerspectiveChanged(updated)
    }

    /**
     * Seals whichever drag is in flight so the host closes its undo coalescing window.
     */
    private fun finishActiveDrag() {
        if (activeHandle != null) {
            activeHandle = null
            listener?.onCropChangeFinished()
        }
        if (activePin >= 0) {
            activePin = -1
            listener?.onPerspectiveChangeFinished()
        }
        if (dragStarted) {
            dragStarted = false
            if (tool == EditorTool.STRAIGHTEN) listener?.onStraightenDragFinished()
        }
    }

    private fun cancelActiveDrag() {
        mainHandler.removeCallbacks(compareRunnable)
        finishActiveDrag()
    }

    private fun refreshProjection(transform: CanvasTransform) {
        projection.update(width, height, frameAspect, transform)
        invalidate()
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0.0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun focusXOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return event.x / width.coerceAtLeast(1)
        return ((event.getX(0) + event.getX(1)) / 2.0f) / width.coerceAtLeast(1)
    }

    private fun focusYOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return event.y / height.coerceAtLeast(1)
        return ((event.getY(0) + event.getY(1)) / 2.0f) / height.coerceAtLeast(1)
    }

    private companion object {
        private const val TOUCH_RADIUS_DP = 26.0f
        private const val COMPARE_HOLD_DELAY_MS = 420L
        private const val STRAIGHTEN_SWEEP_DEGREES = 90.0f
        private val DEFAULT_QUAD = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }
}
