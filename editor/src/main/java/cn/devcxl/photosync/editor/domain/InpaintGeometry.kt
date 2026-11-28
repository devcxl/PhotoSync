package cn.devcxl.photosync.editor.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Integer pixel rectangle in the pre-crop original image.
 *
 * [right] and [bottom] are exclusive, which makes `width = right - left` and keeps every crop
 * conversion free of off-by-one adjustments.
 */
data class InpaintPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {

    init {
        require(right >= left && bottom >= top) { "Inpaint rect must not be inverted" }
    }

    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    val isEmpty: Boolean
        get() = width == 0 || height == 0

    /** Grows every edge by [marginPx] without clipping. */
    fun expandedBy(marginPx: Int): InpaintPixelRect = InpaintPixelRect(
        left = left - marginPx,
        top = top - marginPx,
        right = right + marginPx,
        bottom = bottom + marginPx
    )

    /** Clips this rect to an image of [width] x [height]. */
    fun clampedTo(width: Int, height: Int): InpaintPixelRect = InpaintPixelRect(
        left = left.coerceIn(0, width),
        top = top.coerceIn(0, height),
        right = right.coerceIn(0, width),
        bottom = bottom.coerceIn(0, height)
    )

    /** Smallest rect covering both operands. */
    fun union(other: InpaintPixelRect): InpaintPixelRect = InpaintPixelRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom)
    )

    /** True when the two rects share at least one pixel. */
    fun intersects(other: InpaintPixelRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** True when [x], [y] lie inside the half-open rect. */
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    /** True when [other] lies completely inside this half-open rect. */
    fun containsRect(other: InpaintPixelRect): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
}

/**
 * Soft coverage mask painted by the user, in source-image pixels.
 *
 * Values use the Android `Alpha_8` convention: `255` means "replace this pixel" (a hole) and `0`
 * means "keep the original". The array is row-major and read with [coverageAt] because Kotlin's
 * [ByteArray] is signed.
 */
class InpaintMaskBuffer(
    val width: Int,
    val height: Int,
    val coverage: ByteArray
) {

    init {
        require(width > 0 && height > 0) { "Inpaint mask dimensions must be positive" }
        require(coverage.size == width * height) { "Inpaint mask payload does not match its size" }
    }

    /** Unsigned coverage of one pixel, in `0..255`. */
    fun coverageAt(x: Int, y: Int): Int = coverage[y * width + x].toInt() and 0xFF

    /** True when at least one pixel was painted. */
    fun hasPaintedPixels(): Boolean = coverage.any { it.toInt() != 0 }
}

/**
 * One sampled point of a brush stroke in normalized image coordinates.
 *
 * `u` runs left-to-right across the image width, `v` top-to-bottom. The overlay converts touches
 * into this space so a stroke stays glued to the photo while the preview zooms or pans.
 */
data class InpaintStrokePoint(val u: Float, val v: Float) {

    init {
        require(u.isFinite() && v.isFinite()) { "Inpaint stroke point must be finite" }
    }
}

/**
 * One committed brush stroke.
 *
 * @property erase True for the eraser: the stroke removes coverage painted earlier.
 * @property radiusNorm Brush radius as a fraction of the source-image width, so the brush keeps the
 *   same physical size at any preview zoom and any source resolution.
 * @property points Sampled brush centres; a single point is a round dab.
 */
data class InpaintStroke(
    val erase: Boolean,
    val radiusNorm: Float,
    val points: List<InpaintStrokePoint>
) {

    init {
        require(radiusNorm > 0.0f && radiusNorm.isFinite()) { "Inpaint brush radius must be positive" }
        require(points.isNotEmpty()) { "Inpaint stroke needs at least one point" }
    }

    /** Bounding box of the stroke capsule at [width] x [height], clipped to the image. */
    fun bounds(width: Int, height: Int): InpaintPixelRect {
        val radiusPx = radiusNorm * width
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (point in points) {
            val x = point.u * width
            val y = point.v * height
            left = min(left, x - radiusPx)
            top = min(top, y - radiusPx)
            right = max(right, x + radiusPx)
            bottom = max(bottom, y + radiusPx)
        }
        return InpaintPixelRect(
            left = floor(left).toInt().coerceIn(0, width),
            top = floor(top).toInt().coerceIn(0, height),
            right = ceil(right).toInt().coerceIn(0, width),
            bottom = ceil(bottom).toInt().coerceIn(0, height)
        )
    }
}

