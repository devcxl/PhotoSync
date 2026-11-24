package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A point in normalized UV space, where `(0, 0)` is the top-left corner of the frame and
 * `(1, 1)` is the bottom-right corner.
 */
data class Uv(val u: Float, val v: Float)

/**
 * Pure-Kotlin geometry compiler shared by the Pass A GLES resampling program and the CPU
 * preview-cache resampler.
 *
 * ## Orientation contract (never rotate twice)
 * Every working texture must already be upright before it reaches Pass A:
 * - RAW: LibRaw is invoked with `user_flip = -1`, which applies the camera orientation itself,
 *   so RAW bitmaps must **not** be rotated again.
 * - JPEG: `BitmapFactory` does not apply EXIF orientation, so the upload path applies exactly one
 *   transform described by [exifUprightUv].
 *
 * ## Coordinate conventions
 * - UV is `(0, 0)` top-left / `(1, 1)` bottom-right, so `v` grows downwards.
 * - Rotations are visual: `rotate90 = 1` turns the picture 90 degrees clockwise, matching the
 *   "rotate 90 degrees" button a photographer expects.
 * - Aspect-corrected ("physical") space uses `x in [-aspect/2, aspect/2]`, `y in [-1/2, 1/2]`
 *   so that a rotation is a true Euclidean rotation instead of a UV-space shear.
 */
object GeometryMath {

    internal const val EPSILON = 1e-6f
    internal const val MAX_STRAIGHTEN_DEGREES = 45.0f
    internal const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

    /**
     * Radial scale applied to the red and blue channels at a lateral-CA slider value of `+/-100`.
     */
    const val CA_MAX_RADIAL_SCALE = 0.005f

    private const val QUARTER_TURNS = 4
    private const val ORIENTATION_FLIP_HORIZONTAL = 2
    private const val ORIENTATION_ROTATE_180 = 3
    private const val ORIENTATION_FLIP_VERTICAL = 4
    private const val ORIENTATION_TRANSPOSE = 5
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_TRANSVERSE = 7
    private const val ORIENTATION_ROTATE_270 = 8

    /**
     * Maps a corner coordinate of the raw decoded bitmap to its upright coordinate for one of the
     * eight EXIF orientation values defined by EXIF 2.32.
     *
     * @param orientation EXIF `TAG_ORIENTATION` value; values outside `1..8` are treated as `1`.
     * @param u Raw horizontal normalized coordinate in `[0, 1]`.
     * @param v Raw vertical normalized coordinate in `[0, 1]`.
     * @return The upright coordinate that the same physical pixel occupies after normalization.
     */
    fun exifUprightUv(orientation: Int, u: Float, v: Float): Uv = when (orientation) {
        ORIENTATION_FLIP_HORIZONTAL -> Uv(1.0f - u, v)
        ORIENTATION_ROTATE_180 -> Uv(1.0f - u, 1.0f - v)
        ORIENTATION_FLIP_VERTICAL -> Uv(u, 1.0f - v)
        ORIENTATION_TRANSPOSE -> Uv(v, u)
        ORIENTATION_ROTATE_90 -> Uv(1.0f - v, u)
        ORIENTATION_TRANSVERSE -> Uv(1.0f - v, 1.0f - u)
        ORIENTATION_ROTATE_270 -> Uv(v, 1.0f - u)
        else -> Uv(u, v)
    }

    /**
     * Returns true when [orientation] swaps the frame's width and height, which happens for the
     * four orientations that carry a 90 or 270 degree rotation component.
     */
    fun swapsOrientationAxes(orientation: Int): Boolean = orientation in 5..8

    /**
     * Returns the `(quarterTurns, flipH, flipV)` combination that reproduces [orientation].
     *
     * This is the single table both the JPEG upload path and the interactive geometry sliders
     * consume, so a photo can never be rotated twice or mirrored twice by two disagreeing paths.
     */
    fun discreteOpsForExifOrientation(orientation: Int): ExifDiscreteOps = when (orientation) {
        ORIENTATION_FLIP_HORIZONTAL -> ExifDiscreteOps(0, flipH = true, flipV = false)
        ORIENTATION_ROTATE_180 -> ExifDiscreteOps(2, flipH = false, flipV = false)
        ORIENTATION_FLIP_VERTICAL -> ExifDiscreteOps(0, flipH = false, flipV = true)
        ORIENTATION_TRANSPOSE -> ExifDiscreteOps(1, flipH = true, flipV = false)
        ORIENTATION_ROTATE_90 -> ExifDiscreteOps(1, flipH = false, flipV = false)
        ORIENTATION_TRANSVERSE -> ExifDiscreteOps(3, flipH = true, flipV = false)
        ORIENTATION_ROTATE_270 -> ExifDiscreteOps(3, flipH = false, flipV = false)
        else -> ExifDiscreteOps(0, flipH = false, flipV = false)
    }

