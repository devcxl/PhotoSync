package cn.devcxl.photosync.editor.render

import kotlin.math.roundToInt

/**
 * Maps the canvas viewport onto the previewed image.
 *
 * The editor letterboxes the frame inside the viewport and lets the user zoom up to
 * [MAX_CANVAS_ZOOM]; both the `Pass A`/`Pass B` draws and the Pass C presentation use these two
 * helpers so they can never disagree about where the picture edges are.
 */
internal object CanvasLetterbox {

    private const val MAX_ZOOM = 8.0f

    /** Per-axis NDC scale of a letterboxed [imageAspect] inside a viewport of [viewAspect]. */
    fun scale(imageAspect: Float, viewAspect: Float, rawZoom: Float): Pair<Float, Float> {
        val zoom = rawZoom.takeIf { it.isFinite() && it > 0.0f }?.coerceIn(1.0f, MAX_ZOOM)
            ?: 1.0f
        return if (imageAspect > viewAspect) {
            zoom to (viewAspect / imageAspect) * zoom
        } else {
            (imageAspect / viewAspect) * zoom to zoom
        }
    }

    /**
     * Pixel size of the offscreen target that covers the letterboxed image rect, capped at
     * [maxEdgePx] on the long edge so a zoomed-in preview never allocates an unbounded FBO.
     */
    fun targetSize(
        viewportWidth: Int,
        viewportHeight: Int,
        scaleX: Float,
        scaleY: Float,
        maxEdgePx: Int
    ): Pair<Int, Int> {
        val width = (viewportWidth * scaleX).roundToInt().coerceAtLeast(1)
        val height = (viewportHeight * scaleY).roundToInt().coerceAtLeast(1)
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdgePx) return width to height
        val shrink = maxEdgePx.toFloat() / longEdge.toFloat()
        return (width * shrink).roundToInt().coerceAtLeast(1) to
            (height * shrink).roundToInt().coerceAtLeast(1)
    }
}
