package cn.devcxl.photosync.editor.domain

/**
 * Everything the portrait group needs from one analysis of the current photo.
 *
 * The analysis runs once when the user opens the Portrait tab (and again after the optional
 * multiclass model finishes downloading), so sliders never trigger a MediaPipe inference.
 *
 * @property faces Detected faces in descending bounding-box size, already capped at five.
 * @property skinMask `256 x 256` dual-mode skin weight that drives Stage 0 and the sharpening
 *   suppression.
 * @property personMask `256 x 256` person probability when the analysis could produce one; the body
 *   guides and the waist/leg masking use it.
 * @property guides Resolved waist / hip lines, seeded from the main face and the mask centroid.
 * @property usesMulticlass True when [skinMask] came from the six-class model rather than the
 *   offline person-mask fallback.
 */
class PortraitAnalysis(
    val faces: List<FaceLandmarks>,
    val skinMask: FloatArray,
    val personMask: FloatArray?,
    val guides: BodyGuides,
    val usesMulticlass: Boolean
) {

    /** The largest detected face, the one the body guides are derived from. */
    val mainFace: FaceLandmarks?
        get() = faces.firstOrNull()

    /** Body warp mesh for the persisted `portrait.body` sliders. */
    fun bodyWarp(body: BodyOps): FloatArray = BodyWarpSolver.solve(
        waist = body.waist / SLIDER_SCALE,
        legs = body.legs / SLIDER_SCALE,
        guides = BodyWarpSolver.resolveGuides(mainFace, personMask, body),
        personMask = personMask
    )

    /** Face MLS mesh for the persisted `portrait.reshape` sliders. */
    fun faceWarp(reshape: ReshapeOps): FloatArray =
        FaceMlsSolver.solve(faces, FaceReshapeWeights.from(reshape))

    private companion object {
        private const val SLIDER_SCALE = 100.0f
    }
}
