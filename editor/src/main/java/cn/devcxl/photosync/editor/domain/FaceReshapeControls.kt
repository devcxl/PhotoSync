package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

/**
 * Normalized strengths of the six face-reshaping axes, each in `[-1, 1]`.
 *
 * [from] converts the persisted `-100..100` [ReshapeOps] sliders; a positive value always means
 * "more of the effect" (slimmer face, larger eyes, tighter nose, longer chin, higher forehead,
 * wider smile).
 */
data class FaceReshapeWeights(
    val face: Float = 0.0f,
    val eyes: Float = 0.0f,
    val nose: Float = 0.0f,
    val chin: Float = 0.0f,
    val forehead: Float = 0.0f,
    val smile: Float = 0.0f
) {

    /** True when every axis is neutral, so the solver can return an all-zero grid. */
    val isIdentity: Boolean
        get() = abs(face) < TOLERANCE &&
            abs(eyes) < TOLERANCE &&
            abs(nose) < TOLERANCE &&
            abs(chin) < TOLERANCE &&
            abs(forehead) < TOLERANCE &&
            abs(smile) < TOLERANCE

    /** Clamps every axis into `[-1, 1]`, rejecting `NaN`. */
    fun sanitized(): FaceReshapeWeights = FaceReshapeWeights(
        face = face.sanitized(),
        eyes = eyes.sanitized(),
        nose = nose.sanitized(),
        chin = chin.sanitized(),
        forehead = forehead.sanitized(),
        smile = smile.sanitized()
    )

    private fun Float.sanitized(): Float =
        if (isFinite()) coerceIn(-1.0f, 1.0f) else 0.0f

    companion object {
        private const val TOLERANCE = 1e-4f
        private const val SLIDER_SCALE = 100.0f

        /** Normalizes the `portrait.reshape` sliders of a persisted graph. */
        fun from(ops: ReshapeOps): FaceReshapeWeights = FaceReshapeWeights(
            face = ops.face / SLIDER_SCALE,
            eyes = ops.eyes / SLIDER_SCALE,
            nose = ops.nose / SLIDER_SCALE,
            chin = ops.chin / SLIDER_SCALE,
            forehead = ops.forehead / SLIDER_SCALE,
            smile = ops.smile / SLIDER_SCALE
        ).sanitized()
    }
}

/**
 * The displacement one canonical landmark receives from the six reshape axes.
 *
 * @property index MediaPipe canonical mesh index.
 * @property du Horizontal displacement in normalized frame units.
 * @property dv Vertical displacement in normalized frame units.
 */
internal data class FaceReshapeControl(
    val index: Int,
    val du: Float,
    val dv: Float
)

/**
 * Translates [FaceReshapeWeights] into per-landmark displacements inside one face's local frame.
 *
 * Every formula is the one pinned by the portrait spec: the jaw slims towards the vertical axis with
 * a gonion-weighted falloff, the eye rings expand radially around their iris centres, the nostril
 * wings pinch towards the nose bridge, chin and forehead stretch along the vertical axis, and the
 * mouth corners lift diagonally. Anchor landmarks (cheekbones, brow ridge, lip centres) contribute
 * no displacement, which is what keeps neighbouring features still.
 */
internal object FaceReshapeControls {

    private const val FACE_SLIM_GAIN = 0.07f
    private const val EYE_SCALE_GAIN = 0.12f
    private const val NOSE_SLIM_GAIN = 0.08f
    private const val CHIN_GAIN = 0.06f
    private const val FOREHEAD_GAIN = 0.06f
    private const val SMILE_HORIZONTAL_GAIN = 0.035f
    private const val SMILE_VERTICAL_GAIN = 0.045f
    private const val SMILE_SECONDARY_FRACTION = 0.6f

    /**
     * Returns the merged displacement of every landmark the active axes move.
     *
     * Merging matters because one landmark can belong to more than one cluster (for example the jaw
     * corner `136` is both a jaw point and a chin anchor), and a landmark may appear only once in
     * the MLS correspondence set.
     */
    fun displacements(
        face: FaceLandmarks,
        frame: FaceFrame,
        weights: FaceReshapeWeights
    ): List<FaceReshapeControl> {
        val merged = LinkedHashMap<Int, FloatArray>()
        if (weights.face != 0.0f) slimFace(face, frame, weights.face, merged)
        if (weights.eyes != 0.0f) enlargeEyes(face, weights.eyes, merged)
        if (weights.nose != 0.0f) slimNose(face, frame, weights.nose, merged)
        if (weights.chin != 0.0f) stretchChin(frame, weights.chin, merged)
        if (weights.forehead != 0.0f) stretchForehead(frame, weights.forehead, merged)
        if (weights.smile != 0.0f) liftSmile(frame, weights.smile, merged)
        return merged.map { (index, delta) -> FaceReshapeControl(index, delta[0], delta[1]) }
    }

