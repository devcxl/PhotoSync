package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R

/**
 * Draws the dashed liquify brush ring under the active touch point.
 *
 * The ring is a pure affordance: it shows the exact radius the next stamp will affect, and it
 * changes colour between the push and restore modes so the user can tell which one is armed without
 * looking away from the image.
 */
internal class LiquifyBrushPainter(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val ringStroke = 1.5f * density
    private val dashLength = 6f * density
    private val dashGap = 4f * density
    private val centreRadius = 1.5f * density
    private val crosshairLength = 5f * density

    private val pushPaint = ringPaint(ContextCompat.getColor(context, R.color.editor_overlay_line))
    private val restorePaint = ringPaint(ContextCompat.getColor(context, R.color.editor_zero_pin))
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_glass_bg)
        style = Paint.Style.FILL
    }

    /**
     * Draws the brush preview.
     *
     * @param centreX Touch x in view pixels.
     * @param centreY Touch y in view pixels.
     * @param radiusPx Brush radius in view pixels.
     * @param restoring True to draw the restore (eraser) styling.
     */
    fun drawBrush(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radiusPx: Float,
        restoring: Boolean
    ) {
        if (radiusPx <= ringStroke) return
        val paint = if (restoring) restorePaint else pushPaint
        canvas.drawCircle(centreX, centreY, radiusPx, fillPaint)
        canvas.drawCircle(centreX, centreY, radiusPx, paint)
        canvas.drawCircle(centreX, centreY, centreRadius, paint)
        canvas.drawLine(centreX - crosshairLength, centreY, centreX + crosshairLength, centreY, paint)
        canvas.drawLine(centreX, centreY - crosshairLength, centreX, centreY + crosshairLength, paint)
    }

    private fun ringPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        pathEffect = DashPathEffect(floatArrayOf(dashLength, dashGap), 0.0f)
    }
}
