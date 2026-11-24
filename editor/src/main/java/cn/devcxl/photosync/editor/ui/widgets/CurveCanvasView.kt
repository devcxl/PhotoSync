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
import cn.devcxl.photosync.editor.domain.CompiledMonotoneCurve
import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.CurvePoint
import cn.devcxl.photosync.editor.domain.MonotoneCubicCurve
import cn.devcxl.photosync.editor.ui.gesture.CurveKnotMath

/** The four tone-curve channels exposed by [CurveEditorView]. */
enum class CurveChannel { RGB, R, G, B }

/**
 * Interactive curve grid canvas supporting knot creation, dragging and drag-below-grid deletion
 * (`2..16` knots, minimum horizontal spacing `1/256`).
 */
internal class CurveCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Callbacks raised when the user drags or commits a channel's knot list. */
    interface Listener {
        /** Raised whenever the active [channel] knot list changes during drag or tap. */
        fun onChannelPointsChanged(channel: CurveChannel, points: List<CurvePoint>)

        /** Raised when the finger lifts and the undo step can be sealed. */
        fun onStopTrackingTouch()
    }

    /** Listener receiving live knot updates and release notifications. */
    var listener: Listener? = null

    private var curveOps: CurveOps = CurveOps()
    private var activeChannel: CurveChannel = CurveChannel.RGB
    private var activePoints: List<CurvePoint> = CurveOps.DEFAULT_LINEAR_CURVE
    private var compiledCurves: Map<CurveChannel, CompiledMonotoneCurve> = emptyMap()
    private var draggingIndex: Int = -1
    private var pendingDelete: Boolean = false

    private val density = resources.displayMetrics.density
    private val gridRect = RectF()
    private val curvePath = Path()
    private val channelColors: Map<CurveChannel, Int> = mapOf(
        CurveChannel.RGB to ContextCompat.getColor(context, R.color.editor_text_primary),
        CurveChannel.R to ContextCompat.getColor(context, R.color.editor_curve_r),
        CurveChannel.G to ContextCompat.getColor(context, R.color.editor_curve_g),
        CurveChannel.B to ContextCompat.getColor(context, R.color.editor_curve_b)
    )

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

    init {
        rebuildCompiledCurves()
    }

    /** Binds [ops] and selects [channel], recompiling cached splines outside `onDraw`. */
    fun bindState(ops: CurveOps, channel: CurveChannel) {
        curveOps = ops
        activeChannel = channel
        if (draggingIndex == -1) {
            activePoints = pointsFor(ops, channel)
        }
        rebuildCompiledCurves()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGridRect()
        drawGridAndBaseline(canvas)
        CurveChannel.entries.forEach { ch ->
            if (ch != activeChannel) {
                val compiled = compiledCurves[ch] ?: return@forEach
                drawCompiledCurve(canvas, compiled, channelColors.getValue(ch), inactiveCurvePaint)
            }
        }
        val activeCompiled = if (pendingDelete && draggingIndex in activePoints.indices) {
            MonotoneCubicCurve.compile(CurveKnotMath.deleteKnot(activePoints, draggingIndex))
        } else {
            compiledCurves[activeChannel] ?: MonotoneCubicCurve.compile(activePoints)
        }
        drawCompiledCurve(canvas, activeCompiled, channelColors.getValue(activeChannel), curvePaint)
        drawKnots(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateGridRect()
        if (gridRect.width() <= 1.0f || gridRect.height() <= 1.0f) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> handleMove(event.x, event.y)
            MotionEvent.ACTION_UP -> handleUp()
            MotionEvent.ACTION_CANCEL -> handleCancel()
            else -> super.onTouchEvent(event)
        }
    }

    private fun handleDown(x: Float, y: Float): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val hitRadius = HIT_RADIUS_DP * density
        val hitIndex = activePoints.indices.minByOrNull { idx ->
            hypot(x - toScreenX(activePoints[idx].x), y - toScreenY(activePoints[idx].y))
        }?.takeIf { idx ->
            hypot(x - toScreenX(activePoints[idx].x), y - toScreenY(activePoints[idx].y)) <= hitRadius
        }
        if (hitIndex != null) {
            draggingIndex = hitIndex
            pendingDelete = false
            invalidate()
            return true
        }
        val inserted = CurveKnotMath.insertKnot(activePoints, toNormX(x), toNormY(y)) ?: return true
        activePoints = inserted.first
        draggingIndex = inserted.second
        pendingDelete = false
        notifyPointsChanged()
        return true
    }

    private fun handleMove(x: Float, y: Float): Boolean {
        if (draggingIndex !in activePoints.indices) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        if (activePoints.size > MonotoneCubicCurve.MIN_POINTS &&
            y > gridRect.bottom + DELETE_SLOP_DP * density
        ) {
            pendingDelete = true
            invalidate()
            return true
        }
        pendingDelete = false
        activePoints = CurveKnotMath.moveKnot(activePoints, draggingIndex, toNormX(x), toNormY(y))
        notifyPointsChanged()
        return true
    }

    private fun handleUp(): Boolean {
        if (draggingIndex != -1) {
            if (pendingDelete) {
                activePoints = CurveKnotMath.deleteKnot(activePoints, draggingIndex)
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

    private fun handleCancel(): Boolean {
        if (draggingIndex != -1) {
            draggingIndex = -1
            pendingDelete = false
            activePoints = pointsFor(curveOps, activeChannel)
            rebuildCompiledCurves()
            invalidate()
            listener?.onStopTrackingTouch()
        }
        return true
    }

    private fun notifyPointsChanged() {
        listener?.onChannelPointsChanged(activeChannel, activePoints)
        rebuildCompiledCurves()
        invalidate()
    }

    private fun rebuildCompiledCurves() {
        compiledCurves = CurveChannel.entries.associateWith { ch ->
            val pts = if (ch == activeChannel) activePoints else pointsFor(curveOps, ch)
            MonotoneCubicCurve.compile(pts)
        }
    }

    private fun updateGridRect() {
        val pad = GRID_PAD_DP * density
        gridRect.set(pad, pad, (width - pad).coerceAtLeast(pad + 1.0f), (height - pad).coerceAtLeast(pad + 1.0f))
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

    private fun drawCompiledCurve(
        canvas: Canvas,
        compiled: CompiledMonotoneCurve,
        color: Int,
        paint: Paint
    ) {
        curvePath.reset()
        for (i in 0..PLOT_STEPS) {
            val x = i.toFloat() / PLOT_STEPS.toFloat()
            val sx = toScreenX(x)
            val sy = toScreenY(compiled.evaluate(x))
            if (i == 0) curvePath.moveTo(sx, sy) else curvePath.lineTo(sx, sy)
        }
        paint.color = color
        canvas.drawPath(curvePath, paint)
    }

    private fun drawKnots(canvas: Canvas) {
        knotStrokePaint.color = channelColors.getValue(activeChannel)
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