/**
 * Ordered paint/erase strokes of the P2 inpainting brush.
 *
 * The layer is the single source of truth between the overlay, the panel and the inference run: the
 * view commits strokes here, [rasterize] turns them into the mask MI-GAN consumes, and clearing the
 * layer is the panel's "clear strokes" action. All accessors are synchronized because the view
 * commits on the main thread while rasterization runs on the default dispatcher.
 */
class InpaintStrokeLayer {

    private val strokes = mutableListOf<InpaintStroke>()

    /** True when no stroke was committed (an unfinished stroke is never part of the layer). */
    val isEmpty: Boolean
        @Synchronized get() = strokes.isEmpty()

    /** Number of committed strokes, exposed for tests and diagnostics. */
    val strokeCount: Int
        @Synchronized get() = strokes.size

    /** Appends [stroke] after every previous stroke. */
    @Synchronized
    fun add(stroke: InpaintStroke) {
        strokes.add(stroke)
    }

    /** Drops every committed stroke. */
    @Synchronized
    fun clear() {
        strokes.clear()
    }

    /** Snapshot of the committed strokes, oldest first. */
    @Synchronized
    fun snapshot(): List<InpaintStroke> = strokes.toList()

    /** Union of every stroke's bounds at [width] x [height], or `null` when nothing is painted. */
    fun bounds(width: Int, height: Int): InpaintPixelRect? =
        snapshot().map { it.bounds(width, height) }
            .filterNot { it.isEmpty }
            .reduceOrNull(InpaintPixelRect::union)

    /**
     * Rasterizes the committed strokes into a source-resolution coverage mask.
     *
     * Paint strokes take the maximum coverage and eraser strokes subtract it, both in commit order.
     * The stroke edge gets one pixel of antialiasing so the feathered MI-GAN mask does not start
     * from a jagged line.
     */
    fun rasterize(width: Int, height: Int): InpaintMaskBuffer {
        require(width > 0 && height > 0) { "Inpaint mask dimensions must be positive" }
        val coverage = ByteArray(width * height)
        for (stroke in snapshot()) {
            Rasterizer(coverage, width, height).draw(stroke)
        }
        return InpaintMaskBuffer(width, height, coverage)
    }

    private class Rasterizer(
        private val coverage: ByteArray,
        private val width: Int,
        private val height: Int
    ) {

        fun draw(stroke: InpaintStroke) {
            val radiusPx = stroke.radiusNorm * width
            var previous: InpaintStrokePoint? = null
            for (point in stroke.points) {
                val next = previous
                if (next == null) {
                    stamp(point, point, radiusPx, stroke.erase)
                } else {
                    stamp(next, point, radiusPx, stroke.erase)
                }
                previous = point
            }
        }

        private fun stamp(
            from: InpaintStrokePoint,
            to: InpaintStrokePoint,
            radiusPx: Float,
            erase: Boolean
        ) {
            val fromX = from.u * width
            val fromY = from.v * height
            val toX = to.u * width
            val toY = to.v * height
            val left = floor(min(fromX, toX) - radiusPx - 1.0f).toInt().coerceIn(0, width)
            val top = floor(min(fromY, toY) - radiusPx - 1.0f).toInt().coerceIn(0, height)
            val right = ceil(max(fromX, toX) + radiusPx + 1.0f).toInt().coerceIn(0, width)
            val bottom = ceil(max(fromY, toY) + radiusPx + 1.0f).toInt().coerceIn(0, height)
            if (left >= right || top >= bottom) return
            for (y in top until bottom) {
                val dy = y + 0.5f
                for (x in left until right) {
                    val dx = x + 0.5f
                    val distance = distanceToSegment(dx, dy, fromX, fromY, toX, toY)
                    val edge = (radiusPx - distance + 0.5f).coerceIn(0.0f, 1.0f)
                    if (edge <= 0.0f) continue
                    applyCoverage(y * width + x, edge, erase)
                }
            }
        }

        private fun applyCoverage(index: Int, edge: Float, erase: Boolean) {
            val current = coverage[index].toInt() and 0xFF
            val next = if (erase) {
                (current * (1.0f - edge)).roundToInt()
            } else {
                max(current, (edge * 255.0f).roundToInt())
            }
            coverage[index] = next.coerceIn(0, 255).toByte()
        }

        private fun distanceToSegment(
            px: Float,
            py: Float,
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float
        ): Float {
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 1e-6f) return hypot(px - ax, py - ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0f, 1.0f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
    }
}
