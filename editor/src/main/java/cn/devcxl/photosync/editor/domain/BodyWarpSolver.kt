package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The horizontal guide lines that drive Body Reshaping.
 *
 * Only [waistY] and [hipY] are user-visible and persisted in `EditGraph.portrait.body`; the waist
 * band height and the horizontal pinch centre are derived from the analysis and never persisted, so
 * a reopened edit re-derives them from the photo instead of freezing the old segmentation.
 *
 * All values are normalized frame coordinates: `y` grows downwards.
 */
data class BodyGuides(
    val waistY: Float = DEFAULT_WAIST_Y,
    val hipY: Float = DEFAULT_HIP_Y,
    val waistHalfHeight: Float = DEFAULT_WAIST_HALF_HEIGHT,
    val centreX: Float = DEFAULT_CENTRE_X
) {

    /** Clamps every field into a legal frame range and guarantees `hipY > waistY`. */
    fun sanitized(): BodyGuides {
        val safeWaist = waistY.sanitized(MIN_GUIDE_Y, MAX_WAIST_Y)
        val safeHip = hipY.sanitized(safeWaist + MIN_GUIDE_GAP, MAX_HIP_Y)
        return BodyGuides(
            waistY = safeWaist,
            hipY = safeHip,
            waistHalfHeight = waistHalfHeight.sanitized(MIN_HALF_HEIGHT, MAX_HALF_HEIGHT),
            centreX = centreX.sanitized(0.0f, 1.0f)
        )
    }

    private fun Float.sanitized(minimum: Float, maximum: Float): Float =
        if (isFinite()) coerceIn(minimum, maximum) else minimum

    companion object {
        /** Default waist line when neither a face nor a person mask is available. */
        const val DEFAULT_WAIST_Y = 0.55f

        /** Default hip line when neither a face nor a person mask is available. */
        const val DEFAULT_HIP_Y = 0.66f

        /** Half height of the waist pinch band, as a fraction of the frame. */
        const val DEFAULT_WAIST_HALF_HEIGHT = 0.09f

        /** Horizontal pinch centre; `0.5` is the frame centre. */
        const val DEFAULT_CENTRE_X = 0.5f

        /** Lowest legal waist line. */
        const val MIN_GUIDE_Y = 0.2f

        /** Highest legal waist line. */
        const val MAX_WAIST_Y = 0.85f

        /** Highest legal hip line. */
        const val MAX_HIP_Y = 0.9f

        /** Smallest gap between the waist and hip lines. */
        const val MIN_GUIDE_GAP = 0.02f

        private const val MIN_HALF_HEIGHT = 0.02f
        private const val MAX_HALF_HEIGHT = 0.3f
    }
}

/**
 * Body reshaping without a pose model: a horizontal waist pinch and a vertical leg stretch, both
 * driven by the person mask.
 *
 * ## Waist
 * The waist pinch pulls pixels towards the waist row's horizontal centroid, weighted by a `C^1`
 * cosine bell over [BodyGuides.waistHalfHeight]. The displacement is multiplied by the person
 * probability, so a background wall next to the waist never bends.
 *
 * ## Legs
 * Leg lengthening applies the `C^1` vertical inverse map
 * `y_src = y - 0.12 s (y - y_hip)^2 / (1 - y_hip) * P_person(x, y)` below the hip line. The squared
 * term makes both the value and its slope vanish at the hip line, so the stretch joins the
 * untouched torso without a crease, and because the map only ever samples upwards inside the same
 * frame the output keeps the source aspect ratio (the overflow below the frame is cropped away) and
 * the background under the legs stays still.
 */
object BodyWarpSolver {

    /** Edge length of the published displacement grid. */
    const val GRID_SIZE = 128

    /** Edge length of the person mask the solver samples. */
    const val MASK_SIZE = PersonMaskSampling.MASK_SIZE

    /** Horizontal centre pull at `waist = +1`, as a fraction of the half width. */
    const val WAIST_SLIM_GAIN = 0.10f

    /** Vertical stretch at `legs = +1`, as a fraction of the hip-to-bottom span. */
    const val LEG_STRETCH_GAIN = 0.12f

    /** Lowest hip-to-bottom span the leg map divides by. */
    const val MIN_LEG_SPAN = 0.05f

    /** Face-frame multipliers used for the default guides: waist below the nose bridge. */
    const val WAIST_FACE_HEIGHT_FACTOR = 2.8f

    /** Face-frame multipliers used for the default guides: hip below the nose bridge. */
    const val HIP_FACE_HEIGHT_FACTOR = 3.6f

    /** Waist band half height in face heights. */
    const val WAIST_FACE_BAND_FACTOR = 1.1f

    /** Waist line position inside the person bounding box when no face is detected. */
    const val PERSON_WAIST_FRACTION = 0.45f

    /** Hip line position inside the person bounding box when no face is detected. */
    const val PERSON_HIP_FRACTION = 0.55f

    private const val CHANNELS = 2
    private const val MIN_PERSON_PROBABILITY = 0.02f
    private const val PERSON_BAND_FRACTION = 0.06f
    private const val MIN_FACE_BAND_FACTOR = 0.25f
    private const val MAX_FACE_BAND_FACTOR = 3.0f
    private const val MIN_PERSON_BAND_FACTOR = 0.3f
    private const val MAX_PERSON_BAND_FACTOR = 2.0f

