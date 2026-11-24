package cn.devcxl.photosync.editor.ui.gesture

import kotlin.math.abs

import cn.devcxl.photosync.editor.domain.CurvePoint
import cn.devcxl.photosync.editor.domain.MonotoneCubicCurve

/**
 * Pure state transitions for interactive curve-knot editing (`CurveCanvasView`).
 *
 * Separates spacing enforcement, neighbor clamping and drag-below-grid deletion from Android
 * [android.view.MotionEvent] plumbing so the interaction rules stay deterministic and unit-tested.
 */
internal object CurveKnotMath {

    /**
     * Inserts a new knot at `(nx, ny)` into sorted [points] when the total is below
     * [MonotoneCubicCurve.MAX_POINTS] and at least [MonotoneCubicCurve.MIN_SPACING] away from
     * existing knots.
     *
     * @return Updated list paired with the inserted knot index, or `null` if insertion was blocked.
     */
    fun insertKnot(points: List<CurvePoint>, nx: Float, ny: Float): Pair<List<CurvePoint>, Int>? {
        if (points.size >= MonotoneCubicCurve.MAX_POINTS) return null
        val cx = nx.coerceIn(0.0f, 1.0f)
        val cy = ny.coerceIn(0.0f, 1.0f)
        if (points.any { abs(it.x - cx) < MonotoneCubicCurve.MIN_SPACING }) return null

        val updated = points.toMutableList()
        val insertAt = updated.indexOfFirst { it.x > cx }.let { if (it == -1) updated.size else it }
        updated.add(insertAt, CurvePoint(cx, cy))
        return MonotoneCubicCurve.sanitizePoints(updated) to insertAt
    }

    /**
     * Moves the knot at [index] to `(nx, ny)` while clamping `x` strictly inside its neighbors by
     * [MonotoneCubicCurve.MIN_SPACING].
     */
    fun moveKnot(points: List<CurvePoint>, index: Int, nx: Float, ny: Float): List<CurvePoint> {
        if (index !in points.indices) return points
        val minX = if (index == 0) 0.0f else points[index - 1].x + MonotoneCubicCurve.MIN_SPACING
        val maxX = if (index == points.lastIndex) 1.0f else {
            points[index + 1].x - MonotoneCubicCurve.MIN_SPACING
        }
        val clampedX = nx.coerceIn(minX, maxX.coerceAtLeast(minX))
        val clampedY = ny.coerceIn(0.0f, 1.0f)
        val updated = points.toMutableList()
        updated[index] = CurvePoint(clampedX, clampedY)
        return MonotoneCubicCurve.sanitizePoints(updated)
    }

    /**
     * Deletes the knot at [index] when more than [MonotoneCubicCurve.MIN_POINTS] knots exist.
     */
    fun deleteKnot(points: List<CurvePoint>, index: Int): List<CurvePoint> {
        if (points.size <= MonotoneCubicCurve.MIN_POINTS || index !in points.indices) return points
        val updated = points.toMutableList()
        updated.removeAt(index)
        return MonotoneCubicCurve.sanitizePoints(updated)
    }
}
