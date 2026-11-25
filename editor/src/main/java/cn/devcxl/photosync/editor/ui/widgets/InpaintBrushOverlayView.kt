package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.hypot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer
import cn.devcxl.photosync.editor.domain.InpaintStrokePoint
import cn.devcxl.photosync.editor.ui.INPAINT_OVERSIZE_LIMIT_PX
import cn.devcxl.photosync.editor.ui.InpaintBrushCanvasHost
import cn.devcxl.photosync.editor.ui.InpaintBrushMode
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform

/**
 * Canvas layer of the P2 inpainting brush.
 *
 * Single finger paints a red translucent stroke (or erases with the eraser). A second finger
 * immediately **discards the in-flight stroke** and takes over zoom/pan, so "pinch, don't smear"
 * holds and an aborted stroke can never reach `EditGraph`. The view reports the painted bbox in
 * source pixels after every sample, which is what drives the amber >256 px advice.
 *
 * Only the inpainting tool installs this view; the committed strokes live in [strokeLayer] so the
 * controller and the inference run share one source of truth.
 */
class InpaintBrushOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), InpaintBrushCanvasHost {

    /**
     * Callbacks raised while the user paints or pinches.
     */
    interface Listener {
        /** Painted bbox changed; `0 x 0` means nothing is painted any more. */
        fun onBboxEstimated(widthPx: Int, heightPx: Int, isOversized: Boolean) {}

        /** A second finger discarded the in-flight stroke and took over the canvas. */
        fun onInpaintTwoFingerPreempted() {}

        /** Two fingers changed the preview zoom/pan. */
        fun onInpaintCanvasTransformChanged(transform: CanvasTransform) {}
    }

    /** Stroke, bbox and gesture callbacks. */
    var listener: Listener? = null

    /** Width/height ratio of the image the GL preview renders. */
    var frameAspect: Float = 1.0f
        set(value) {
            field = if (value.isFinite() && value > 0.0f) value else 1.0f
            refreshProjection()
        }

    /** Current preview zoom/pan, shared with the GL canvas. */
    var canvasTransform: CanvasTransform = CanvasTransform()
        set(value) {
            field = value
            refreshProjection()
        }

    override var brushMode: InpaintBrushMode = InpaintBrushMode.PAINT
        set(value) {
            field = value
            invalidate()
        }

    override var brushRadiusNorm: Float = DEFAULT_BRUSH_RADIUS
        set(value) {
            field = if (value.isFinite()) value.coerceIn(MIN_BRUSH_RADIUS, MAX_BRUSH_RADIUS)
            else DEFAULT_BRUSH_RADIUS
            invalidate()
        }

