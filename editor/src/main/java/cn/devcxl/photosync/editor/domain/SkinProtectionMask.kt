package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.min

/**
 * The five feather-blurred protective polygons that keep eyes, eyebrows and lips out of skin
 * smoothing.
 *
 * Each detected face contributes its right and left eye ring, its right and left eyebrow ridge and
 * its outer lip contour. The polygons are rasterized on the `256 x 256` analysis mask and then
 * blurred with a `3 px` Gaussian, so the protection fades over three pixels instead of stamping a
 * hard edge into the skin mask.
 */
internal object SkinProtectionMask {

    /** Edge length of the analysis mask. */
    const val MASK_SIZE = 256

    /** Gaussian radius applied to the rasterized polygons. */
    const val FEATHER_RADIUS_PX = 3

    /** Half thickness of a rasterized brow band, in normalized frame units. */
    const val BROW_HALF_THICKNESS = 0.012f

    /**
     * Rasterizes and feathers the protective holes of every face in [faces].
     *
     * @return `256 x 256` weights where `1` means "never smooth this pixel".
     */
    fun holeMask(faces: List<FaceLandmarks>): FloatArray {
        val raw = FloatArray(MASK_SIZE * MASK_SIZE)
        for (face in faces) {
            for (polygon in polygonsOf(face)) {
                rasterize(polygon, raw)
            }
        }
        return SeparableGaussian.blur(raw, MASK_SIZE, MASK_SIZE, FEATHER_RADIUS_PX)
            .let { blurred -> FloatArray(blurred.size) { blurred[it].coerceIn(0.0f, 1.0f) } }
    }

    /** The five closed contours of [face] as interleaved `(u, v)` arrays. */
    fun polygonsOf(face: FaceLandmarks): List<FloatArray> = listOf(
        polygon(face, FaceLandmarkIndices.RIGHT_EYE_RING),
        polygon(face, FaceLandmarkIndices.LEFT_EYE_RING),
        browStrip(face, FaceLandmarkIndices.RIGHT_BROW),
        browStrip(face, FaceLandmarkIndices.LEFT_BROW),
        polygon(face, FaceLandmarkIndices.OUTER_LIPS)
    )

    /**
     * Expands a brow ridge into a closed strip.
     *
     * The canonical brow landmarks are ordered *along* the ridge, so closing them directly would
     * only rasterize a zero-area sliver. Offsetting the ridge by [BROW_HALF_THICKNESS] upwards and
     * back downwards produces the band that actually protects the eyebrow.
     */
    private fun browStrip(face: FaceLandmarks, ridge: IntArray): FloatArray {
        val polygon = FloatArray(ridge.size * 4)
        ridge.forEachIndexed { position, index ->
            val point = face.uv(index)
            polygon[position * 2] = point.u
            polygon[position * 2 + 1] = point.v - BROW_HALF_THICKNESS
            val mirrored = (ridge.size * 2 - 1 - position) * 2
            polygon[mirrored] = point.u
            polygon[mirrored + 1] = point.v + BROW_HALF_THICKNESS
        }
        return polygon
    }

    /** Fills [target] with `1` inside [polygon] using the even-odd rule. */
    fun rasterize(polygon: FloatArray, target: FloatArray) {
        val points = polygon.size / 2
        if (points < 3) return
        var minU = Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (index in 0 until points) {
            val u = polygon[index * 2]
            val v = polygon[index * 2 + 1]
            minU = min(minU, u)
            maxU = max(maxU, u)
            minV = min(minV, v)
            maxV = max(maxV, v)
        }
        val firstX = ((minU * MASK_SIZE).toInt() - 1).coerceIn(0, MASK_SIZE - 1)
        val lastX = ((maxU * MASK_SIZE).toInt() + 1).coerceIn(0, MASK_SIZE - 1)
        val firstY = ((minV * MASK_SIZE).toInt() - 1).coerceIn(0, MASK_SIZE - 1)
        val lastY = ((maxV * MASK_SIZE).toInt() + 1).coerceIn(0, MASK_SIZE - 1)
        for (y in firstY..lastY) {
            val sampleV = (y + 0.5f) / MASK_SIZE
            for (x in firstX..lastX) {
                val sampleU = (x + 0.5f) / MASK_SIZE
                if (contains(polygon, points, sampleU, sampleV)) {
                    target[y * MASK_SIZE + x] = 1.0f
                }
            }
        }
    }

    /** Even-odd point-in-polygon test. */
    internal fun contains(polygon: FloatArray, points: Int, u: Float, v: Float): Boolean {
        var inside = false
        var previous = points - 1
        for (current in 0 until points) {
            val currentU = polygon[current * 2]
            val currentV = polygon[current * 2 + 1]
            val previousU = polygon[previous * 2]
            val previousV = polygon[previous * 2 + 1]
            val straddles = (currentV > v) != (previousV > v)
            if (straddles) {
                val crossingU = (previousU - currentU) * (v - currentV) / (previousV - currentV) + currentU
                if (u < crossingU) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun polygon(face: FaceLandmarks, indices: IntArray): FloatArray {
        val polygon = FloatArray(indices.size * 2)
        indices.forEachIndexed { position, index ->
            polygon[position * 2] = face.x(index)
            polygon[position * 2 + 1] = face.y(index)
        }
        return polygon
    }
}
