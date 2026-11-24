package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Multi-face MLS reshaping: 478-point landmarks in, a `128 x 128` Pass A displacement grid out.
 *
 * ## Pipeline
 * 1. Faces whose bounding box covers less than [MIN_FACE_AREA_FRACTION] of the frame are dropped as
 *    distant background faces, and at most [MAX_FACES] faces are processed.
 * 2. Every face is solved in its own rotation-invariant [FaceFrame], so a tilted head reshapes
 *    along its own axes instead of the image axes.
 * 3. The correspondence set of each face is its six-axis landmark displacements plus **identity
 *    pins**: [HALO_PIN_COUNT] halo points on an ellipse at [HALO_RADIUS_SCALE] times the face radius
 *    and the [BORDER_PIN_COUNT] image-border pins. The pins are snapped to the solve grid and their
 *    vertices forced to zero, which confines each face's deformation to its own neighbourhood and
 *    keeps group-photo neighbours and background lines still.
 * 4. [MlsSimilarity] solves the inverse warp on a [SOLVE_GRID] grid, which is bilinearly
 *    upsampled to [OUTPUT_GRID] to match the `uWarpTex` contract.
 */
object FaceMlsSolver {

    /** Grid the MLS is solved on, in cells per axis. */
    const val SOLVE_GRID = 64

    /** Grid the solver publishes, matching the `128 x 128` `uWarpTex`. */
    const val OUTPUT_GRID = 128

    /** Maximum number of faces reshaped in one frame. */
    const val MAX_FACES = 5

    /** Faces smaller than this fraction of the frame are treated as background. */
    const val MIN_FACE_AREA_FRACTION = 0.005f

    /** Identity pins placed around each face at [HALO_RADIUS_SCALE] times its radius. */
    const val HALO_PIN_COUNT = 16

    /** Identity pins placed along the four image borders. */
    const val BORDER_PIN_COUNT = 32

    /** Halo ring distance, in face radii. */
    const val HALO_RADIUS_SCALE = 1.8f

    /** Weight exponent of the Schaefer MLS similarity deformation. */
    const val MLS_ALPHA = 1.0f

    /** Largest displacement a cell may receive, in normalized frame units. */
    const val MAX_DISPLACEMENT = 0.25f

    private const val DEGENERATE_SPAN = 1e-4f
    private const val HALF_TEXEL = 0.5f
    private const val CONFINEMENT_INNER_RADIUS = 0.72f
    private const val TWO_PI = (2.0 * PI).toFloat()
    private val BORDER_STEPS = intArrayOf(0, 9, 18, 27, 36, 45, 54, 63)

    /**
     * Returns the rotation-invariant local frame of [face].
     *
     * Degenerate meshes (all landmarks on one spot) fall back to the image axes with a negligible
     * but positive span so callers never divide by zero.
     */
    fun faceFrame(face: FaceLandmarks): FaceFrame {
        val origin = face.uv(FaceLandmarkIndices.NOSE_BRIDGE)
        val right = face.uv(FaceLandmarkIndices.RIGHT_EYE_OUTER)
        val left = face.uv(FaceLandmarkIndices.LEFT_EYE_OUTER)
        val runU = left.u - right.u
        val runV = left.v - right.v
        val run = hypot(runU, runV)
        val unitU = if (run > DEGENERATE_SPAN) Uv(runU / run, runV / run) else Uv(1.0f, 0.0f)
        val unitV = Uv(-unitU.v, unitU.u)
        val width = span(face, FaceLandmarkIndices.RIGHT_CHEEK, FaceLandmarkIndices.LEFT_CHEEK)
        val height = span(face, FaceLandmarkIndices.BROW_TOP, FaceLandmarkIndices.CHIN_TIP)
        return FaceFrame(
            origin = origin,
            u = unitU,
            v = unitV,
            width = width.coerceAtLeast(DEGENERATE_SPAN),
            height = height.coerceAtLeast(DEGENERATE_SPAN)
        )
    }

    /** Drops background faces and keeps at most [MAX_FACES], in detection order. */
    fun selectFaces(faces: List<FaceLandmarks>): List<FaceLandmarks> =
        faces.asSequence()
            .filter { it.boundingBoxArea() >= MIN_FACE_AREA_FRACTION }
            .take(MAX_FACES)
            .toList()

    /** The 32 identity pins along the image borders, already snapped to solve-grid vertices. */
    fun boundaryPins(): List<Uv> {
        val low = texelCentre(0)
        val high = texelCentre(SOLVE_GRID - 1)
        val pins = ArrayList<Uv>(BORDER_PIN_COUNT)
        for (step in BORDER_STEPS) {
            val along = texelCentre(step)
            pins += Uv(along, low)
            pins += Uv(along, high)
            pins += Uv(low, along)
            pins += Uv(high, along)
        }
        return pins
    }