    override var sourceWidth: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            notifyBounds()
        }

    override var sourceHeight: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            notifyBounds()
        }

    override val strokeLayer: InpaintStrokeLayer = InpaintStrokeLayer()

    private val projection = OverlayProjection()
    private val painter = InpaintBrushPainter(context)
    private val pinchTracker = InpaintPinchTracker()

    private var activePoints: MutableList<InpaintStrokePoint>? = null
    private var activeStrokeView: InpaintStroke? = null
    private var committedStrokes: List<InpaintStroke> = emptyList()
    private var activeErase: Boolean = false
    private var brushCentre: InpaintStrokePoint? = null
    private var lastSampleX: Float = 0.0f
    private var lastSampleY: Float = 0.0f

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
        painter.draw(
            canvas = canvas,
            viewWidth = width,
            viewHeight = height,
            projection = projection.imageRect,
            strokes = committedStrokes,
            activeStroke = activeStrokeView,
            mode = brushMode,
            brushCentre = brushCentre,
            brushRadiusNorm = brushRadiusNorm
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onExtraPointerDown(event)
            MotionEvent.ACTION_MOVE -> onPointerMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onPointerRelease(event)
            MotionEvent.ACTION_CANCEL -> onGestureCancelled()
            else -> true
        }
    }

    override fun performClick(): Boolean = super.performClick()

    /** Discards the in-flight stroke; committed strokes are untouched. */
    override fun abortActiveStroke() {
        if (activePoints == null) return
        activePoints = null
        activeStrokeView = null
        brushCentre = null
        invalidate()
        notifyBounds()
    }

    /** Drops every committed stroke and any in-flight one. */
    override fun clearStrokes() {
        strokeLayer.clear()
        committedStrokes = emptyList()
        activePoints = null
        activeStrokeView = null
        brushCentre = null
        invalidate()
        notifyBounds()
    }

    private fun onPointerDown(event: MotionEvent): Boolean {
        if (projection.imageRect.isEmpty || !projection.imageRect.contains(event.x, event.y)) {
            return false
        }
        val point = uvOf(event)
        val points = mutableListOf(point)
        activePoints = points
        activeStrokeView = InpaintStroke(
            erase = brushMode == InpaintBrushMode.ERASE,
            radiusNorm = brushRadiusNorm,
            points = points
        )
        activeErase = brushMode == InpaintBrushMode.ERASE
        brushCentre = point
        lastSampleX = event.x
        lastSampleY = event.y
        notifyBounds()
        invalidate()
        return true
    }

    private fun onExtraPointerDown(event: MotionEvent): Boolean {
        val hadStroke = activePoints != null
        abortActiveStroke()
        if (hadStroke) listener?.onInpaintTwoFingerPreempted()
        pinchTracker.begin(canvasTransform, event, width, height)
        return true
    }

    private fun onPointerMove(event: MotionEvent): Boolean {
        if (pinchTracker.isActive && event.pointerCount >= TWO_FINGERS) {
            val transformed = pinchTracker.update(event, width, height)
            canvasTransform = transformed
            listener?.onInpaintCanvasTransformChanged(transformed)
            return true
        }
        val points = activePoints ?: return true
        if (hypot(event.x - lastSampleX, event.y - lastSampleY) < minSampleDistancePx()) return true
        lastSampleX = event.x
        lastSampleY = event.y
        val point = uvOf(event)
        points.add(point)
        brushCentre = point
        notifyBounds()
        invalidate()
        return true
    }

    private fun onPointerUp(event: MotionEvent): Boolean {
        if (pinchTracker.isActive && event.pointerCount - 1 < TWO_FINGERS) {
            pinchTracker.reset()
            brushCentre = null
            invalidate()
        }
        return true
    }

    private fun onPointerRelease(event: MotionEvent): Boolean {
        commitActiveStroke()
        pinchTracker.reset()
        brushCentre = null
        performClick()
        invalidate()
        return true
    }

    private fun onGestureCancelled(): Boolean {
        abortActiveStroke()
        pinchTracker.reset()
        brushCentre = null
        invalidate()
        return true
    }

    private fun commitActiveStroke() {
        val points = activePoints ?: return
        activePoints = null
        activeStrokeView = null
        if (points.isEmpty()) return
        strokeLayer.add(
            InpaintStroke(
                erase = activeErase,
                radiusNorm = brushRadiusNorm,
                points = points.toList()
            )
        )
        committedStrokes = strokeLayer.snapshot()
        notifyBounds()
    }

    private fun notifyBounds() {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            listener?.onBboxEstimated(0, 0, false)
            return
        }
        val committed = strokeLayer.bounds(sourceWidth, sourceHeight)
        val active = activeStrokeView?.bounds(sourceWidth, sourceHeight)
        val bounds = listOfNotNull(committed, active).reduceOrNull { first, second ->
            first.union(second)
        }
        if (bounds == null || bounds.isEmpty) {
            listener?.onBboxEstimated(0, 0, false)
            return
        }
        val oversized = bounds.width > INPAINT_OVERSIZE_LIMIT_PX ||
            bounds.height > INPAINT_OVERSIZE_LIMIT_PX
        listener?.onBboxEstimated(bounds.width, bounds.height, oversized)
    }

    private fun uvOf(event: MotionEvent): InpaintStrokePoint =
        InpaintStrokePoint(projection.uOf(event.x), projection.vOf(event.y))

    private fun refreshProjection() {
        projection.update(width, height, frameAspect, canvasTransform)
        invalidate()
    }

    private fun minSampleDistancePx(): Float = MIN_SAMPLE_DISTANCE_DP * resources.displayMetrics.density

    companion object {
        /** Brush radius as a fraction of the source width: a 4% diameter default. */
        const val DEFAULT_BRUSH_RADIUS = 0.02f

        /** Smallest brush: a 0.8% diameter, enough for a single small object. */
        const val MIN_BRUSH_RADIUS = 0.004f

        /** Largest brush: a 16% diameter; larger strokes would inevitably be tiled. */
        const val MAX_BRUSH_RADIUS = 0.08f

        private const val TWO_FINGERS = 2
        private const val MIN_SAMPLE_DISTANCE_DP = 1.5f
    }
}
