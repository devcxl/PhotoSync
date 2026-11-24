package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R

/**
 * Draws the crop scrim, corner/edge handles, rule-of-thirds grid and perspective pins of
 * [CropOverlayView].
 */
internal class CropOverlayPainter(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val handleLength = 18f * density
    private val handleThickness = 3f * density
    private val edgeHandleLength = 26f * density
    private val pinRadius = 9f * density

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_crop_scrim)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_overlay_line)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_accent_blue)
        style = Paint.Style.STROKE
        strokeWidth = handleThickness
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_overlay_grid)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val pinFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_accent_blue)
        style = Paint.Style.FILL
    }
    private val pinStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_text_primary)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    /**
     * Darkens everything outside [rect] so the kept frame reads as the bright area.
     */
    fun drawScrim(canvas: Canvas, rect: RectF) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        canvas.drawRect(0f, 0f, width, rect.top, scrimPaint)
        canvas.drawRect(0f, rect.bottom, width, height, scrimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, scrimPaint)
        canvas.drawRect(rect.right, rect.top, width, rect.bottom, scrimPaint)
    }

    /**
     * Draws the crop border, the rule-of-thirds grid and, when [withHandles] is true, the eight
     * drag handles.
     */
    fun drawCropFrame(canvas: Canvas, rect: RectF, withHandles: Boolean) {
        canvas.drawRect(rect, borderPaint)
        drawThirdsGrid(canvas, rect)
        if (!withHandles) return
        drawCornerHandles(canvas, rect)
        drawEdgeHandles(canvas, rect)
    }

    /**
     * Draws the rule-of-thirds composition grid inside [rect].
     */
    fun drawThirdsGrid(canvas: Canvas, rect: RectF) {
        val thirdWidth = rect.width() / 3f
        val thirdHeight = rect.height() / 3f
        for (index in 1..2) {
            val x = rect.left + thirdWidth * index
            val y = rect.top + thirdHeight * index
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
        }
    }

    /**
     * Draws the four perspective pins and the quad edges that connect them.
     *
     * @param quad Flat `[u0, v0, ... u3, v3]` corner list.
     * @param imageRect Pixel rect that normalized UV is projected into.
     */
    fun drawPerspectiveQuad(canvas: Canvas, quad: FloatArray, imageRect: RectF) {
        if (quad.size != CORNER_COORDINATES) return
        for (corner in 0 until CORNER_COUNT) {
            val next = (corner + 1) % CORNER_COUNT
            canvas.drawLine(
                projectX(quad[corner * 2], imageRect),
                projectY(quad[corner * 2 + 1], imageRect),
                projectX(quad[next * 2], imageRect),
                projectY(quad[next * 2 + 1], imageRect),
                gridPaint
            )
        }
        for (corner in 0 until CORNER_COUNT) {
            val centerX = projectX(quad[corner * 2], imageRect)
            val centerY = projectY(quad[corner * 2 + 1], imageRect)
            canvas.drawCircle(centerX, centerY, pinRadius, pinFillPaint)
            canvas.drawCircle(centerX, centerY, pinRadius, pinStrokePaint)
        }
    }

    private fun drawCornerHandles(canvas: Canvas, rect: RectF) {
        drawCorner(canvas, rect.left, rect.top, 1f, 1f)
        drawCorner(canvas, rect.right, rect.top, -1f, 1f)
        drawCorner(canvas, rect.right, rect.bottom, -1f, -1f)
        drawCorner(canvas, rect.left, rect.bottom, 1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, directionX: Float, directionY: Float) {
        canvas.drawLine(x, y, x + handleLength * directionX, y, accentPaint)
        canvas.drawLine(x, y, x, y + handleLength * directionY, accentPaint)
    }

    private fun drawEdgeHandles(canvas: Canvas, rect: RectF) {
        val halfEdge = edgeHandleLength / 2f
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        canvas.drawLine(centerX - halfEdge, rect.top, centerX + halfEdge, rect.top, accentPaint)
        canvas.drawLine(centerX - halfEdge, rect.bottom, centerX + halfEdge, rect.bottom, accentPaint)
        canvas.drawLine(rect.left, centerY - halfEdge, rect.left, centerY + halfEdge, accentPaint)
        canvas.drawLine(rect.right, centerY - halfEdge, rect.right, centerY + halfEdge, accentPaint)
    }

    private fun projectX(u: Float, imageRect: RectF): Float = imageRect.left + u * imageRect.width()

    private fun projectY(v: Float, imageRect: RectF): Float = imageRect.top + v * imageRect.height()

    private companion object {
        private const val CORNER_COUNT = 4
        private const val CORNER_COORDINATES = CORNER_COUNT * 2
    }
}
