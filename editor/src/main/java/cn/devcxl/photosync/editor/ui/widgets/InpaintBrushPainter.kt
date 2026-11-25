package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokePoint
import cn.devcxl.photosync.editor.ui.InpaintBrushMode

/**
 * Draws the red translucent removal mask and the dashed brush ring of the P2 inpainting tool.
 *
 * Committed paint strokes and the in-flight stroke are painted as round-capped polylines; eraser
 * strokes are drawn with `PorterDuff.CLEAR` inside one saved layer so they punch real holes in the
 * red preview instead of painting a second colour. The ring only shows the next affected radius.
 */
internal class InpaintBrushPainter(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MASK_COLOR
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val maskFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MASK_COLOR
        style = Paint.Style.FILL
    }
    private val eraseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0.0f)
    }
    private val paintRingColor = ContextCompat.getColor(context, R.color.editor_overlay_line)
    private val eraseRingColor = ContextCompat.getColor(context, R.color.editor_zero_pin)
    private val reusablePath = Path()

    /**
     * Draws one frame.
     *
     * @param projection Current image rect in view pixels, including the canvas transform.
     * @param strokes Committed strokes, oldest first.
     * @param activeStroke In-flight stroke, or `null`.
     * @param mode Armed mode; decides how [activeStroke] is painted.
     * @param brushCentre Last touch point in normalized coordinates, or `null`.
     * @param brushRadiusNorm Brush radius as a fraction of the image width.
     */
    fun draw(
        canvas: Canvas,
        viewWidth: Int,
        viewHeight: Int,
        projection: RectF,
        strokes: List<InpaintStroke>,
        activeStroke: InpaintStroke?,
        mode: InpaintBrushMode,
        brushCentre: InpaintStrokePoint?,
        brushRadiusNorm: Float
    ) {
        if (projection.isEmpty) return
        val hasEraser = strokes.any { it.erase } || activeStroke?.erase == true
        val layer = if (hasEraser) {
            canvas.saveLayer(0.0f, 0.0f, viewWidth.toFloat(), viewHeight.toFloat(), null)
        } else {
            canvas.save()
        }
        canvas.clipRect(projection)
        val imageWidth = projection.width()
        for (stroke in strokes) {
            drawStroke(canvas, stroke, projection, imageWidth)
        }
        if (activeStroke != null) drawStroke(canvas, activeStroke, projection, imageWidth)
        canvas.restoreToCount(layer)
        val centre = brushCentre ?: return
        ringPaint.color = if (mode == InpaintBrushMode.ERASE) eraseRingColor else paintRingColor
        canvas.drawCircle(
            projection.left + centre.u * projection.width(),
            projection.top + centre.v * projection.height(),
            brushRadiusNorm * imageWidth,
            ringPaint
        )
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: InpaintStroke,
        projection: RectF,
        imageWidth: Float
    ) {
        val radiusPx = stroke.radiusNorm * imageWidth
        if (radiusPx <= 0.0f) return
        if (stroke.points.size == 1) {
            val point = stroke.points.single()
            val fillPaint = if (stroke.erase) eraseFillPaint else maskFillPaint
            canvas.drawCircle(
                projection.left + point.u * projection.width(),
                projection.top + point.v * projection.height(),
                radiusPx,
                fillPaint
            )
            return
        }
        val paint = if (stroke.erase) erasePaint else maskPaint
        paint.strokeWidth = radiusPx * 2.0f
        val path = reusablePath
        path.rewind()
        stroke.points.forEachIndexed { index, point ->
            val x = projection.left + point.u * projection.width()
            val y = projection.top + point.v * projection.height()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private companion object {
        /** 40% red: visible on any photo without hiding the texture under the stroke. */
        const val MASK_COLOR = 0x66FF3B30
    }
}
