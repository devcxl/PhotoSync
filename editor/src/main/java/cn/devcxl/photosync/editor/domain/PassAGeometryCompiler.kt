package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compiles the geometry group of an [EditGraph] into the single inverse homography that Pass A
 * evaluates, plus the frame aspect ratio that the pass must render.
 *
 * ## Stage order (output -> source)
 * ```
 * output UV --crop--> perspective UV --quad warp--> straightened UV
 *           --inscribed straighten--> rotated UV --quarter turn + mirrors--> source UV
 * ```
 * Each stage is bounded by construction: the crop rectangle lives inside `[0, 1]^2`, the
 * perspective quad is clamped into `[0, 1]^2`, and straightening is limited to the maximum
 * inscribed rectangle. Every output corner therefore samples a real source pixel, and the rendered
 * frame can never contain a black triangle.
 */
internal object PassAGeometryCompiler {

    private const val QUAD_CORNERS = 4
    private const val QUAD_COORDINATES = QUAD_CORNERS * 2
    private const val QUARTER_TURNS = 4
    private const val CROP_LEFT = 0
    private const val CROP_TOP = 1
    private const val CROP_WIDTH = 2
    private const val CROP_HEIGHT = 3
    private const val MIN_CROP_SPAN = 0.05f
    private const val MIN_QUAD_CROSS_PRODUCT = 1e-4f

    private val UNIT_SQUARE_QUAD = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    /**
     * Compiles `H^-1` for [geometry], mapping output UV to pre-crop source UV.
     */
    fun compile(geometry: GeometryOps, srcAspect: Float): Mat3 {
        val aspect = validAspect(srcAspect)
        val quarterTurns = normalizeQuarterTurns(geometry.rotate90)
        val frameAspect = if (quarterTurns % 2 != 0) 1.0f / aspect else aspect
        return discreteInverse(quarterTurns, geometry.flipH, geometry.flipV)
            .times(straightenInverse(geometry.straighten, frameAspect))
            .times(perspectiveInverse(geometry.perspective))
            .times(cropInverse(geometry.crop))
    }

    /**
     * Width/height ratio of the frame Pass A renders, folding in the quarter turn, the aspect
     * implied by the perspective quad's mean edge lengths and the crop rectangle.
     */
    fun outputAspect(geometry: GeometryOps, srcAspect: Float): Float {
        val aspect = validAspect(srcAspect)
        val frameAspect = if (normalizeQuarterTurns(geometry.rotate90) % 2 != 0) 1.0f / aspect else aspect
        val span = cropSpan(geometry.crop)
        return perspectiveAspect(frameAspect, geometry.perspective) * span[CROP_WIDTH] / span[CROP_HEIGHT]
    }

    /**
     * Clamps the four dragged corners into the source frame and rejects concave or degenerate drags,
     * so an invalid quad can never fold the image or expose black corners.
     */
    fun sanitizeQuad(perspective: List<Float>): FloatArray {
        if (perspective.size != QUAD_COORDINATES || !perspective.all { it.isFinite() }) {
            return UNIT_SQUARE_QUAD.copyOf()
        }
        val clamped = FloatArray(QUAD_COORDINATES) { perspective[it].coerceIn(0.0f, 1.0f) }
        return if (isConvexQuad(clamped)) clamped else UNIT_SQUARE_QUAD.copyOf()
    }

    private fun validAspect(srcAspect: Float): Float =
        if (srcAspect.isFinite() && srcAspect > GeometryMath.EPSILON) srcAspect else 1.0f

    private fun normalizeQuarterTurns(rotate90: Int): Int =
        ((rotate90 % QUARTER_TURNS) + QUARTER_TURNS) % QUARTER_TURNS

    private fun discreteInverse(quarterTurns: Int, flipH: Boolean, flipV: Boolean): Mat3 {
        val flip = Mat3.ofRowMajor(
            doubleArrayOf(
                if (flipH) -1.0 else 1.0, 0.0, if (flipH) 1.0 else 0.0,
                0.0, if (flipV) -1.0 else 1.0, if (flipV) 1.0 else 0.0,
                0.0, 0.0, 1.0
            )
        )
        val inverseTurn = when (QUARTER_TURNS - quarterTurns) {
            1 -> MATRIX_CLOCKWISE
            2 -> MATRIX_HALF_TURN
            3 -> MATRIX_ANTICLOCKWISE
            else -> Mat3.identity()
        }
        return inverseTurn.times(flip)
    }

