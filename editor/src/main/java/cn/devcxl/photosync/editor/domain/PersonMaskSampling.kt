package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Sampling helpers for the `256 x 256` person probability mask the segmenters produce.
 *
 * Kept apart from [BodyWarpSolver] so the warp math stays pure arithmetic and every mask accessor
 * (bilinear probability, row centroid, vertical bounds) can be reused by the analysis pipeline.
 */
internal object PersonMaskSampling {

    /** Edge length of the person mask. */
    const val MASK_SIZE = 256

    /** Probability below which a pixel counts as background. */
    const val MIN_PROBABILITY = 0.02f

    /** True when [mask] is a `256 x 256` person probability. */
    fun isValid(mask: FloatArray?): Boolean = mask != null && mask.size == MASK_SIZE * MASK_SIZE

    /** Bilinearly samples [mask] at normalized [u], [v]; `1` when no mask is available. */
    fun probabilityAt(mask: FloatArray?, u: Float, v: Float): Float {
        if (mask == null) return 1.0f
        if (!isValid(mask)) return 1.0f
        val x = (u * MASK_SIZE - 0.5f).coerceIn(0.0f, MASK_SIZE - 1.0f)
        val y = (v * MASK_SIZE - 0.5f).coerceIn(0.0f, MASK_SIZE - 1.0f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, MASK_SIZE - 1)
        val y1 = min(y0 + 1, MASK_SIZE - 1)
        val fx = x - x0
        val fy = y - y0
        val top = mask[y0 * MASK_SIZE + x0] +
            (mask[y0 * MASK_SIZE + x1] - mask[y0 * MASK_SIZE + x0]) * fx
        val bottom = mask[y1 * MASK_SIZE + x0] +
            (mask[y1 * MASK_SIZE + x1] - mask[y1 * MASK_SIZE + x0]) * fx
        return (top + (bottom - top) * fy).coerceIn(0.0f, 1.0f)
    }

    /** Probability-weighted horizontal centroid of one mask [row], or `null` when it is empty. */
    fun centroidAt(mask: FloatArray?, row: Float): Float? {
        if (!isValid(mask)) return null
        val y = (row * MASK_SIZE).toInt().coerceIn(0, MASK_SIZE - 1)
        var weighted = 0.0f
        var total = 0.0f
        for (column in 0 until MASK_SIZE) {
            val probability = mask?.get(y * MASK_SIZE + column) ?: 0.0f
            if (probability <= MIN_PROBABILITY) continue
            weighted += probability * (column + 0.5f) / MASK_SIZE
            total += probability
        }
        return if (total > 0.0f) weighted / total else null
    }

    /** Fractional `[top, bottom]` rows where the mask first and last exceed [MIN_PROBABILITY]. */
    fun bounds(mask: FloatArray?): MaskBounds? {
        if (!isValid(mask)) return null
        var top = MASK_SIZE
        var bottom = -1
        for (row in 0 until MASK_SIZE) {
            var rowHasPerson = false
            for (column in 0 until MASK_SIZE) {
                val probability = mask?.get(row * MASK_SIZE + column) ?: 0.0f
                if (probability > MIN_PROBABILITY) {
                    rowHasPerson = true
                    break
                }
            }
            if (!rowHasPerson) continue
            top = min(top, row)
            bottom = max(bottom, row)
        }
        if (bottom < 0 || bottom < top) return null
        return MaskBounds(
            top = top.toFloat() / MASK_SIZE,
            bottom = (bottom + 1).toFloat() / MASK_SIZE
        )
    }
}

/** Vertical extent of the person mask, in normalized frame coordinates. */
internal data class MaskBounds(val top: Float, val bottom: Float)
