package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.abs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform

/**
 * Two draggable horizontal guide lines that place the waist and hip of Body Reshaping.
 *
 * The lines live in normalized frame coordinates (`v = 0` at the top edge), so a zoom or pan keeps
 * them attached to the body, and dragging one writes straight into `EditGraph.portrait.body.waistY`
 * / `hipY` through [Listener.onGuidesChanged].
 *
 * A touch that does not land near a line is **not** consumed: it falls through to the crop/gesture
 * overlay underneath, which keeps pinch-zoom, pan and press-and-hold comparison working while the
 * Body panel is open.
 */
class BodyGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Receives guide-line edits. */
    interface Listener {
        /** Raised while a guide is dragged, with both lines already clamped. */
        fun onGuidesChanged(waistY: Float, hipY: Float)

        /** Raised when the guide drag ends, so the host seals its undo step. */
        fun onGuidesChangeFinished()
    }

    /** Listener notified on guide changes and release. */
    var listener: Listener? = null

    /** Normalized waist line, `0` top edge / `1` bottom edge. */
    var waistY: Float = DEFAULT_WAIST_Y
        set(value) {
            field = value
            invalidate()
        }

    /** Normalized hip line, always below [waistY]. */
    var hipY: Float = DEFAULT_HIP_Y
        set(value) {
            field = value
            invalidate()
        }

    /** Width/height ratio of the frame the GL canvas is letterboxing. */
    var frameAspect: Float = 1.0f
        set(value) {
            field = if (value.isFinite() && value > 0.0f) value else 1.0f
            projection.update(width, height, field, canvasTransform)
            invalidate()
        }

    /** Canvas zoom/pan state, mirrored from the crop overlay. */
    var canvasTransform: CanvasTransform = CanvasTransform()
        set(value) {
            field = value
            projection.update(width, height, frameAspect, value)
            invalidate()
        }

    private val projection = OverlayProjection()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_accent_blue)
        strokeWidth = LINE_WIDTH_DP * resources.displayMetrics.density
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_glass_bg)
        strokeWidth = HALO_WIDTH_DP * resources.displayMetrics.density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_text_primary)
        style = Paint.Style.FILL
    }
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var dragging: Guide? = null
    private var startY: Float = 0.0f
    private var startGuideY: Float = 0.0f
    private var moved: Boolean = false

    init {
        setWillNotDraw(false)
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        projection.update(w, h, frameAspect, canvasTransform)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (projection.imageRect.isEmpty) return
        drawGuide(canvas, waistY)
        drawGuide(canvas, hipY)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= TWO_FINGERS) {
            finishDrag()
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onUp()
            else -> dragging != null
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onDown(event: MotionEvent): Boolean {
        val guide = hitTest(event.y) ?: return false
        dragging = guide
        moved = false
        startY = event.y
        startGuideY = guideY(guide)
        return true
    }

    private fun onMove(event: MotionEvent): Boolean {
        val guide = dragging ?: return false
        val delta = event.y - startY
        if (!moved && abs(delta) < touchSlop) return true
        moved = true
        val height = projection.imageRect.height()
        if (height <= 0.0f) return true
        val target = startGuideY + delta / height
        apply(guide, target)
        return true
    }

    private fun onUp(): Boolean {
        val wasDragging = dragging != null
        finishDrag()
        if (wasDragging) performClick()
        return wasDragging
    }

    private fun finishDrag() {
        if (dragging == null) return
        val changed = moved
        dragging = null
        moved = false
        if (changed) listener?.onGuidesChangeFinished()
    }

    private fun apply(guide: Guide, target: Float) {
        when (guide) {
            Guide.WAIST -> waistY = target.coerceIn(MIN_WAIST, hipY - MIN_GAP)
            Guide.HIP -> hipY = target.coerceIn(waistY + MIN_GAP, MAX_HIP)
        }
        listener?.onGuidesChanged(waistY, hipY)
    }

    private fun guideY(guide: Guide): Float = if (guide == Guide.WAIST) waistY else hipY

    private fun hitTest(y: Float): Guide? {
        if (projection.imageRect.isEmpty) return null
        val radius = TOUCH_RADIUS_DP * density
        val waistDistance = abs(y - projection.yOf(waistY))
        val hipDistance = abs(y - projection.yOf(hipY))
        val closest = minOf(waistDistance, hipDistance)
        if (closest > radius) return null
        return if (waistDistance <= hipDistance) Guide.WAIST else Guide.HIP
    }

    private fun drawGuide(canvas: Canvas, v: Float) {
        val rect: RectF = projection.imageRect
        val y = projection.yOf(v)
        canvas.drawLine(rect.left, y, rect.right, y, haloPaint)
        canvas.drawLine(rect.left, y, rect.right, y, linePaint)
        val centreX = rect.left + rect.width() / 2.0f
        canvas.drawCircle(centreX, y, KNOB_RADIUS_DP * density, knobPaint)
        canvas.drawCircle(centreX, y, KNOB_RADIUS_DP * density * 0.55f, linePaint)
    }

    private enum class Guide { WAIST, HIP }

    private companion object {
        private const val DEFAULT_WAIST_Y = 0.55f
        private const val DEFAULT_HIP_Y = 0.66f
        private const val MIN_WAIST = 0.2f
        private const val MAX_HIP = 0.9f
        private const val MIN_GAP = 0.03f
        private const val TWO_FINGERS = 2
        private const val TOUCH_RADIUS_DP = 24.0f
        private const val LINE_WIDTH_DP = 1.5f
        private const val HALO_WIDTH_DP = 5.0f
        private const val KNOB_RADIUS_DP = 7.0f
    }
}
