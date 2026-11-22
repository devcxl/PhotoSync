package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * The single `128 x 128` Pass A displacement mesh: face MLS + body reshaping + liquify brush added
 * together.
 *
 * Components are summed cell by cell, exactly as the portrait spec defines
 * `Δuv_total = Δuv_face + Δuv_body + Δuv_liquify`, so Pass A still performs one texture fetch.
 */
class UnifiedWarpField private constructor(private val cells: FloatArray) : WarpDisplacementField {

    override val gridSize: Int
        get() = PortraitWarpComposer.GRID_SIZE

    override fun isZero(): Boolean = cells.all { abs(it) < ZERO_TOLERANCE }

    override fun readHalfFloatBitsInto(destination: ShortArray) {
        require(destination.size == cells.size) {
            "warp half-float grid must hold ${cells.size} values"
        }
        for (index in cells.indices) {
            destination[index] = HalfFloatBits.toBits(cells[index])
        }
    }

    override fun displacementRaw(index: Int): Float = cells[index]

    override fun displacementAt(u: Float, v: Float): Uv {
        if (!u.isFinite() || !v.isFinite()) return Uv(0.0f, 0.0f)
        val size = PortraitWarpComposer.GRID_SIZE
        val x = (u * size - HALF_TEXEL).coerceIn(0.0f, size - 1.0f)
        val y = (v * size - HALF_TEXEL).coerceIn(0.0f, size - 1.0f)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val fx = x - x0
        val fy = y - y0
        val topLeft = (y0 * size + x0) * CHANNELS
        val topRight = (y0 * size + x1) * CHANNELS
        val bottomLeft = (y1 * size + x0) * CHANNELS
        val bottomRight = (y1 * size + x1) * CHANNELS
        return Uv(
            bilinear(cells, topLeft, topRight, bottomLeft, bottomRight, fx, fy),
            bilinear(cells, topLeft + 1, topRight + 1, bottomLeft + 1, bottomRight + 1, fx, fy)
        )
    }

    /** Deep copy of the interleaved components, for callers that need the raw field. */
    fun copyComponents(): FloatArray = cells.copyOf()

    /** True when [other] holds exactly the same displacement values. */
    fun contentEquals(other: UnifiedWarpField): Boolean = cells.contentEquals(other.cells)

    companion object {
        /** Components per cell: `(dx, dy)`. */
        const val CHANNELS = 2

        /** Displacement below which the field counts as untouched. */
        const val ZERO_TOLERANCE = 1e-5f

        private const val HALF_TEXEL = 0.5f

        /** Wraps interleaved components, copying them so callers cannot mutate the field. */
        fun fromComponents(components: FloatArray): UnifiedWarpField {
            require(components.size == PortraitWarpComposer.COMPONENT_COUNT) {
                "a warp field needs ${PortraitWarpComposer.COMPONENT_COUNT} components"
            }
            return UnifiedWarpField(components.copyOf())
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

/**
 * Sums the three warp producers into the one `uWarpTex` mesh Pass A samples.
 */
object PortraitWarpComposer {

    /** Edge length of the composed displacement grid, fixed by the `uWarpTex` contract. */
    const val GRID_SIZE = 128

    /** Interleaved components of the composed grid. */
    const val COMPONENT_COUNT = GRID_SIZE * GRID_SIZE * UnifiedWarpField.CHANNELS

    /** Tolerance used when deciding whether a source grid can be skipped. */
    private const val ZERO_TOLERANCE = 1e-7f

    /**
     * Adds [faceWarp] and [bodyWarp] (each `GRID_SIZE x GRID_SIZE x 2` interleaved components) and
     * [liquify] into a single field.
     *
     * Every input is optional: a `null` grid simply contributes nothing, which is how a photo
     * without a detected face, without body sliders or without liquify strokes still yields a
     * valid mesh.
     */
    fun compose(
        faceWarp: FloatArray?,
        bodyWarp: FloatArray?,
        liquify: WarpDisplacementField?
    ): UnifiedWarpField = UnifiedWarpField.fromComponents(composeComponents(faceWarp, bodyWarp, liquify))

    /**
     * Returns the summed interleaved components in the exact layout the composition produces.
     */
    fun composeComponents(
        faceWarp: FloatArray?,
        bodyWarp: FloatArray?,
        liquify: WarpDisplacementField?
    ): FloatArray {
        val components = FloatArray(COMPONENT_COUNT)
        addGrid(components, faceWarp)
        addGrid(components, bodyWarp)
        if (liquify != null && liquify.gridSize == GRID_SIZE && !liquify.isZero()) {
            for (index in components.indices) {
                components[index] += liquify.displacementRaw(index)
            }
        }
        return components
    }

    private fun addGrid(target: FloatArray, source: FloatArray?) {
        if (source == null || source.size != COMPONENT_COUNT) return
        var hasValue = false
        for (index in source.indices) {
            if (abs(source[index]) > ZERO_TOLERANCE) {
                hasValue = true
                break
            }
        }
        if (!hasValue) return
        for (index in target.indices) {
            target[index] += source[index]
        }
    }
}