    /** The 16 halo identity pins of [face], snapped to the nearest solve-grid vertex. */
    fun haloPins(face: FaceLandmarks): List<Uv> {
        val frame = faceFrame(face)
        val semiU = HALO_RADIUS_SCALE * 0.5f * frame.width
        val semiV = HALO_RADIUS_SCALE * 0.5f * frame.height
        return (0 until HALO_PIN_COUNT).map { index ->
            val angle = TWO_PI * index.toFloat() / HALO_PIN_COUNT
            val alongU = cos(angle) * semiU
            val alongV = sin(angle) * semiV
            snap(
                frame.origin.u + alongU * frame.u.u + alongV * frame.v.u,
                frame.origin.v + alongU * frame.u.v + alongV * frame.v.v
            )
        }
    }

    /** Solves the MLS warp on the [SOLVE_GRID] grid and returns interleaved `(dx, dy)` cells. */
    fun solveGrid(faces: List<FaceLandmarks>, weights: FaceReshapeWeights): FloatArray {
        val grid = FloatArray(SOLVE_GRID * SOLVE_GRID * CHANNELS)
        if (weights.isIdentity) return grid
        val selected = selectFaces(faces)
        if (selected.isEmpty()) return grid
        val scratch = FloatArray(2)
        for (face in selected) {
            accumulateFace(face, weights, grid, scratch)
        }
        val pinned = ArrayList<Uv>(BORDER_PIN_COUNT + selected.size * HALO_PIN_COUNT)
        pinned += boundaryPins()
        selected.forEach { pinned += haloPins(it) }
        pinned.forEach { zeroVertex(grid, it) }
        return grid
    }

    /** Solves and upsamples to the [OUTPUT_GRID] mesh the Pass A warp texture uploads. */
    fun solve(faces: List<FaceLandmarks>, weights: FaceReshapeWeights): FloatArray =
        upsample(solveGrid(faces, weights))