    /**
     * Applies the interactive discrete geometry controls in display order: a clockwise quarter turn
     * followed by a horizontal and then a vertical mirror of the *displayed* frame.
     *
     * This is the forward `source -> displayed` map; Pass A consumes the inverse.
     */
    fun discreteSourceToFrameUv(
        rotate90: Int,
        flipH: Boolean,
        flipV: Boolean,
        u: Float,
        v: Float
    ): Uv {
        var turnedU: Float
        var turnedV: Float
        when (normalizeQuarterTurns(rotate90)) {
            1 -> {
                turnedU = 1.0f - v
                turnedV = u
            }
            2 -> {
                turnedU = 1.0f - u
                turnedV = 1.0f - v
            }
            3 -> {
                turnedU = v
                turnedV = 1.0f - u
            }
            else -> {
                turnedU = u
                turnedV = v
            }
        }
        if (flipH) turnedU = 1.0f - turnedU
        if (flipV) turnedV = 1.0f - turnedV
        return Uv(turnedU, turnedV)
    }

    /**
     * Inverse of [discreteSourceToFrameUv]: undoes the displayed-frame mirrors and then the
     * opposite quarter turn, so the result reads back into the source texture.
     */
    fun discreteFrameToSourceUv(
        rotate90: Int,
        flipH: Boolean,
        flipV: Boolean,
        u: Float,
        v: Float
    ): Uv {
        val mirroredU = if (flipH) 1.0f - u else u
        val mirroredV = if (flipV) 1.0f - v else v
        return discreteSourceToFrameUv(
            rotate90 = QUARTER_TURNS - normalizeQuarterTurns(rotate90),
            flipH = false,
            flipV = false,
            u = mirroredU,
            v = mirroredV
        )
    }

    /**
     * Solves the exact largest axis-aligned rectangle, of the same [aspectRatio] as the source
     * frame, that fits inside that frame once the frame has been straightened by [angleDeg].
     *
     * A rotated rectangle fits inside an axis-aligned frame exactly when its own axis-aligned
     * bounding box does, and that box is `ow|cos| + oh|sin|` by `ow|sin| + oh|cos|`. With
     * `oh = ow / aspectRatio` this yields the two closed-form half-plane limits used below.
     *
     * Because the returned rectangle never exceeds those limits, mapping the straightened frame
     * through it samples only real pixels, so preview and export can never show black corners.
     *
     * @param angleDeg Straightening angle in degrees; clamped to `-45..45` and `NaN` becomes `0`.
     * @param aspectRatio Source frame width/height; non-positive or non-finite values become `1`.
     * @return The inscribed rectangle in the frame's physical units.
     */
    fun solveMaxInscribedRect(angleDeg: Float, aspectRatio: Float): InscribedRect {
        val aspect = if (aspectRatio.isFinite() && aspectRatio > EPSILON) aspectRatio else 1.0f
        val degrees = angleDeg
            .takeIf { it.isFinite() }
            ?.coerceIn(-MAX_STRAIGHTEN_DEGREES, MAX_STRAIGHTEN_DEGREES)
            ?: 0.0f
        val radians = degrees * DEGREES_TO_RADIANS
        val cosAbs = abs(cos(radians))
        val sinAbs = abs(sin(radians))
        val halfWidth = minOf(
            aspect / 2.0f / (cosAbs + sinAbs / aspect),
            0.5f / (sinAbs + cosAbs / aspect)
        ).coerceAtMost(aspect / 2.0f)
        return InscribedRect(halfWidth = halfWidth, halfHeight = halfWidth / aspect)
    }

    /**
     * Solves the 3x3 homography that maps [srcQuad] onto [dstQuad].
     *
     * Quads are flat `[u0, v0, u1, v1, u2, v2, u3, v3]` arrays in corner order
     * top-left, top-right, bottom-right, bottom-left.
     *
     * @return The resulting transform, or `null` when the quads are degenerate or the
     *   correspondence collapses to a singular matrix.
     */
    fun solveHomography3x3(dstQuad: FloatArray, srcQuad: FloatArray): Mat3? =
        HomographySolver.solve(dstQuad, srcQuad)

    /**
     * Returns the width/height ratio of the frame that Pass A must render.
     *
     * The value folds in the discrete quarter turn, the aspect implied by the perspective quad's
     * mean edge lengths (so a keystone correction is not stretched), and the crop rectangle.
     */
    fun outputAspect(geometry: GeometryOps, srcAspect: Float): Float =
        PassAGeometryCompiler.outputAspect(geometry, srcAspect)

