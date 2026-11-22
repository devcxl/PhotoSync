package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Fixed `128 x 128` inverse-mapping displacement field of the liquify brush.
 *
 * ## Convention
 * The field stores, for every normalized output coordinate `p`, the offset that must be added to
 * `p` before sampling the source: `sourceUv = p + displacement(p)`. Dragging content to the right
 * therefore accumulates a **negative** horizontal displacement, which is why every stroke applies
 * `-delta`.
 *
 * ## Layout
 * Cells are interleaved `(dx, dy)` pairs in row-major order, matching the `RG16F` texel layout of
 * the Pass A `uWarpTex` so the CPU resampler and the shader read exactly the same values. Sampling
 * uses the same texel-centre convention as a `GL_LINEAR` / `GL_CLAMP_TO_EDGE` texture fetch.
 *
 * Instances are mutable accumulators owned by a single editor session; [copy] snapshots the state
 * for preemption rollback and [toHalfFloatBits] produces the compact persistence form.
 */
internal class LiquifyMesh private constructor(private val cells: FloatArray) : WarpDisplacementField {

    /** Edge length of the square displacement grid. */
    override val gridSize: Int
        get() = GRID_SIZE

    /** Deep copy of this field, used to snapshot the state before a stroke. */
    fun copy(): LiquifyMesh = LiquifyMesh(cells.copyOf())

    /**
     * Returns true when every cell is within [ZERO_TOLERANCE] of zero, i.e. the edit is visually
     * identical to no liquify at all and `EditGraph.geometry.liquify` should be cleared.
     */
    override fun isZero(): Boolean = cells.all { abs(it) < ZERO_TOLERANCE }

    /** Raw interleaved component at [index]; exposed for the persistence codec and tests. */
    override fun displacementRaw(index: Int): Float = cells[index]

    /** True when [other] holds exactly the same displacement values. */
    fun contentEquals(other: LiquifyMesh): Boolean = cells.contentEquals(other.cells)

