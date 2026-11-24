package cn.devcxl.photosync.editor.domain

/**
 * One face detected by MediaPipe Face Landmarker: the 478 canonical mesh points in normalized
 * image coordinates, where `(0, 0)` is the top-left corner and `(1, 1)` the bottom-right.
 *
 * Each point stores `(x, y, z)` in [points]; `z` is MediaPipe's relative depth and is unused by the
 * solvers, which work in the image plane only.
 */
class FaceLandmarks(val points: FloatArray) {

    init {
        require(points.size >= POINT_COUNT * STRIDE) {
            "A face mesh needs ${POINT_COUNT * STRIDE} floats, got ${points.size}"
        }
    }

    /** Normalized horizontal coordinate of [index]. */
    fun x(index: Int): Float = points[index * STRIDE]

    /** Normalized vertical coordinate of [index]. */
    fun y(index: Int): Float = points[index * STRIDE + 1]

    /** The normalized image-plane position of [index]. */
    fun uv(index: Int): Uv = Uv(x(index), y(index))

    /**
     * The rotation-invariant local coordinate frame of this face.
     *
     * The origin sits on the nose bridge ([FaceLandmarkIndices.NOSE_BRIDGE]), the horizontal unit
     * axis runs from the right eye outer corner ([FaceLandmarkIndices.RIGHT_EYE_OUTER]) towards the
     * left one ([FaceLandmarkIndices.LEFT_EYE_OUTER]) and the vertical axis is that vector rotated
     * 90 degrees clockwise, which points from the brow towards the chin even when the head is
     * tilted. Width and height are the cheekbone and brow-to-chin spans.
     */
    fun frame(): FaceFrame = FaceMlsSolver.faceFrame(this)

    /** Axis-aligned bounding box area of all mesh points, in normalized frame units squared. */
    fun boundingBoxArea(): Float {
        if (points.isEmpty()) return 0.0f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var index = 0
        while (index < POINT_COUNT) {
            val px = x(index)
            val py = y(index)
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
            index++
        }
        return (maxX - minX).coerceAtLeast(0.0f) * (maxY - minY).coerceAtLeast(0.0f)
    }

    companion object {
        /** Number of landmarks MediaPipe's canonical face mesh reports. */
        const val POINT_COUNT = 478

        /** Floats per landmark: `x`, `y`, `z`. */
        const val STRIDE = 3

        /** Wraps a `478 x 3` flat array. */
        fun fromFlat(points: FloatArray): FaceLandmarks = FaceLandmarks(points)
    }
}

/**
 * A face-local, rotation-invariant orthonormal frame.
 *
 * @property origin Nose-bridge position `O_f` ([FaceLandmarkIndices.NOSE_BRIDGE]).
 * @property u Unit vector from the right to the left eye outer corner.
 * @property v Unit vector of [u] rotated 90 degrees clockwise (brow towards chin).
 * @property width Cheekbone span `||P234 - P454||`.
 * @property height Brow-to-chin span `||P10 - P152||`.
 */
data class FaceFrame(
    val origin: Uv,
    val u: Uv,
    val v: Uv,
    val width: Float,
    val height: Float
) {

    /** Projects [point] onto the horizontal axis: positive towards the face's left side. */
    fun dotU(point: Uv): Float =
        (point.u - origin.u) * u.u + (point.v - origin.v) * u.v

    /** Projects [point] onto the vertical axis: positive towards the chin. */
    fun dotV(point: Uv): Float =
        (point.u - origin.u) * v.u + (point.v - origin.v) * v.v

    /** True when [point] is horizontally on the face's left half. */
    fun isOnLeft(point: Uv): Boolean = dotU(point) >= 0.0f
}
