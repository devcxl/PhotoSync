package cn.devcxl.photosync.editor.domain

import kotlin.math.ceil

/**
 * One budget-sized slice of an [InpaintCropPlan], the execution rhythm of a progressive run.
 *
 * A batch is not a rejection unit: any number of tiles can be planned, they are merely cut into
 * slices of [InpaintCropSpec.batchTiles] so the caller can commit and report between slices
 * (spec §7.3).
 *
 * @property index Zero-based batch order, equal to the position of the first tile's group.
 * @property tiles Ordered tiles of this batch; every tile still produces its own patch.
 */
data class InpaintBatch(val index: Int, val tiles: List<InpaintTilePlan>) {

    init {
        require(index >= 0) { "Inpaint batch index must not be negative" }
        require(tiles.isNotEmpty()) { "An inpaint batch needs at least one tile" }
    }

    /** Number of tiles in this batch. */
    val size: Int
        get() = tiles.size
}

/**
 * Ordered batches of one inpainting confirmation.
 *
 * @property batches Non-empty batches in execution order; empty when nothing was painted.
 */
data class InpaintBatches(val batches: List<InpaintBatch>) {

    /** Number of batches; `0` when the plan was empty. */
    val count: Int
        get() = batches.size

    /** Total tiles across every batch. */
    val tileCount: Int
        get() = batches.sumOf { it.size }

    /**
     * Tiles belonging to the batches that have not been committed yet.
     *
     * @param committedBatchIndex Index of the batch that just finished; `-1` when none has.
     */
    fun remainingTilesAfter(committedBatchIndex: Int): List<InpaintTilePlan> =
        batches.drop(committedBatchIndex + 1).flatMap { it.tiles }
}

/**
 * Cuts a tile plan into the execution budget and estimates the workload before inference starts.
 *
 * The planner never rejects a plan (spec §7.3 replaced `InpaintPlanTooLargeException` with
 * budgeted batching). [estimateTileCount] mirrors the crop planner's core grid so the panel can
 * show "预计 N 块" from the painted bbox alone, without decoding the source or rasterizing a mask.
 */
object InpaintBatchPlanner {

    /** Splits [plan] by the batch budget of its own [InpaintCropSpec]. */
    fun plan(plan: InpaintCropPlan): InpaintBatches = of(plan.tiles, plan.spec.batchTiles)

    /**
     * Splits [tiles] into ordered batches of at most [batchTiles] tiles.
     *
     * @param tiles Tiles in execution order; an empty list yields empty [InpaintBatches].
     * @param batchTiles Positive batch budget; a rhythm, never a rejection rule.
     */
    fun of(tiles: List<InpaintTilePlan>, batchTiles: Int): InpaintBatches {
        require(batchTiles > 0) { "Inpaint batch size must be positive" }
        if (tiles.isEmpty()) return InpaintBatches(emptyList())
        val batches = tiles.chunked(batchTiles).mapIndexed { index, chunk ->
            InpaintBatch(index = index, tiles = chunk)
        }
        return InpaintBatches(batches)
    }

    /**
     * Estimates how many tiles a painted bbox needs, without rasterizing the mask.
     *
     * The result equals [InpaintCropPlanner]'s grid for a single connected blob filling [bounds];
     * several separated blobs can need more tiles because each gets its own grid. The panel uses
     * this as a live workload hint and the value is documented as an estimate.
     *
     * @param bounds Painted bbox in source pixels, already projected through Pass A.
     * @param spec Tier and quality geometry to estimate with.
     * @return Tile count, `0` when [bounds] is empty.
     */
    fun estimateTileCount(bounds: InpaintPixelRect, spec: InpaintCropSpec): Int {
        if (bounds.isEmpty) return 0
        if (bounds.width <= spec.singleTileMaxSidePx && bounds.height <= spec.singleTileMaxSidePx) {
            return 1
        }
        return axisTileCount(bounds.width, spec) * axisTileCount(bounds.height, spec)
    }

    /**
     * Tile count along one axis, mirroring the crop planner's core stepping and tail cell.
     *
     * The planner walks `start + k * step` while the current core still fits and then adds the
     * `end - core` tail when it is not already the last start; that is exactly
     * `1 + ceil((length - core) / step)` for a length longer than the core.
     */
    private fun axisTileCount(length: Int, spec: InpaintCropSpec): Int {
        if (length <= 0) return 0
        if (length <= spec.coreSidePx) return 1
        val extra = length - spec.coreSidePx
        return 1 + ceil(extra.toDouble() / spec.stepPx).toInt()
    }
}