    /**
     * Compiles the single inverse homography `H^-1` that Pass A evaluates for every output pixel.
     *
     * The chain is `output crop -> perspective quad -> straighten -> rotate/flip`, so one texture
     * fetch reaches the original pre-crop UV. Straightening is bounded by the maximum inscribed
     * rectangle and the perspective quad is clamped into `[0, 1]^2`, therefore every output corner
     * samples a real pixel and the rendered frame can never contain black triangles.
     *
     * @param geometry The geometry group of the edit graph.
     * @param srcAspect Width/height ratio of the upright source texture.
     * @return `H^-1` mapping output UV to pre-crop source UV.
     */
    fun buildPassAInverseMatrix(geometry: GeometryOps, srcAspect: Float): Mat3 =
        PassAGeometryCompiler.compile(geometry, srcAspect)

    /**
     * Clamps the four dragged perspective corners into the source frame and rejects concave drags.
     */
    fun sanitizePerspectiveQuad(perspective: List<Float>): FloatArray =
        PassAGeometryCompiler.sanitizeQuad(perspective)

    /**
     * Returns the radial coefficient for a lateral chromatic-aberration slider value.
     *
     * `+/-100` maps to +/- [CA_MAX_RADIAL_SCALE], i.e. at most half a percent of radial scaling at
     * the frame corner, which covers the worst real-world lateral CA while still resolving
     * sub-pixel moves on a 1024px proxy.
     */
    fun lateralCaKFromSlider(value: Int): Float =
        (value.coerceIn(-100, 100) / 100.0f) * CA_MAX_RADIAL_SCALE

    /**
     * Reference implementation of the pre-crop lateral chromatic-aberration resampling that the
     * Pass A shader performs.
     *
     * Red and blue are scaled by `1 + k * r^2` around the pre-crop optical centre `(0.5, 0.5)`,
     * where `r^2` is computed in physical aspect-corrected coordinates normalized so `r^2 = 1.0`
     * at the frame corners (circular optical radius rather than an ellipse on non-square images).
     * Green stays put as the reference channel.
     *
     * @param preCropUv Sample coordinate in the pre-crop source frame.
     * @param kRed Radial coefficient for the red channel (see [lateralCaKFromSlider]).
     * @param kBlue Radial coefficient for the blue channel (see [lateralCaKFromSlider]).
     * @param srcAspect Width/height ratio of the pre-crop source frame.
     */
    fun applyLateralCaSampleUv(
        preCropUv: Uv,
        kRed: Float,
        kBlue: Float,
        srcAspect: Float = 1.0f
    ): LateralCaSample {
        val aspect = if (srcAspect.isFinite() && srcAspect > EPSILON) srcAspect else 1.0f
        val deltaU = (preCropUv.u - 0.5f) * aspect
        val deltaV = preCropUv.v - 0.5f
        val maxCornerR2 = 0.25f * (aspect * aspect + 1.0f)
        val radiusSquared = (deltaU * deltaU + deltaV * deltaV) / maxCornerR2
        return LateralCaSample(
            red = scaleAboutOpticalCentre(preCropUv, radiusSquared, kRed),
            green = preCropUv,
            blue = scaleAboutOpticalCentre(preCropUv, radiusSquared, kBlue)
        )
    }

    private fun scaleAboutOpticalCentre(uv: Uv, radiusSquared: Float, k: Float): Uv {
        if (abs(k) < EPSILON) return uv
        val scale = 1.0f + k * radiusSquared
        return Uv(0.5f + (uv.u - 0.5f) * scale, 0.5f + (uv.v - 0.5f) * scale)
    }

    private fun normalizeQuarterTurns(rotate90: Int): Int =
        ((rotate90 % QUARTER_TURNS) + QUARTER_TURNS) % QUARTER_TURNS
}

/**
 * Largest axis-aligned rectangle of a fixed aspect ratio that still fits inside the source frame
 * after straightening, so mapping through it can never sample outside the source image.
 *
 * Extents use the frame's physical units, where the source frame spans
 * `[-aspect/2, aspect/2] x [-1/2, 1/2]`.
 */
data class InscribedRect(val halfWidth: Float, val halfHeight: Float) {
    /** Uniform scale of this rectangle relative to the full, un-rotated frame. */
    val scale: Float get() = halfHeight * 2.0f
}

/**
 * Pre-crop sample coordinates for the three colour channels after lateral CA correction.
 */
data class LateralCaSample(val red: Uv, val green: Uv, val blue: Uv)

/**
 * The interactive rotate/mirror equivalent of an EXIF orientation value.
 */
data class ExifDiscreteOps(val quarterTurns: Int, val flipH: Boolean, val flipV: Boolean)
