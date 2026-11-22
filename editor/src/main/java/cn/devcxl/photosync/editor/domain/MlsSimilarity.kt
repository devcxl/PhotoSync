package cn.devcxl.photosync.editor.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Closed-form moving-least-squares (MLS) **similarity** deformation of Schaefer et al. 2006.
 *
 * The warp is parameterised by a correspondence set: every control point has a position in the
 * deformed (output) frame and a position in the original (sampling) frame. For a query point the
 * solver fits the least-squares rotation + uniform scale that maps the source controls onto the
 * target controls and returns where the query has to sample.
 *
 * Unlike the "normalise `A (v - q*)`" shortcut, this implementation evaluates the optimal similarity
 * directly (rotation from `atan2` of the moment matrix, scale from the same moments). That makes the
 * solver **exactly identity** when every correspondence is the identity, which is what keeps the
 * 32 image-border pins and the 16 per-face halo pins at zero displacement.
 */
internal object MlsSimilarity {

    private const val EPSILON = 1e-8f
    private const val MOMENT_EPSILON = 1e-12f
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 2.0f

    /**
     * Solves the inverse warp at [u], [v] and writes the sampling coordinate into [out][0..1].
     *
     * @param source Interleaved `(x, y)` deformed control positions.
     * @param target Interleaved `(x, y)` original control positions.
     * @param count Number of control pairs in [source] / [target].
     * @param alpha Weight exponent; the paper's similarity deformation uses `1.0`.
     */
    fun solve(
        source: FloatArray,
        target: FloatArray,
        count: Int,
        u: Float,
        v: Float,
        alpha: Float,
        out: FloatArray
    ) {
        out[0] = u
        out[1] = v
        if (count <= 0) return
        var weightSum = 0.0f
        var sourceX = 0.0f
        var sourceY = 0.0f
        var targetX = 0.0f
        var targetY = 0.0f
        for (index in 0 until count) {
            val offset = index * 2
            val weight = weight(u - source[offset], v - source[offset + 1], alpha)
            weightSum += weight
            sourceX += weight * source[offset]
            sourceY += weight * source[offset + 1]
            targetX += weight * target[offset]
            targetY += weight * target[offset + 1]
        }
        if (!weightSum.isFinite() || weightSum <= EPSILON) return
        val sourceCentreX = sourceX / weightSum
        val sourceCentreY = sourceY / weightSum
        val targetCentreX = targetX / weightSum
        val targetCentreY = targetY / weightSum

        var momentA = 0.0f
        var momentB = 0.0f
        var momentC = 0.0f
        var momentD = 0.0f
        var sourceEnergy = 0.0f
        for (index in 0 until count) {
            val offset = index * 2
            val weight = weight(u - source[offset], v - source[offset + 1], alpha)
            val sourceDx = source[offset] - sourceCentreX
            val sourceDy = source[offset + 1] - sourceCentreY
            val targetDx = target[offset] - targetCentreX
            val targetDy = target[offset + 1] - targetCentreY
            // target ⊗ source, so the moments map a source-space offset onto target space.
            momentA += weight * targetDx * sourceDx
            momentB += weight * targetDx * sourceDy
            momentC += weight * targetDy * sourceDx
            momentD += weight * targetDy * sourceDy
            sourceEnergy += weight * (sourceDx * sourceDx + sourceDy * sourceDy)
        }
        if (sourceEnergy <= MOMENT_EPSILON) return
        val trace = momentA + momentD
        val skew = momentC - momentB
        if (!trace.isFinite() || !skew.isFinite()) return
        val angle = atan2(skew, trace)
        val cosine = cos(angle)
        val sine = sin(angle)
        val scale = ((cosine * trace + sine * skew) / sourceEnergy)
            .coerceIn(MIN_SCALE, MAX_SCALE)
            .takeIf { it.isFinite() } ?: 1.0f
        val offsetX = u - sourceCentreX
        val offsetY = v - sourceCentreY
        out[0] = targetCentreX + scale * (cosine * offsetX - sine * offsetY)
        out[1] = targetCentreY + scale * (sine * offsetX + cosine * offsetY)
    }

    private fun weight(dx: Float, dy: Float, alpha: Float): Float {
        val squared = dx * dx + dy * dy + EPSILON
        return if (alpha == 1.0f) 1.0f / squared else (1.0f / squared).powApprox(alpha)
    }

    private fun Float.powApprox(exponent: Float): Float =
        if (exponent == 0.0f) 1.0f else Math.pow(toDouble(), exponent.toDouble()).toFloat()
}
