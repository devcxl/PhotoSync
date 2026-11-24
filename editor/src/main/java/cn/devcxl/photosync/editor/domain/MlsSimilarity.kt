package cn.devcxl.photosync.editor.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Closed-form moving-least-squares (MLS) **similarity** deformation of Schaefer et al. 2006.
 *
 * Evaluates the optimal rotation + uniform scale (clamped to `[0.5, 2.0]` to guard degenerate
 * near-collinear control clusters) directly from weighted moment matrices, keeping every identity
 * correspondence at exact zero displacement.
 */
internal object MlsSimilarity {

    private const val EPSILON = 1e-8f
    private const val MOMENT_EPSILON = 1e-12f
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 2.0f

    /** Solves the inverse warp at [u], [v] and writes the sampling coordinate into [out][0..1]. */
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
        val centroids = weightedCentroids(source, target, count, u, v, alpha) ?: return
        solveFromCentroids(source, target, count, u, v, alpha, centroids, out)
    }

    private fun weightedCentroids(
        source: FloatArray,
        target: FloatArray,
        count: Int,
        u: Float,
        v: Float,
        alpha: Float
    ): FloatArray? {
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
        if (!weightSum.isFinite() || weightSum <= EPSILON) return null
        return floatArrayOf(sourceX / weightSum, sourceY / weightSum, targetX / weightSum, targetY / weightSum)
    }

    private fun solveFromCentroids(
        source: FloatArray,
        target: FloatArray,
        count: Int,
        u: Float,
        v: Float,
        alpha: Float,
        centroids: FloatArray,
        out: FloatArray
    ) {
        val sourceCentreX = centroids[0]
        val sourceCentreY = centroids[1]
        val targetCentreX = centroids[2]
        val targetCentreY = centroids[3]
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
