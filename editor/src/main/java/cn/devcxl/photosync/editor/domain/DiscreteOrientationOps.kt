package cn.devcxl.photosync.editor.domain

/**
 * Screen-space discrete orientation operations (`±90°` rotation and horizontal/vertical mirror)
 * over the 8-element dihedral symmetry group `D4`.
 *
 * `PassAGeometryCompiler` stores orientation canonically as `V^flipV ∘ H^flipH ∘ R^rotate90`.
 * Because mirrors invert rotation handedness (`R ∘ H = H ∘ R^-1`), incrementing `rotate90` naively
 * when an odd number of flips is active turns the *displayed* image counter-clockwise instead of
 * clockwise. These helpers commute the requested screen-space action cleanly through any active
 * flip, straighten angle and crop rectangle so every tap always moves the picture in the exact
 * visual direction the button icon shows.
 */
object DiscreteOrientationOps {

    private const val QUARTER_TURNS = 4

    /** Rotates the displayed frame 90 degrees clockwise in screen space. */
    fun rotateClockwise90(geometry: GeometryOps): GeometryOps {
        val oddFlips = geometry.flipH xor geometry.flipV
        val delta = if (oddFlips) 3 else 1
        return geometry.copy(
            rotate90 = normalizeQuarterTurns(geometry.rotate90 + delta),
            crop = rotateCropClockwise(geometry.crop)
        )
    }

    /** Rotates the displayed frame 90 degrees counter-clockwise in screen space. */
    fun rotateCounterClockwise90(geometry: GeometryOps): GeometryOps {
        val oddFlips = geometry.flipH xor geometry.flipV
        val delta = if (oddFlips) 1 else 3
        return geometry.copy(
            rotate90 = normalizeQuarterTurns(geometry.rotate90 + delta),
            crop = rotateCropCounterClockwise(geometry.crop)
        )
    }

    /** Mirrors the displayed frame left-to-right in screen space. */
    fun toggleFlipHorizontal(geometry: GeometryOps): GeometryOps = geometry.copy(
        flipH = !geometry.flipH,
        straighten = if (geometry.straighten == 0.0f) 0.0f else -geometry.straighten,
        crop = mirrorCropHorizontal(geometry.crop)
    )

    /** Mirrors the displayed frame top-to-bottom in screen space. */
    fun toggleFlipVertical(geometry: GeometryOps): GeometryOps = geometry.copy(
        flipV = !geometry.flipV,
        straighten = if (geometry.straighten == 0.0f) 0.0f else -geometry.straighten,
        crop = mirrorCropVertical(geometry.crop)
    )

    /** Resets all discrete orientation flags (rotation and flips) to identity. */
    fun resetOrientation(geometry: GeometryOps): GeometryOps = geometry.copy(
        rotate90 = 0,
        flipH = false,
        flipV = false
    )

    /** True when any discrete rotation or flip is active. */
    fun isOrientationModified(geometry: GeometryOps): Boolean =
        normalizeQuarterTurns(geometry.rotate90) != 0 || geometry.flipH || geometry.flipV

    private fun normalizeQuarterTurns(turns: Int): Int =
        ((turns % QUARTER_TURNS) + QUARTER_TURNS) % QUARTER_TURNS

    private fun rotateCropClockwise(crop: CropRect): CropRect =
        if (crop.isIdentity()) {
            crop
        } else {
            CropRect(
                left = 1.0f - crop.bottom,
                top = crop.left,
                right = 1.0f - crop.top,
                bottom = crop.right
            )
        }

    private fun rotateCropCounterClockwise(crop: CropRect): CropRect =
        if (crop.isIdentity()) {
            crop
        } else {
            CropRect(
                left = crop.top,
                top = 1.0f - crop.right,
                right = crop.bottom,
                bottom = 1.0f - crop.left
            )
        }

    private fun mirrorCropHorizontal(crop: CropRect): CropRect =
        if (crop.isIdentity()) {
            crop
        } else {
            CropRect(
                left = 1.0f - crop.right,
                top = crop.top,
                right = 1.0f - crop.left,
                bottom = crop.bottom
            )
        }

    private fun mirrorCropVertical(crop: CropRect): CropRect =
        if (crop.isIdentity()) {
            crop
        } else {
            CropRect(
                left = crop.left,
                top = 1.0f - crop.bottom,
                right = crop.right,
                bottom = 1.0f - crop.top
            )
        }
}
