package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic synthetic 478-point face meshes for the portrait solvers.
 *
 * Every landmark the portrait group reads is placed on a plausible anatomical position, expressed in
 * face-local units where the cheekbone span and the brow-to-chin span are both `1.0`. Unused mesh
 * points collapse onto the frame origin, so a fixture's bounding box is exactly the extent of the
 * landmarks that matter.
 */
internal object FaceMeshFixtures {

    private const val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()

    /** Local `(x, y)` positions keyed by canonical landmark index. */
    private val LOCAL_POSITIONS: Map<Int, Uv> = buildMap {
        fun put(index: Int, x: Float, y: Float) {
            this[index] = Uv(x, y)
        }

        // Frame rulers.
        put(FaceLandmarkIndices.NOSE_BRIDGE, 0.0f, 0.0f)
        put(FaceLandmarkIndices.RIGHT_EYE_OUTER, -0.42f, -0.12f)
        put(FaceLandmarkIndices.LEFT_EYE_OUTER, 0.42f, -0.12f)
        put(FaceLandmarkIndices.RIGHT_CHEEK, -0.5f, 0.05f)
        put(FaceLandmarkIndices.LEFT_CHEEK, 0.5f, 0.05f)
        put(FaceLandmarkIndices.BROW_TOP, 0.0f, -0.35f)
        put(FaceLandmarkIndices.CHIN_TIP, 0.0f, 0.65f)

        // Jaw lines.
        listOf(-1.0f, 1.0f).forEach { side ->
            val jaw = if (side < 0.0f) FaceLandmarkIndices.LEFT_JAW else FaceLandmarkIndices.RIGHT_JAW
            val xs = floatArrayOf(0.48f, 0.46f, 0.42f, 0.36f, 0.30f, 0.22f, 0.15f, 0.08f)
            val ys = floatArrayOf(0.20f, 0.30f, 0.42f, 0.50f, 0.56f, 0.60f, 0.63f, 0.65f)
            jaw.forEachIndexed { position, index -> put(index, side * xs[position], ys[position]) }
        }

        // Eye rings around the iris centres.
        putRing(FaceLandmarkIndices.RIGHT_EYE_RING, FaceLandmarkIndices.RIGHT_IRIS, -0.22f, 0.075f, 0.05f)
        putRing(FaceLandmarkIndices.LEFT_EYE_RING, FaceLandmarkIndices.LEFT_IRIS, 0.22f, 0.075f, 0.05f)

        // Brows.
        putBrow(FaceLandmarkIndices.RIGHT_BROW, -0.30f, -0.24f, 1.0f)
        putBrow(FaceLandmarkIndices.LEFT_BROW, 0.30f, -0.24f, -1.0f)

        // Nostrils and nose tip.
        putNostril(FaceLandmarkIndices.LEFT_NOSTRIL, -0.09f)
        putNostril(FaceLandmarkIndices.RIGHT_NOSTRIL, 0.09f)
        put(FaceLandmarkIndices.NOSE_TIP, 0.0f, 0.20f)

        // Chin arc plus its anchors.
        put(148, -0.08f, 0.64f)
        put(377, 0.08f, 0.64f)
        put(136, -0.36f, 0.50f)
        put(365, 0.36f, 0.50f)
        put(FaceLandmarkIndices.LOWER_LIP_CENTRE, 0.0f, 0.50f)

        // Forehead arc plus brow anchors.
        val forehead = FaceLandmarkIndices.FOREHEAD_ARC
        val foreheadX = floatArrayOf(-0.30f, -0.22f, -0.12f, 0.0f, 0.12f, 0.22f, 0.30f)
        forehead.forEachIndexed { position, index -> put(index, foreheadX[position], -0.35f) }
        // The brow anchors (70, 105, 334, 300) are brow landmarks and already placed above.

        // Mouth corners, their clusters and the lip centres.
        put(FaceLandmarkIndices.MOUTH_LEFT, -0.15f, 0.45f)
        put(146, -0.13f, 0.47f)
        put(91, -0.10f, 0.48f)
        put(FaceLandmarkIndices.MOUTH_RIGHT, 0.15f, 0.45f)
        put(375, 0.13f, 0.47f)
        put(321, 0.10f, 0.48f)
        put(FaceLandmarkIndices.UPPER_LIP_CENTRE, 0.0f, 0.42f)
        putLips()
    }

    /**
     * Builds one synthetic face.
     *
     * @param centreU Normalized image position of the nose bridge.
     * @param centreV Normalized image position of the nose bridge.
     * @param width Normalized cheekbone span.
     * @param height Normalized brow-to-chin span.
     * @param rotationDegrees Clockwise head tilt in the image plane.
     */
    fun face(
        centreU: Float,
        centreV: Float,
        width: Float,
        height: Float,
        rotationDegrees: Float = 0.0f
    ): FaceLandmarks {
        val points = FloatArray(FaceLandmarks.POINT_COUNT * FaceLandmarks.STRIDE)
        val radians = rotationDegrees * DEGREES_TO_RADIANS
        val cosine = cos(radians)
        val sine = sin(radians)
        for (index in 0 until FaceLandmarks.POINT_COUNT) {
            val local = LOCAL_POSITIONS[index] ?: Uv(0.0f, 0.0f)
            val scaledX = local.u * width
            val scaledY = local.v * height
            val offset = index * FaceLandmarks.STRIDE
            points[offset] = centreU + cosine * scaledX - sine * scaledY
            points[offset + 1] = centreV + sine * scaledX + cosine * scaledY
            points[offset + 2] = 0.0f
        }
        return FaceLandmarks(points)
    }

    private fun MutableMap<Int, Uv>.putRing(
        ring: IntArray,
        centreIndex: Int,
        centreU: Float,
        radiusU: Float,
        radiusV: Float
    ) {
        val centreV = -0.12f
        this[centreIndex] = Uv(centreU, centreV)
        ring.forEachIndexed { position, index ->
            val angle = (2.0 * PI * position / ring.size).toFloat()
            this[index] = Uv(centreU + cos(angle) * radiusU, centreV + sin(angle) * radiusV)
        }
    }

    private fun MutableMap<Int, Uv>.putBrow(brow: IntArray, centreU: Float, centreV: Float, direction: Float) {
        brow.forEachIndexed { position, index ->
            val fraction = position / (brow.size - 1).toFloat()
            this[index] = Uv(centreU + direction * (fraction - 0.5f) * 0.22f, centreV - 0.02f * fraction)
        }
    }

    private fun MutableMap<Int, Uv>.putNostril(nostril: IntArray, centreU: Float) {
        nostril.forEachIndexed { position, index ->
            this[index] = Uv(centreU + position * 0.012f, 0.10f + position * 0.015f)
        }
    }

    private fun MutableMap<Int, Uv>.putLips() {
        val lips = FaceLandmarkIndices.OUTER_LIPS
        lips.forEachIndexed { position, index ->
            val normalized = position / lips.size.toFloat()
            val angle = (2.0 * PI * normalized).toFloat()
            this[index] = Uv(cos(angle) * 0.15f, 0.46f + sin(angle) * 0.05f)
        }
    }
}