    /**
     * Bilinearly samples [grid] (`gridSize` cells per axis) at normalized [u], [v], using the same
     * texel-centre convention as the `GL_LINEAR` `uWarpTex` fetch.
     */
    fun displacementAt(grid: FloatArray, gridSize: Int, u: Float, v: Float): Uv {
        if (!u.isFinite() || !v.isFinite() || gridSize <= 0) return Uv(0.0f, 0.0f)
        val x = (u * gridSize - HALF_TEXEL).coerceIn(0.0f, gridSize - 1.0f)
        val y = (v * gridSize - HALF_TEXEL).coerceIn(0.0f, gridSize - 1.0f)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, gridSize - 1)
        val y1 = min(y0 + 1, gridSize - 1)
        val fx = x - x0
        val fy = y - y0
        val topLeft = (y0 * gridSize + x0) * CHANNELS
        val topRight = (y0 * gridSize + x1) * CHANNELS
        val bottomLeft = (y1 * gridSize + x0) * CHANNELS
        val bottomRight = (y1 * gridSize + x1) * CHANNELS
        return Uv(
            bilinear(grid, topLeft, topRight, bottomLeft, bottomRight, fx, fy),
            bilinear(grid, topLeft + 1, topRight + 1, bottomLeft + 1, bottomRight + 1, fx, fy)
        )
    }

    /** Bilinearly upsamples a [SOLVE_GRID] displacement grid to [OUTPUT_GRID]. */
    internal fun upsample(grid: FloatArray): FloatArray {
        val out = FloatArray(OUTPUT_GRID * OUTPUT_GRID * CHANNELS)
        val ratio = OUTPUT_GRID.toFloat() / SOLVE_GRID.toFloat()
        for (y in 0 until OUTPUT_GRID) {
            val sourceY = ((y + HALF_TEXEL) / ratio - HALF_TEXEL).coerceIn(0.0f, SOLVE_GRID - 1.0f)
            for (x in 0 until OUTPUT_GRID) {
                val sourceX = ((x + HALF_TEXEL) / ratio - HALF_TEXEL).coerceIn(0.0f, SOLVE_GRID - 1.0f)
                val sampled = displacementAt(
                    grid = grid,
                    gridSize = SOLVE_GRID,
                    u = (sourceX + HALF_TEXEL) / SOLVE_GRID,
                    v = (sourceY + HALF_TEXEL) / SOLVE_GRID
                )
                val target = (y * OUTPUT_GRID + x) * CHANNELS
                out[target] = sampled.u
                out[target + 1] = sampled.v
            }
        }
        return out
    }

    private fun accumulateFace(
        face: FaceLandmarks,
        weights: FaceReshapeWeights,
        grid: FloatArray,
        scratch: FloatArray
    ) {
        val frame = faceFrame(face)
        val controls = FaceReshapeControls.displacements(face, frame, weights)
        if (controls.isEmpty()) return
        val halo = haloPins(face)
        val border = boundaryPins()
        val count = controls.size + halo.size + border.size
        val source = FloatArray(count * CHANNELS)
        val target = FloatArray(count * CHANNELS)
        controls.forEachIndexed { position, control ->
            val point = face.uv(control.index)
            val offset = position * CHANNELS
            source[offset] = point.u + control.du
            source[offset + 1] = point.v + control.dv
            target[offset] = point.u
            target[offset + 1] = point.v
        }
        var position = controls.size
        for (pin in halo + border) {
            val offset = position * CHANNELS
            source[offset] = pin.u
            source[offset + 1] = pin.v
            target[offset] = pin.u
            target[offset + 1] = pin.v
            position++
        }
        for (row in 0 until SOLVE_GRID) {
            val v = texelCentre(row)
            for (column in 0 until SOLVE_GRID) {
                val u = texelCentre(column)
                // The confinement window is exactly zero outside the halo, so skipping those cells is
                // identical to solving and multiplying by zero — and on a typical frame it skips most
                // of the grid, which is the hot path of the whole MLS solve.
                val confinement = confinementWindow(frame, u, v)
                if (confinement <= 0.0f) continue
                MlsSimilarity.solve(source, target, count, u, v, MLS_ALPHA, scratch)
                val cell = (row * SOLVE_GRID + column) * CHANNELS
                grid[cell] += ((scratch[0] - u) * confinement)
                    .coerceIn(-MAX_DISPLACEMENT, MAX_DISPLACEMENT)
                grid[cell + 1] += ((scratch[1] - v) * confinement)
                    .coerceIn(-MAX_DISPLACEMENT, MAX_DISPLACEMENT)
            }
        }
    }

    /**
     * Radial confinement of one face's deformation.
     *
     * The MLS similarity fit has a non-vanishing far field (it is a global least-squares similarity),
     * so the halo pins alone cannot stop a strongly reshaped face from nudging a neighbour. This
     * window is `1` over the whole face (every landmark sits inside `0.72` of the halo radius) and
     * falls to `0` exactly on the pin ring at `1.0`, which makes each face's contribution provably
     * zero outside its own halo.
     */
    private fun confinementWindow(frame: FaceFrame, u: Float, v: Float): Float {
        val semiU = HALO_RADIUS_SCALE * 0.5f * frame.width
        val semiV = HALO_RADIUS_SCALE * 0.5f * frame.height
        val alongU = ((u - frame.origin.u) * frame.u.u + (v - frame.origin.v) * frame.u.v) / semiU
        val alongV = ((u - frame.origin.u) * frame.v.u + (v - frame.origin.v) * frame.v.v) / semiV
        val radius = hypot(alongU, alongV)
        return 1.0f - smoothStep(CONFINEMENT_INNER_RADIUS, 1.0f, radius)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val span = edge1 - edge0
        if (span <= 0.0f) return if (value < edge0) 0.0f else 1.0f
        val t = ((value - edge0) / span).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }

    private fun zeroVertex(grid: FloatArray, pin: Uv) {
        val column = floor(pin.u * SOLVE_GRID).toInt().coerceIn(0, SOLVE_GRID - 1)
        val row = floor(pin.v * SOLVE_GRID).toInt().coerceIn(0, SOLVE_GRID - 1)
        val cell = (row * SOLVE_GRID + column) * CHANNELS
        grid[cell] = 0.0f
        grid[cell + 1] = 0.0f
    }

    private fun snap(u: Float, v: Float): Uv = Uv(texelCentre(gridIndex(u)), texelCentre(gridIndex(v)))

    private fun gridIndex(value: Float): Int =
        (value * SOLVE_GRID - HALF_TEXEL).roundToInt().coerceIn(0, SOLVE_GRID - 1)

    private fun texelCentre(index: Int): Float = (index + HALF_TEXEL) / SOLVE_GRID

    private fun span(face: FaceLandmarks, first: Int, second: Int): Float {
        val a = face.uv(first)
        val b = face.uv(second)
        return hypot(a.u - b.u, a.v - b.v)
    }

    private fun bilinear(
        grid: FloatArray,
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        fx: Float,
        fy: Float
    ): Float {
        val top = grid[topLeft] + (grid[topRight] - grid[topLeft]) * fx
        val bottom = grid[bottomLeft] + (grid[bottomRight] - grid[bottomLeft]) * fx
        return top + (bottom - top) * fy
    }

    private const val CHANNELS = 2
}