    /**
     * Derives the guide lines for [face] / [personMask], honouring the user's persisted overrides.
     *
     * @param face The largest detected face, or `null` when nobody faces the camera.
     * @param personMask `256 x 256` person probability, or `null` when segmentation is unavailable.
     * @param body Persisted `portrait.body` values; `waistY` / `hipY` win over the derived defaults.
     */
    fun resolveGuides(
        face: FaceLandmarks?,
        personMask: FloatArray?,
        body: BodyOps = BodyOps()
    ): BodyGuides {
        val frame = face?.let { FaceMlsSolver.faceFrame(it) }
        if (frame != null) {
            val waist = (frame.origin.v + WAIST_FACE_HEIGHT_FACTOR * frame.height)
                .coerceIn(BodyGuides.MIN_GUIDE_Y, BodyGuides.MAX_WAIST_Y)
            val hip = (frame.origin.v + HIP_FACE_HEIGHT_FACTOR * frame.height)
                .coerceIn(BodyGuides.MIN_GUIDE_Y + BodyGuides.MIN_GUIDE_GAP, BodyGuides.MAX_HIP_Y)
            return BodyGuides(
                waistY = body.waistY ?: waist,
                hipY = body.hipY ?: hip,
                waistHalfHeight = (WAIST_FACE_BAND_FACTOR * frame.height).coerceIn(
                    BodyGuides.DEFAULT_WAIST_HALF_HEIGHT * MIN_FACE_BAND_FACTOR,
                    BodyGuides.DEFAULT_WAIST_HALF_HEIGHT * MAX_FACE_BAND_FACTOR
                ),
                centreX = PersonMaskSampling.centroidAt(personMask, waist) ?: BodyGuides.DEFAULT_CENTRE_X
            ).sanitized()
        }
        val bounds = PersonMaskSampling.bounds(personMask)
        if (bounds != null) {
            val span = bounds.bottom - bounds.top
            val waist = bounds.top + PERSON_WAIST_FRACTION * span
            val hip = bounds.top + PERSON_HIP_FRACTION * span
            return BodyGuides(
                waistY = body.waistY ?: waist,
                hipY = body.hipY ?: hip,
                waistHalfHeight = (PERSON_BAND_FRACTION * span).coerceIn(
                    BodyGuides.DEFAULT_WAIST_HALF_HEIGHT * MIN_PERSON_BAND_FACTOR,
                    BodyGuides.DEFAULT_WAIST_HALF_HEIGHT * MAX_PERSON_BAND_FACTOR
                ),
                centreX = PersonMaskSampling.centroidAt(personMask, waist) ?: BodyGuides.DEFAULT_CENTRE_X
            ).sanitized()
        }
        return BodyGuides(
            waistY = body.waistY ?: BodyGuides.DEFAULT_WAIST_Y,
            hipY = body.hipY ?: BodyGuides.DEFAULT_HIP_Y
        ).sanitized()
    }

    /**
     * Analytic displacement of the body warp at normalized [u], [v].
     *
     * @param waist Normalized waist slider in `[-1, 1]`.
     * @param legs Normalized leg slider in `[-1, 1]`.
     * @param personMask `256 x 256` person probability; `null` treats the whole frame as person.
     */
    fun displacementAt(
        waist: Float,
        legs: Float,
        guides: BodyGuides,
        personMask: FloatArray?,
        u: Float,
        v: Float
    ): Uv {
        if (!u.isFinite() || !v.isFinite()) return Uv(0.0f, 0.0f)
        if (abs(waist) < 1e-4f && abs(legs) < 1e-4f) return Uv(0.0f, 0.0f)
        val probability = PersonMaskSampling.probabilityAt(personMask, u, v)
        if (probability <= 0.0f) return Uv(0.0f, 0.0f)
        val safeWaist = waist.coerceIn(-1.0f, 1.0f) * probability
        val safeLegs = legs.coerceIn(-1.0f, 1.0f) * probability
        val horizontal = if (abs(safeWaist) < 1e-6f) {
            0.0f
        } else {
            -WAIST_SLIM_GAIN * safeWaist * (u - guides.centreX) *
                waistBell(v, guides.waistY, guides.waistHalfHeight)
        }
        val vertical = if (abs(safeLegs) < 1e-6f || v <= guides.hipY) {
            0.0f
        } else {
            val span = (1.0f - guides.hipY).coerceAtLeast(MIN_LEG_SPAN)
            val offset = v - guides.hipY
            -LEG_STRETCH_GAIN * safeLegs * offset * offset / span
        }
        return Uv(horizontal, vertical)
    }

    /** Solves the body warp on the [GRID_SIZE] grid, interleaved `(dx, dy)` row-major. */
    fun solve(
        waist: Float,
        legs: Float,
        guides: BodyGuides,
        personMask: FloatArray?
    ): FloatArray {
        val grid = FloatArray(GRID_SIZE * GRID_SIZE * CHANNELS)
        if (waist == 0.0f && legs == 0.0f) return grid
        val safe = guides.sanitized()
        for (row in 0 until GRID_SIZE) {
            val v = (row + 0.5f) / GRID_SIZE
            for (column in 0 until GRID_SIZE) {
                val u = (column + 0.5f) / GRID_SIZE
                val displacement = displacementAt(waist, legs, safe, personMask, u, v)
                val cell = (row * GRID_SIZE + column) * CHANNELS
                grid[cell] = displacement.u
                grid[cell + 1] = displacement.v
            }
        }
        return grid
    }

    /** True when the analysis produced a usable person mask. */
    fun isValidMask(mask: FloatArray?): Boolean = PersonMaskSampling.isValid(mask)

    /** Cosine bell `C^1` around [centre] with half width [halfHeight]. */
    internal fun waistBell(v: Float, centre: Float, halfHeight: Float): Float {
        val distance = abs(v - centre)
        val half = halfHeight.coerceAtLeast(1e-4f)
        if (distance >= half) return 0.0f
        return 0.5f * (1.0f + cos(PI.toFloat() * (v - centre) / half))
    }
}
