package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.hypot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.CurveChannel
import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.CurvePoint
import cn.devcxl.photosync.editor.domain.MonotoneCubicCurve

/**
 * Interactive curve grid canvas supporting knot creation, dragging and drag-below-grid deletion
 * (`2..16` knots, minimum horizontal spacing `1/256`).
 */
internal class CurveCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onChannelPointsChanged(channel: CurveChannel, points: List<CurvePoint>)
        fun onStopTrackingTouch()
    }

    var listener: Listener? = null

    private var curveOps: CurveOps = CurveOps()
    private var activeChannel: CurveChannel = CurveChannel.RGB
    private var activePoints: MutableList<CurvePoint> = CurveOps.DEFAULT_LINEAR_CURVE.toMutableList()
    private var draggingIndex: Int = -1
    private var pendingDelete: Boolean = false

    private val density = resources.displayMetrics.density
    private val gridRect = RectF()
    private val curvePath = Path()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_overlay_grid)
        strokeWidth = 1.0f * density
        style = Paint.Style.STROKE
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_text_muted)
        strokeWidth = 1.0f * density
        style = Paint.Style.STROKE
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }
    private val inactiveCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
        alpha = 96
    }
    private val knotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_text_primary)
        style = Paint.Style.FILL
    }
    private val knotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.0f * density
        style = Paint.Style.STROKE
    }
    private val deleteFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_curve_delete)
        style = Paint.Style.FILL
    }

    fun bindState(ops: CurveOps, channel: CurveChannel) {
        curveOps = ops
        activeChannel = channel
        if (draggingIndex == -1) {
            activePoints = pointsFor(ops, channel).toMutableList()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGridRect()
        drawGridAndBaseline(canvas)
        CurveChannel.entries.forEach { ch ->
            if (ch != activeChannel) {
                drawCompiledCurve(canvas, pointsFor(curveOps, ch), channelColor(ch), inactiveCurvePaint)
            }
        }
        val previewPoints = if (pendingDelete && draggingIndex in activePoints.indices) {
            activePoints.filterIndexed { index, _ -> index != draggingIndex }
        } else {
            activePoints
        }
        drawCompiledCurve(canvas, previewPoints, channelColor(activeChannel), curvePaint)
        drawKnots(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateGridRect()
        if (gridRect.width() <= 1.0f || gridRect.height() <= 1.0f) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> handleMove(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleRelease()
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleDown(x: Float, y: Float): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val hitRadius = HIT_RADIUS_DP * density
        val hitIndex = activePoints.indices.minByOrNull { idx ->
            val px = toScreenX(activePoints[idx].x)
            val py = toScreenY(activePoints[idx].y)
            hypot(x - px, y - py)
        }?.takeIf { idx ->
            hypot(x - toScreenX(activePoints[idx].x), y - toScreenY(activePoints[idx].y)) <= hitRadius
        }

        if (hitIndex != null) {
            draggingIndex = hitIndex
            pendingDelete = false
            invalidate()
            return true
        }
        if (activePoints.size >= MonotoneCubicCurve.MAX_POINTS) return true

        val nx = toNormX(x)
        val ny = toNormY(y)
        val tooClose = activePoints.any { kotlin.math.abs(it.x - nx) < MonotoneCubicCurve.MIN_SPACING }
        if (tooClose) return true

        val insertAt = activePoints.indexOfFirst { it.x > nx }.let { if (it == -1) activePoints.size else it }
        activePoints.add(insertAt, CurvePoint(nx, ny))
        draggingIndex = insertAt
        pendingDelete = false
        notifyPointsChanged()
        invalidate()
        return true
    }

    private fun handleMove(x: Float, y: Float): Boolean {
        if (draggingIndex !in activePoints.indices) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        if (activePoints.size > MonotoneCubicCurve.MIN_POINTS && y > gridRect.bottom + DELETE_SLOP_DP * density) {
            pendingDelete = true
            invalidate()
            return true
        }
        pendingDelete = false
        val idx = draggingIndex
        val minX = if (idx == 0) 0.0f else activePoints[idx - 1].x + MonotoneCubicCurve.MIN_SPACING
        val maxX = if (idx == activePoints.lastIndex) 1.0f else {
            activePoints[idx + 1].x - MonotoneCubicCurve.MIN_SPACING
        }
        val nx = toNormX(x).coerceIn(minX, maxX.coerceAtLeast(minX))
        val ny = toNormY(y)
        activePoints[idx] = CurvePoint(nx, ny)
        notifyPointsChanged()
        invalidate()
        return true
    }

    private fun handleRelease(): Boolean {
        if (draggingIndex != -1) {
            if (pendingDelete && activePoints.size > MonotoneCubicCurve.MIN_POINTS) {
                activePoints.removeAt(draggingIndex)
                notifyPointsChanged()
            }
            draggingIndex = -1
            pendingDelete = false
            invalidate()
            listener?.onStopTrackingTouch()
        }
        performClick()
        return true
    }

    private fun notifyPointsChanged() {
        val clean = MonotoneCubicCurve.sanitizePoints(activePoints)
        listener?.onChannelPointsChanged(activeChannel, clean)
    }

    private fun updateGridRect() {
        val pad = GRID_PAD_DP * density
        gridRect.set(
            pad,
            pad,
            (width - pad).coerceAtLeast(pad + 1.0f),
            (height - pad).coerceAtLeast(pad + 1.0f)
        )
    }

    private fun drawGridAndBaseline(canvas: Canvas) {
        canvas.drawRect(gridRect, gridPaint)
        for (step in 1..3) {
            val f = step / 4.0f
            val gx = gridRect.left + gridRect.width() * f
            val gy = gridRect.top + gridRect.height() * f
            canvas.drawLine(gx, gridRect.top, gx, gridRect.bottom, gridPaint)
            canvas.drawLine(gridRect.left, gy, gridRect.right, gy, gridPaint)
        }
        canvas.drawLine(gridRect.left, gridRect.bottom, gridRect.right, gridRect.top, baselinePaint)
    }

    private fun drawCompiledCurve(canvas: Canvas, points: List<CurvePoint>, color: Int, paint: Paint) {
        val compiled = MonotoneCubicCurve.compile(points)
        curvePath.reset()
        for (i in 0..PLOT_STEPS) {
            val x = i.toFloat() / PLOT_STEPS.toFloat()
            val y = compiled.evaluate(x)
            val sx = toScreenX(x)
            val sy = toScreenY(y)
            if (i == 0) curvePath.moveTo(sx, sy) else curvePath.lineTo(sx, sy)
        }
        paint.color = color
        canvas.drawPath(curvePath, paint)
    }

    private fun drawKnots(canvas: Canvas) {
        knotStrokePaint.color = channelColor(activeChannel)
        activePoints.forEachIndexed { idx, pt ->
            val sx = toScreenX(pt.x)
            val sy = toScreenY(pt.y)
            val isDeleting = pendingDelete && idx == draggingIndex
            val radius = if (idx == draggingIndex) KNOT_ACTIVE_RADIUS_DP * density else KNOT_RADIUS_DP * density
            canvas.drawCircle(sx, sy, radius, if (isDeleting) deleteFillPaint else knotFillPaint)
            canvas.drawCircle(sx, sy, radius, knotStrokePaint)
        }
    }

    private fun toScreenX(nx: Float): Float = gridRect.left + nx * gridRect.width()
    private fun toScreenY(ny: Float): Float = gridRect.bottom - ny * gridRect.height()
    private fun toNormX(sx: Float): Float = ((sx - gridRect.left) / gridRect.width()).coerceIn(0.0f, 1.0f)
    private fun toNormY(sy: Float): Float = ((gridRect.bottom - sy) / gridRect.height()).coerceIn(0.0f, 1.0f)

    private fun channelColor(channel: CurveChannel): Int = ContextCompat.getColor(
        context,
        when (channel) {
            CurveChannel.RGB -> R.color.editor_text_primary
            CurveChannel.R -> R.color.editor_curve_r
            CurveChannel.G -> R.color.editor_curve_g
            CurveChannel.B -> R.color.editor_curve_b
        }
    )

    private fun pointsFor(ops: CurveOps, channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.RGB -> ops.rgb
        CurveChannel.R -> ops.r
        CurveChannel.G -> ops.g
        CurveChannel.B -> ops.b
    }

    private companion object {
        private const val PLOT_STEPS = 64
        private const val GRID_PAD_DP = 10.0f
        private const val HIT_RADIUS_DP = 22.0f
        private const val DELETE_SLOP_DP = 18.0f
        private const val KNOT_RADIUS_DP = 5.0f
        private const val KNOT_ACTIVE_RADIUS_DP = 7.0f
    }
}