    /**
     * Samples the field at normalized [u], [v] with bilinear interpolation.
     *
     * @return The displacement at that coordinate; `(0, 0)` outside the grid.
     */
    override fun displacementAt(u: Float, v: Float): Uv {
        if (!u.isFinite() || !v.isFinite()) return Uv(0.0f, 0.0f)
        val x = (u * GRID_SIZE - HALF_TEXEL).coerceIn(0.0f, LAST_INDEX)
        val y = (v * GRID_SIZE - HALF_TEXEL).coerceIn(0.0f, LAST_INDEX)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, LAST_INDEX_INT)
        val y1 = min(y0 + 1, LAST_INDEX_INT)
        val fx = x - x0
        val fy = y - y0
        val topLeft = (y0 * GRID_SIZE + x0) * CHANNELS
        val topRight = (y0 * GRID_SIZE + x1) * CHANNELS
        val bottomLeft = (y1 * GRID_SIZE + x0) * CHANNELS
        val bottomRight = (y1 * GRID_SIZE + x1) * CHANNELS
        return Uv(
            u = bilinear(cells, topLeft, topRight, bottomLeft, bottomRight, fx, fy),
            v = bilinear(cells, topLeft + 1, topRight + 1, bottomLeft + 1, bottomRight + 1, fx, fy)
        )
    }

    /**
     * Accumulates a push stroke that drags content from [prevUv] towards [nextUv].
     *
     * The displacement is the negated drag so pixels visually follow the finger, weighted by the
     * smooth radial falloff `(1 - r^2)^2` inside [radiusNorm].
     *
     * @param radiusNorm Brush radius in normalized horizontal units.
     * @param strength Brush strength in `0..1`; the panel maps its `0..100` slider onto this.
     * @param frameAspect Frame width/height ratio, so the brush stays circular on screen.
     */
    fun applyPushStroke(
        prevUv: Uv,
        nextUv: Uv,
        radiusNorm: Float,
        strength: Float,
        frameAspect: Float = 1.0f
    ) {
        val deltaU = nextUv.u - prevUv.u
        val deltaV = nextUv.v - prevUv.v
        if (!deltaU.isFinite() || !deltaV.isFinite()) return
        if (abs(deltaU) < FLOAT_EPSILON && abs(deltaV) < FLOAT_EPSILON) return
        forEachWeightedCell(nextUv, radiusNorm, strength, frameAspect) { index, weight ->
            cells[index] = (cells[index] - deltaU * weight).clampDisplacement()
            cells[index + 1] = (cells[index + 1] - deltaV * weight).clampDisplacement()
        }
    }

    /**
     * Smoothly decays the displacement back towards zero around [uv], which is the behaviour of the
     * `Restore` eraser: repeated stamps converge on the untouched image without overshooting.
     */
    fun applyRestoreStroke(
        uv: Uv,
        radiusNorm: Float,
        strength: Float,
        frameAspect: Float = 1.0f
    ) {
        forEachWeightedCell(uv, radiusNorm, strength, frameAspect) { index, weight ->
            val keep = (1.0f - weight).coerceIn(0.0f, 1.0f)
            cells[index] *= keep
            cells[index + 1] *= keep
        }
    }

    /** Serializes the grid into interleaved half-float bits, the persistence and upload form. */
    fun toHalfFloatBits(): ShortArray =
        ShortArray(COMPONENT_COUNT).also { readHalfFloatBitsInto(it) }

    /**
     * Decodes the grid into [destination], reusing the caller's buffer so a live brush stroke does
     * not allocate a new 64 KiB array for every texture upload.
     */
    override fun readHalfFloatBitsInto(destination: ShortArray) {
        require(destination.size == COMPONENT_COUNT) {
            "liquify half-float grid must hold $COMPONENT_COUNT values"
        }
        for (index in cells.indices) {
            destination[index] = HalfFloatBits.toBits(cells[index])
        }
    }

    private inline fun forEachWeightedCell(
        centre: Uv,
        radiusNorm: Float,
        strength: Float,
        frameAspect: Float,
        action: (index: Int, weight: Float) -> Unit
    ) {
        if (!centre.u.isFinite() || !centre.v.isFinite()) return
        val radius = radiusNorm.takeIf { it.isFinite() && it > MIN_RADIUS } ?: return
        val safeStrength = strength.coerceIn(0.0f, 1.0f)
        if (safeStrength <= 0.0f) return
        val aspect = frameAspect.takeIf { it.isFinite() && it > MIN_RADIUS } ?: 1.0f
        val physicalRadius = radius * aspect
        val inverseRadiusSquared = 1.0f / (physicalRadius * physicalRadius)
        for (y in 0 until GRID_SIZE) {
            val deltaV = (y + HALF_TEXEL) / GRID_SIZE - centre.v
            val deltaVSquared = deltaV * deltaV
            for (x in 0 until GRID_SIZE) {
                val deltaU = ((x + HALF_TEXEL) / GRID_SIZE - centre.u) * aspect
                val radiusSquared = (deltaU * deltaU + deltaVSquared) * inverseRadiusSquared
                if (radiusSquared >= 1.0f) continue
                val falloff = 1.0f - radiusSquared
                action((y * GRID_SIZE + x) * CHANNELS, falloff * falloff * safeStrength)
            }
        }
    }

    private fun Float.clampDisplacement(): Float = coerceIn(-MAX_DISPLACEMENT, MAX_DISPLACEMENT)

    companion object {
        /** Edge length of the displacement grid, fixed by the persistence contract. */
        const val GRID_SIZE = 128

        /** Interleaved channels per cell: `(dx, dy)`. */
        const val CHANNELS = 2

        /** Number of displacement cells. */
        const val CELL_COUNT = GRID_SIZE * GRID_SIZE

        /** Number of raw float components (or half-float values) in one grid. */
        const val COMPONENT_COUNT = CELL_COUNT * CHANNELS

        /** Absolute displacement below which the whole field counts as untouched. */
        const val ZERO_TOLERANCE = 1e-5f

        /** Largest displacement a single cell may carry, in normalized frame units. */
        const val MAX_DISPLACEMENT = 0.25f

        private const val HALF_TEXEL = 0.5f
        private const val LAST_INDEX = GRID_SIZE - 1.0f
        private const val LAST_INDEX_INT = GRID_SIZE - 1
        private const val MIN_RADIUS = 1e-4f
        private const val FLOAT_EPSILON = 1e-7f

        /** Creates an all-zero field. */
        fun empty(): LiquifyMesh = LiquifyMesh(FloatArray(COMPONENT_COUNT))

        /**
         * Wraps raw interleaved components, copying them so callers cannot mutate the grid behind
         * the mesh's back.
         */
        fun fromDisplacements(components: FloatArray): LiquifyMesh {
            require(components.size == COMPONENT_COUNT) {
                "liquify grid must hold $COMPONENT_COUNT components"
            }
            return LiquifyMesh(components.copyOf())
        }

        /** Rebuilds a field from its interleaved half-float representation. */
        fun fromHalfFloatBits(bits: ShortArray): LiquifyMesh {
            require(bits.size == COMPONENT_COUNT) {
                "liquify half-float grid must hold $COMPONENT_COUNT values"
            }
            return LiquifyMesh(HalfFloatBits.toFloats(bits))
        }

        private fun bilinear(
            cells: FloatArray,
            topLeft: Int,
            topRight: Int,
            bottomLeft: Int,
            bottomRight: Int,
            fx: Float,
            fy: Float
        ): Float {
            val top = cells[topLeft] + (cells[topRight] - cells[topLeft]) * fx
            val bottom = cells[bottomLeft] + (cells[bottomRight] - cells[bottomLeft]) * fx
            return top + (bottom - top) * fy
        }
    }
}
