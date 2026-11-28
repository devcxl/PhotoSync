package cn.devcxl.photosync.editor.domain

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A working window of the full-resolution pre-crop source that one inpainting run reads.
 *
 * A 60 MP photo cannot be held as a whole `ARGB` buffer, so the brush is projected into full-source
 * pixels first, then only the painted bbox plus context margin is decoded at 1:1. [originX] and
 * [originY] translate that window back into source pixels; patch boxes are returned to
 * [InpaintBBox] normalized against [sourceWidth] / [sourceHeight], never against the window.
 *
 * @property originX Left edge of the window inside the full source, in source pixels.
 * @property originY Top edge of the window inside the full source, in source pixels.
 * @property width Window width in pixels.
 * @property height Window height in pixels.
 * @property sourceWidth Full pre-crop source width.
 * @property sourceHeight Full pre-crop source height.
 */
internal data class InpaintWorkRegion(
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int
) {

    init {
        require(originX >= 0 && originY >= 0) { "Inpaint window origin must not be negative" }
        require(width > 0 && height > 0) { "Inpaint window must not be empty" }
        require(originX + width <= sourceWidth && originY + height <= sourceHeight) {
            "Inpaint window must stay inside the source"
        }
    }

    /**
     * Re-expresses full-source normalized [strokes] in this window's pixel space.
     *
     * The returned strokes are normalized to the window, which is exactly what
     * [InpaintStrokeLayer.rasterize] consumes. The radius keeps the same physical pixel size.
     */
    fun localize(strokes: List<InpaintStroke>): List<InpaintStroke> = strokes.map { stroke ->
        InpaintStroke(
            erase = stroke.erase,
            radiusNorm = (stroke.radiusNorm * sourceWidth / width).coerceAtLeast(MIN_RADIUS_NORM),
            points = stroke.points.map { point ->
                InpaintStrokePoint(
                    u = (point.u * sourceWidth - originX) / width,
                    v = (point.v * sourceHeight - originY) / height
                )
            }
        )
    }

    /** Converts a patch box normalized to this window into one normalized to the full source. */
    fun toSourceBBox(local: InpaintBBox): InpaintBBox = InpaintBBox(
        left = ((originX + local.left * width) / sourceWidth).coerceIn(0.0f, 1.0f),
        top = ((originY + local.top * height) / sourceHeight).coerceIn(0.0f, 1.0f),
        right = ((originX + local.right * width) / sourceWidth).coerceIn(0.0f, 1.0f),
        bottom = ((originY + local.bottom * height) / sourceHeight).coerceIn(0.0f, 1.0f)
    )

    companion object {

        /**
         * Context margin around the painted bbox.
         *
         * It must cover the planner's largest possible crop shift. An E1 single-tile crop is a
         * square of at most 896 px on STANDARD (768 px on LOW), centred on a bbox of up to the tier's
         * fine core; a thin 640 × 1 stroke therefore reaches 448 px beyond the bbox vertically.
         * Grid crops only add the 128 px context margin. 512 px covers the worst case with a small
         * safety margin.
         */
        const val CONTEXT_MARGIN_PX: Int = 512

        private const val MIN_RADIUS_NORM = 1e-6f

        /**
         * Builds the smallest window that contains [bounds] plus [marginPx], clamped to the source.
         *
         * @return `null` when the bounds are empty or the source size is invalid.
         */
        fun around(
            bounds: InpaintPixelRect,
            sourceWidth: Int,
            sourceHeight: Int,
            marginPx: Int = CONTEXT_MARGIN_PX
        ): InpaintWorkRegion? {
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            if (bounds.isEmpty) return null
            val margin = marginPx.coerceAtLeast(0)
            val left = (bounds.left - margin).coerceIn(0, sourceWidth)
            val top = (bounds.top - margin).coerceIn(0, sourceHeight)
            val right = (bounds.right + margin).coerceIn(left, sourceWidth)
            val bottom = (bounds.bottom + margin).coerceIn(top, sourceHeight)
            if (right <= left || bottom <= top) return null
            return InpaintWorkRegion(
                originX = left,
                originY = top,
                width = right - left,
                height = bottom - top,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )
        }

        /**
         * Bounds of [strokes] in full-source pixels.
         *
         * The strokes must already be expressed in pre-crop normalized coordinates. Radius is the
         * stroke's fraction of the source width, matching [InpaintStroke.bounds].
         */
        fun boundsOf(
            strokes: List<InpaintStroke>,
            sourceWidth: Int,
            sourceHeight: Int
        ): InpaintPixelRect? {
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (stroke in strokes) {
                val radius = stroke.radiusNorm * sourceWidth
                for (point in stroke.points) {
                    val x = point.u * sourceWidth
                    val y = point.v * sourceHeight
                    left = minOf(left, x - radius)
                    top = minOf(top, y - radius)
                    right = maxOf(right, x + radius)
                    bottom = maxOf(bottom, y + radius)
                }
            }
            if (left > right || top > bottom) return null
            val rect = InpaintPixelRect(
                left = floor(left).toInt().coerceIn(0, sourceWidth),
                top = floor(top).toInt().coerceIn(0, sourceHeight),
                right = ceil(right).toInt().coerceIn(0, sourceWidth),
                bottom = ceil(bottom).toInt().coerceIn(0, sourceHeight)
            )
            return rect.takeUnless { it.isEmpty }
        }
    }
}
