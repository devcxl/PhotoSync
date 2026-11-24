package cn.devcxl.photosync.editor.domain

/**
 * Canonical MediaPipe face-mesh landmark indices used by the portrait group.
 *
 * The indices are the ones MediaPipe publishes for the 478-point canonical mesh (468 mesh points
 * plus 10 iris points). Grouping them here keeps [FaceMlsSolver] and [SkinMaskMath] reading the
 * same tables, so a reshape axis and the protective hole it must not smooth can never drift apart.
 */
object FaceLandmarkIndices {

    /** Nose bridge: origin `O_f` of the local face frame. */
    const val NOSE_BRIDGE = 168

    /** Right eye outer corner, the tail of the face's horizontal axis. */
    const val RIGHT_EYE_OUTER = 33

    /** Left eye outer corner, the head of the face's horizontal axis. */
    const val LEFT_EYE_OUTER = 263

    /** Right cheekbone, one end of the face width ruler. */
    const val RIGHT_CHEEK = 234

    /** Left cheekbone, the other end of the face width ruler. */
    const val LEFT_CHEEK = 454

    /** Forehead top, one end of the face height ruler. */
    const val BROW_TOP = 10

    /** Chin tip, the other end of the face height ruler. */
    const val CHIN_TIP = 152

    /** Right iris centre. */
    const val RIGHT_IRIS = 468

    /** Left iris centre. */
    const val LEFT_IRIS = 473

    /** Left mouth corner. */
    const val MOUTH_LEFT = 61

    /** Right mouth corner. */
    const val MOUTH_RIGHT = 291

    /** Left jaw line, ordered from the ear towards the chin. */
    val LEFT_JAW = intArrayOf(132, 58, 172, 136, 150, 149, 176, 148)

    /** Right jaw line, ordered from the chin towards the ear. */
    val RIGHT_JAW = intArrayOf(361, 288, 397, 365, 379, 378, 400, 377)

    /**
     * Slimming weight of each jaw point, keyed by landmark index: the gonion (`172` / `397`) moves
     * the most, while the chin-adjacent points barely move so the chin stays pointed.
     */
    val JAW_SLIM_WEIGHTS: Map<Int, Float> = mapOf(
        172 to 1.00f, 397 to 1.00f,
        136 to 0.90f, 365 to 0.90f,
        150 to 0.75f, 379 to 0.75f,
        149 to 0.60f, 378 to 0.60f,
        176 to 0.45f, 400 to 0.45f,
        132 to 0.35f, 361 to 0.35f,
        58 to 0.25f, 288 to 0.25f,
        148 to 0.10f, 377 to 0.10f
    )

    /** Right eye ring, ordered around the eye. */
    val RIGHT_EYE_RING = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)

    /** Left eye ring, ordered around the eye. */
    val LEFT_EYE_RING = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)

    /** Right eyebrow ridge, ear to nose. */
    val RIGHT_BROW = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)

    /** Left eyebrow ridge, nose to ear. */
    val LEFT_BROW = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)

    /** Left nostril wing. */
    val LEFT_NOSTRIL = intArrayOf(129, 49, 131, 115, 218)

    /** Right nostril wing. */
    val RIGHT_NOSTRIL = intArrayOf(358, 279, 360, 344, 438)

    /** Nose tip, the anchor the nostril wings pinch towards. */
    const val NOSE_TIP = 1

    /** Chin bottom arc. */
    val CHIN_ARC = intArrayOf(148, 152, 377)

    /** Jaw corners that anchor the chin arc. */
    val CHIN_ANCHORS = intArrayOf(136, 365)

    /** Lower-lip centre that anchors the chin arc. */
    const val LOWER_LIP_CENTRE = 17

    /** Forehead top arc. */
    val FOREHEAD_ARC = intArrayOf(103, 67, 109, 10, 338, 297, 332)

    /** Brow ridge anchors that keep the forehead stretch local. */
    val FOREHEAD_ANCHORS = intArrayOf(70, 105, 334, 300)

    /** Left mouth corner cluster. */
    val LEFT_SMILE = intArrayOf(61, 146, 91)

    /** Right mouth corner cluster. */
    val RIGHT_SMILE = intArrayOf(291, 375, 321)

    /** Upper lip centre, a smile anchor. */
    const val UPPER_LIP_CENTRE = 0

    /** Outer lip contour, ordered from the left corner around the upper lip to the right corner. */
    val OUTER_LIPS = intArrayOf(
        61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146
    )
}
