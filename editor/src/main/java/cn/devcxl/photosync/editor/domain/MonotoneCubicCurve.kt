package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Compiled Fritsch-Carlson monotone cubic Hermite spline ready for fast evaluation.
 *
 * Guarantees zero overshoot on every interval `[x_k, x_{k+1}]` and strict monotonicity whenever
 * the underlying control points are non-decreasing.
 */
class CompiledMonotoneCurve internal constructor(
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val tangents: FloatArray
) {

    /**
     * Evaluates the curve at normalized coordinate [x], clamped to `[0, 1]`.
     */
    fun evaluate(x: Float): Float {
        val clamped = x.coerceIn(0.0f, 1.0f)
        val n = xs.size
        if (clamped <= xs[0]) return ys[0].coerceIn(0.0f, 1.0f)
        if (clamped >= xs[n - 1]) return ys[n - 1].coerceIn(0.0f, 1.0f)

        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= clamped) lo = mid else hi = mid
        }

        val h = xs[lo + 1] - xs[lo]
        if (h <= EPSILON) return ys[lo].coerceIn(0.0f, 1.0f)
        val t = ((clamped - xs[lo]) / h).coerceIn(0.0f, 1.0f)
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2.0f * t3 - 3.0f * t2 + 1.0f
        val h10 = t3 - 2.0f * t2 + t
        val h01 = -2.0f * t3 + 3.0f * t2
        val h11 = t3 - t2
        val value = h00 * ys[lo] + h10 * h * tangents[lo] + h01 * ys[lo + 1] + h11 * h * tangents[lo + 1]
        return value.coerceIn(0.0f, 1.0f)
    }

    private companion object {
        private const val EPSILON = 1e-7f
    }
}

/**
 * Fritsch-Carlson (1980) monotone piecewise cubic Hermite interpolator.
 *
 * Sanitizes control points (`2..16`, minimum spacing `1/256`), computes secant slopes and
 * clamps tangent magnitudes onto the `alpha^2 + beta^2 <= 9` circle so smooth sky gradients
 * never exhibit ringing halos.
 */
object MonotoneCubicCurve {

    /** Minimum number of control knots per channel curve (black/shadow and white/highlight ends). */
    const val MIN_POINTS = 2

    /** Maximum number of control knots per channel curve enforced by [EditValidator]. */
    const val MAX_POINTS = 16

    /** Minimum horizontal spacing (`1 / 256`) between adjacent knots to keep slopes finite. */
    const val MIN_SPACING = 1.0f / 256.0f

    private const val FLAT_SLOPE_EPSILON = 1e-7f
    private const val MAX_TANGENT_NORM_SQ = 9.0f

    /**
     * Normalizes [points] into a valid sorted list of `2..16` knots spaced by at least [MIN_SPACING].
     *
     * When more than [MAX_POINTS] valid knots are supplied, the final knot is preserved as the
     * highlight endpoint whenever spaced cleanly from the penultimate knot, preventing silent
     * highlight-end flattening.
     */
    fun sanitizePoints(points: List<CurvePoint>): List<CurvePoint> {
        val valid = points
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map { CurvePoint(it.x.coerceIn(0.0f, 1.0f), it.y.coerceIn(0.0f, 1.0f)) }
            .sortedBy { it.x }
        if (valid.size < MIN_POINTS) return CurveOps.DEFAULT_LINEAR_CURVE

        val filtered = ArrayList<CurvePoint>(valid.size)
        filtered.add(valid.first())
        for (i in 1 until valid.size) {
            if (valid[i].x - filtered.last().x >= MIN_SPACING) {
                filtered.add(valid[i])
            }
        }
        if (filtered.size < MIN_POINTS) return CurveOps.DEFAULT_LINEAR_CURVE
        if (filtered.size <= MAX_POINTS) return filtered

        val capped = ArrayList<CurvePoint>(MAX_POINTS)
        for (i in 0 until MAX_POINTS - 1) {
            capped.add(filtered[i])
        }
        capped.add(filtered.last())
        return capped
    }

    /**
     * Compiles [points] into a [CompiledMonotoneCurve].
     */
    fun compile(points: List<CurvePoint>): CompiledMonotoneCurve {
        val clean = sanitizePoints(points)
        val n = clean.size
        val xs = FloatArray(n) { clean[it].x }
        val ys = FloatArray(n) { clean[it].y }
        val deltas = FloatArray(n - 1)
        for (k in 0 until n - 1) {
            val dx = xs[k + 1] - xs[k]
            deltas[k] = if (dx > FLAT_SLOPE_EPSILON) (ys[k + 1] - ys[k]) / dx else 0.0f
        }

        val tangents = FloatArray(n)
        tangents[0] = deltas[0]
        tangents[n - 1] = deltas[n - 2]
        for (k in 1 until n - 1) {
            val prev = deltas[k - 1]
            val next = deltas[k]
            tangents[k] = if (prev * next <= 0.0f) 0.0f else 0.5f * (prev + next)
        }

        for (k in 0 until n - 1) {
            val delta = deltas[k]
            if (abs(delta) < FLAT_SLOPE_EPSILON) {
                tangents[k] = 0.0f
                tangents[k + 1] = 0.0f
                continue
            }
            val alpha = (tangents[k] / delta).coerceAtLeast(0.0f)
            val beta = (tangents[k + 1] / delta).coerceAtLeast(0.0f)
            val normSq = alpha * alpha + beta * beta
            if (normSq > MAX_TANGENT_NORM_SQ) {
                val tau = 3.0f / sqrt(normSq)
                tangents[k] = tau * alpha * delta
                tangents[k + 1] = tau * beta * delta
            }
        }
        return CompiledMonotoneCurve(xs, ys, tangents)
    }
}