    private fun straightenInverse(angleDeg: Float, frameAspect: Float): Mat3 {
        val degrees = angleDeg
            .takeIf { it.isFinite() }
            ?.coerceIn(-GeometryMath.MAX_STRAIGHTEN_DEGREES, GeometryMath.MAX_STRAIGHTEN_DEGREES)
            ?: 0.0f
        val scale = GeometryMath.solveMaxInscribedRect(degrees, frameAspect).scale
        val radians = degrees * GeometryMath.DEGREES_TO_RADIANS
        val sin = sin(radians)
        val cos = cos(radians)
        val scaledCos = scale * cos
        val scaledSin = scale * sin
        val verticalShift = scale * frameAspect * sin
        return Mat3.ofRowMajor(
            doubleArrayOf(
                scaledCos.toDouble(),
                (-scaledSin / frameAspect).toDouble(),
                (0.5f - 0.5f * scaledCos + 0.5f * scaledSin / frameAspect).toDouble(),
                verticalShift.toDouble(),
                scaledCos.toDouble(),
                (0.5f - 0.5f * verticalShift - 0.5f * scaledCos).toDouble(),
                0.0, 0.0, 1.0
            )
        )
    }

    private fun perspectiveInverse(perspective: List<Float>): Mat3 =
        HomographySolver.solve(sanitizeQuad(perspective), UNIT_SQUARE_QUAD) ?: Mat3.identity()

    private fun cropInverse(crop: CropRect): Mat3 {
        val span = cropSpan(crop)
        return Mat3.ofRowMajor(
            doubleArrayOf(
                span[CROP_WIDTH].toDouble(), 0.0, span[CROP_LEFT].toDouble(),
                0.0, span[CROP_HEIGHT].toDouble(), span[CROP_TOP].toDouble(),
                0.0, 0.0, 1.0
            )
        )
    }

    private fun cropSpan(crop: CropRect): FloatArray {
        val left = crop.left.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f - MIN_CROP_SPAN) ?: 0.0f
        val top = crop.top.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f - MIN_CROP_SPAN) ?: 0.0f
        val right = crop.right.takeIf { it.isFinite() }?.coerceIn(left + MIN_CROP_SPAN, 1.0f) ?: 1.0f
        val bottom = crop.bottom.takeIf { it.isFinite() }?.coerceIn(top + MIN_CROP_SPAN, 1.0f) ?: 1.0f
        return floatArrayOf(left, top, right - left, bottom - top)
    }

    private fun perspectiveAspect(frameAspect: Float, perspective: List<Float>): Float {
        val quad = sanitizeQuad(perspective)
        val meanWidth = (distance(quad, 0, 1, frameAspect) + distance(quad, 3, 2, frameAspect)) * 0.5f
        val meanHeight = (distance(quad, 0, 3, frameAspect) + distance(quad, 1, 2, frameAspect)) * 0.5f
        if (meanHeight < GeometryMath.EPSILON) return frameAspect
        return meanWidth / meanHeight
    }

    private fun distance(quad: FloatArray, from: Int, to: Int, frameAspect: Float): Float {
        val dx = (quad[to * 2] - quad[from * 2]) * frameAspect
        val dy = quad[to * 2 + 1] - quad[from * 2 + 1]
        return sqrt(dx * dx + dy * dy)
    }

    internal fun isConvexQuad(quad: FloatArray): Boolean {
        if (quad.size != QUAD_COORDINATES) return false
        var positiveTurns = 0
        var negativeTurns = 0
        for (corner in 0 until QUAD_CORNERS) {
            val cross = crossProductAt(quad, corner)
            if (abs(cross) < MIN_QUAD_CROSS_PRODUCT) return false
            if (cross > 0.0f) positiveTurns++ else negativeTurns++
        }
        return positiveTurns == 0 || negativeTurns == 0
    }

    private fun crossProductAt(quad: FloatArray, corner: Int): Float {
        val x = quad[corner * 2]
        val y = quad[corner * 2 + 1]
        val nextX = quad[((corner + 1) % QUAD_CORNERS) * 2]
        val nextY = quad[((corner + 1) % QUAD_CORNERS) * 2 + 1]
        val afterX = quad[((corner + 2) % QUAD_CORNERS) * 2]
        val afterY = quad[((corner + 2) % QUAD_CORNERS) * 2 + 1]
        return (nextX - x) * (afterY - nextY) - (nextY - y) * (afterX - nextX)
    }

    /** `(u, v) -> (1 - v, u)`: a clockwise quarter turn in the top-left UV frame. */
    private val MATRIX_CLOCKWISE = Mat3.ofRowMajor(
        doubleArrayOf(0.0, -1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    )

    private val MATRIX_HALF_TURN = Mat3.ofRowMajor(
        doubleArrayOf(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0)
    )

    /** `(u, v) -> (v, 1 - u)`: an anticlockwise quarter turn. */
    private val MATRIX_ANTICLOCKWISE = Mat3.ofRowMajor(
        doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 1.0)
    )
}
