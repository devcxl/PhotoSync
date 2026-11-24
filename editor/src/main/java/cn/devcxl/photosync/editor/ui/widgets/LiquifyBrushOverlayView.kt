package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.hypot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.GestureRouter
import cn.devcxl.photosync.editor.ui.gesture.LiquifyPreemption
import cn.devcxl.photosync.editor.ui.gesture.LiquifyStrokeArbiter

/**
 * Interactive liquify canvas layer drawn over the GLES preview.
 *
 * A single finger paints displacement stamps, two fingers always pinch/pan the canvas, and the
 * two-finger preemption rule decides what happens to a stroke that is already in flight: below the
 * 8dp slop the stroke is rolled back so "zoom, don't smear" holds, at or above it the stroke is kept
 * and committed. A double tap clears every displacement.
 *
 * Only the liquify tool installs this view; every other tool leaves the canvas to
 * [CropOverlayView].
 */
class LiquifyBrushOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Receives liquify stroke and canvas gestures.
     */
    interface Listener {
        /** A stroke started; the host snapshots the mesh for rollback. */
        fun onLiquifyStrokeBegin() {}

        /** The finger moved from [prevUv] to [nextUv] in normalized frame coordinates. */
        fun onLiquifyStrokeMove(prevUv: Uv, nextUv: Uv) {}

        /** The stroke ended; [committed] is false when it must be rolled back. */
        fun onLiquifyStrokeFinished(committed: Boolean) {}

        /** Two fingers changed the canvas zoom/pan. */
        fun onLiquifyCanvasTransformChanged(transform: CanvasTransform) {}

        /** The user double-tapped to clear the liquify edit. */
        fun onLiquifyResetRequested() {}
    }

    /** Stroke and gesture callbacks. */
    var listener: Listener? = null

    /** Width/height ratio of the frame the GL canvas currently renders. */
    var frameAspect: Float = 1.0f
        set(value) {
            field = if (value.isFinite() && value > 0.0f) value else 1.0f
            refreshProjection()
        }

    /** Current canvas zoom/pan state. */
    var canvasTransform: CanvasTransform = CanvasTransform()

    /** Brush radius in normalized horizontal frame units. */
    var brushRadiusNorm: Float = 0.08f
        set(value) {
            field = if (value.isFinite() && value > 0.0f) value else 0.08f
            invalidate()
        }

    /** True when the restore (eraser) mode is armed, which only changes the ring colour. */
    var restoring: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val painter = LiquifyBrushPainter(context)
    private val projection = OverlayProjection()
    private val arbiter = LiquifyStrokeArbiter(
        GestureRouter.preemptionSlopPx(resources.displayMetrics.density)
    )

    private var strokeActive: Boolean = false
    private var pinchActive: Boolean = false
    private var brushCentre: Uv? = null
    private var lastTouchUv: Uv = Uv(0.5f, 0.5f)
    private var lastTouchPixelX: Float = 0.0f
    private var lastTouchPixelY: Float = 0.0f

    private var gestureStartTransform: CanvasTransform = CanvasTransform()
    private var gestureStartSpan: Float = 1.0f
    private var gestureStartFocusX: Float = 0.5f
    private var gestureStartFocusY: Float = 0.5f

    private var lastTapUpTimeMs: Long = 0L
    private var lastTapX: Float = 0.0f
    private var lastTapY: Float = 0.0f

    private val doubleTapSlopPx: Float
        get() = DOUBLE_TAP_SLOP_DP * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshProjection()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centre = brushCentre ?: return
        if (projection.imageRect.isEmpty) return
        painter.drawBrush(
            canvas = canvas,
            centreX = projection.xOf(centre.u),
            centreY = projection.yOf(centre.v),
            radiusPx = brushRadiusNorm * projection.imageRect.width(),
            restoring = restoring
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onPointerDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> return onExtraPointerDown(event)
            MotionEvent.ACTION_MOVE -> return onPointerMove(event)
            MotionEvent.ACTION_POINTER_UP -> return onPointerUp(event)
            MotionEvent.ACTION_UP -> return onPointerRelease(event)
            MotionEvent.ACTION_CANCEL -> return onGestureCancelled()
            else -> return true
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onPointerDown(event: MotionEvent): Boolean {
        if (projection.imageRect.isEmpty || !projection.imageRect.contains(event.x, event.y)) {
            return false
        }
        val uv = uvOf(event)
        lastTouchUv = uv
        lastTouchPixelX = event.x
        lastTouchPixelY = event.y
        brushCentre = uv
        strokeActive = true
        arbiter.beginStroke()
        listener?.onLiquifyStrokeBegin()
        invalidate()
        return true
    }

    private fun onExtraPointerDown(event: MotionEvent): Boolean {
        when (arbiter.onSecondPointerDown()) {
            LiquifyPreemption.ROLL_BACK_UNCOMMITTED_STROKE ->
                finishStroke(committed = false, notify = strokeActive)
            LiquifyPreemption.KEEP_STROKE -> finishStroke(committed = true, notify = strokeActive)
        }
        strokeActive = false
        beginPinch(event)
        return true
    }

    private fun onPointerMove(event: MotionEvent): Boolean {
        if (pinchActive && event.pointerCount >= TWO_FINGERS) {
            updatePinch(event)
            return true
        }
        if (!strokeActive) return true
        val nextUv = uvOf(event)
        arbiter.onMove(hypot(event.x - lastTouchPixelX, event.y - lastTouchPixelY))
        lastTouchPixelX = event.x
        lastTouchPixelY = event.y
        if (!nextUv.u.isFinite() || !nextUv.v.isFinite()) return true
        listener?.onLiquifyStrokeMove(lastTouchUv, nextUv)
        lastTouchUv = nextUv
        brushCentre = nextUv
        invalidate()
        return true
    }

    private fun onPointerUp(event: MotionEvent): Boolean {
        if (pinchActive && event.pointerCount - 1 < TWO_FINGERS) {
            pinchActive = false
            brushCentre = null
            invalidate()
        }
        return true
    }

    private fun onPointerRelease(event: MotionEvent): Boolean {
        val wasStroke = strokeActive
        val wasPinching = pinchActive
        val committed = wasStroke && arbiter.endStroke()
        finishStroke(committed = committed, notify = wasStroke)
        strokeActive = false
        pinchActive = false
        if (!committed && !wasPinching && arbiter.travelPx < arbiterSlopPx()) {
            detectDoubleTapReset(event)
        }
        performClick()
        brushCentre = null
        invalidate()
        return true
    }

    private fun onGestureCancelled(): Boolean {
        finishStroke(committed = false, notify = strokeActive)
        strokeActive = false
        pinchActive = false
        arbiter.cancel()
        brushCentre = null
        invalidate()
        return true
    }

    private fun finishStroke(committed: Boolean, notify: Boolean) {
        if (notify) listener?.onLiquifyStrokeFinished(committed)
    }

    private fun beginPinch(event: MotionEvent) {
        pinchActive = true
        gestureStartTransform = canvasTransform
        gestureStartSpan = spanOf(event).coerceAtLeast(1.0f)
        gestureStartFocusX = focusXOf(event)
        gestureStartFocusY = focusYOf(event)
    }

    private fun updatePinch(event: MotionEvent) {
        val span = spanOf(event).coerceAtLeast(1.0f)
        val focusX = focusXOf(event)
        val focusY = focusYOf(event)
        val transformed = gestureStartTransform
            .pinch(span / gestureStartSpan, focusX, focusY)
            .pan(focusX - gestureStartFocusX, focusY - gestureStartFocusY)
        canvasTransform = transformed
        refreshProjection()
        listener?.onLiquifyCanvasTransformChanged(transformed)
    }

    private fun detectDoubleTapReset(event: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        val withinWindow = now - lastTapUpTimeMs <= DOUBLE_TAP_TIMEOUT_MS
        val nearPrevious = hypot(event.x - lastTapX, event.y - lastTapY) <= doubleTapSlopPx
        if (withinWindow && nearPrevious) {
            lastTapUpTimeMs = 0L
            listener?.onLiquifyResetRequested()
            return
        }
        lastTapUpTimeMs = now
        lastTapX = event.x
        lastTapY = event.y
    }

    private fun arbiterSlopPx(): Float =
        GestureRouter.preemptionSlopPx(resources.displayMetrics.density)

    private fun uvOf(event: MotionEvent): Uv =
        Uv(projection.uOf(event.x), projection.vOf(event.y))

    private fun refreshProjection() {
        projection.update(width, height, frameAspect, canvasTransform)
        invalidate()
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < TWO_FINGERS) return 0.0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun focusXOf(event: MotionEvent): Float {
        if (event.pointerCount < TWO_FINGERS) return event.x / width.coerceAtLeast(1)
        return ((event.getX(0) + event.getX(1)) / 2.0f) / width.coerceAtLeast(1)
    }

    private fun focusYOf(event: MotionEvent): Float {
        if (event.pointerCount < TWO_FINGERS) return event.y / height.coerceAtLeast(1)
        return ((event.getY(0) + event.getY(1)) / 2.0f) / height.coerceAtLeast(1)
    }

    private companion object {
        private const val TWO_FINGERS = 2
        private const val DOUBLE_TAP_TIMEOUT_MS = 320L
        private const val DOUBLE_TAP_SLOP_DP = 40.0f
    }
}