    private fun slimFace(
        face: FaceLandmarks,
        frame: FaceFrame,
        strength: Float,
        out: MutableMap<Int, FloatArray>
    ) {
        val gain = -FACE_SLIM_GAIN * strength * frame.width
        FaceLandmarkIndices.JAW_SLIM_WEIGHTS.forEach { (index, weight) ->
            val side = if (frame.isOnLeft(face.uv(index))) 1.0f else -1.0f
            val amount = gain * side * weight
            add(out, index, amount * frame.u.u, amount * frame.u.v)
        }
    }

    private fun enlargeEyes(
        face: FaceLandmarks,
        strength: Float,
        out: MutableMap<Int, FloatArray>
    ) {
        val gain = EYE_SCALE_GAIN * strength
        expandRing(face, FaceLandmarkIndices.RIGHT_EYE_RING, FaceLandmarkIndices.RIGHT_IRIS, gain, out)
        expandRing(face, FaceLandmarkIndices.LEFT_EYE_RING, FaceLandmarkIndices.LEFT_IRIS, gain, out)
    }

    private fun expandRing(
        face: FaceLandmarks,
        ring: IntArray,
        centreIndex: Int,
        gain: Float,
        out: MutableMap<Int, FloatArray>
    ) {
        val centre = face.uv(centreIndex)
        for (index in ring) {
            val point = face.uv(index)
            add(
                out,
                index,
                gain * (point.u - centre.u),
                gain * (point.v - centre.v)
            )
        }
    }

    private fun slimNose(
        face: FaceLandmarks,
        frame: FaceFrame,
        strength: Float,
        out: MutableMap<Int, FloatArray>
    ) {
        val tip = face.uv(FaceLandmarkIndices.NOSE_TIP)
        val gain = -NOSE_SLIM_GAIN * strength
        for (index in FaceLandmarkIndices.LEFT_NOSTRIL + FaceLandmarkIndices.RIGHT_NOSTRIL) {
            val offset = frame.dotU(face.uv(index)) - frame.dotU(tip)
            val amount = gain * offset
            add(out, index, amount * frame.u.u, amount * frame.u.v)
        }
    }

    private fun stretchChin(frame: FaceFrame, strength: Float, out: MutableMap<Int, FloatArray>) {
        val amount = CHIN_GAIN * strength * frame.height
        for (index in FaceLandmarkIndices.CHIN_ARC) {
            add(out, index, amount * frame.v.u, amount * frame.v.v)
        }
    }

    private fun stretchForehead(frame: FaceFrame, strength: Float, out: MutableMap<Int, FloatArray>) {
        val amount = -FOREHEAD_GAIN * strength * frame.height
        for (index in FaceLandmarkIndices.FOREHEAD_ARC) {
            add(out, index, amount * frame.v.u, amount * frame.v.v)
        }
    }

    private fun liftSmile(frame: FaceFrame, strength: Float, out: MutableMap<Int, FloatArray>) {
        val horizontal = SMILE_HORIZONTAL_GAIN * frame.width * strength
        val vertical = -SMILE_VERTICAL_GAIN * frame.height * strength
        liftCorner(FaceLandmarkIndices.LEFT_SMILE, 1.0f, horizontal, vertical, frame, out)
        liftCorner(FaceLandmarkIndices.RIGHT_SMILE, -1.0f, horizontal, vertical, frame, out)
    }

    private fun liftCorner(
        cluster: IntArray,
        side: Float,
        horizontal: Float,
        vertical: Float,
        frame: FaceFrame,
        out: MutableMap<Int, FloatArray>
    ) {
        cluster.forEachIndexed { position, index ->
            val fraction = if (position == 0) 1.0f else SMILE_SECONDARY_FRACTION
            val du = side * horizontal * fraction * frame.u.u + vertical * fraction * frame.v.u
            val dv = side * horizontal * fraction * frame.u.v + vertical * fraction * frame.v.v
            add(out, index, du, dv)
        }
    }

    private fun add(out: MutableMap<Int, FloatArray>, index: Int, du: Float, dv: Float) {
        if (du == 0.0f && dv == 0.0f) return
        val current = out[index]
        if (current == null) {
            out[index] = floatArrayOf(du, dv)
        } else {
            current[0] += du
            current[1] += dv
        }
    }
}
