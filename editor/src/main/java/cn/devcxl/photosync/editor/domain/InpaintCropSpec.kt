package cn.devcxl.photosync.editor.domain

import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Geometry and execution budget of one inpainting run (spec §6.3 and §7.3).
 *
 * The numbers are one table, not scattered constants: the context margin decides how much
 * surrounding source each tile sees, the core decides how much of it a tile owns, and the step
 * decides whether neighbouring cores overlap (fine mode) or only meet at the context margin
 * (fast mode). [batchTiles] is the execution rhythm a caller may use; it never rejects work.
 *
 * @property memoryTier Device class the numbers were derived from.
 * @property qualityMode Fine (overlapping cores, better detail) or fast (about half the tiles).
 * @property contextMarginPx Context added on every side of a core to build its crop.
 * @property minCropSidePx Smallest crop side; small holes are no longer upscaled to 512.
 * @property maxCropSidePx Largest crop side; bounds the model input and therefore the peak memory.
 * @property coreSidePx Side of the grid cell a tile is responsible for.
 * @property stepPx Distance between two grid cells; equal to [coreSidePx] in fast mode.
 * @property batchTiles Tiles one execution batch may contain; a rhythm, never a rejection rule.
 */
data class InpaintCropSpec(
    val memoryTier: DeviceMemoryTier,
    val qualityMode: InpaintQualityMode,
    val contextMarginPx: Int = CONTEXT_MARGIN_PX,
    val minCropSidePx: Int = MIN_CROP_SIDE_PX,
    val maxCropSidePx: Int,
    val coreSidePx: Int,
    val stepPx: Int,
    val batchTiles: Int
) {

    init {
        require(contextMarginPx >= 0) { "Inpaint context margin must not be negative" }
        require(minCropSidePx > 0) { "Inpaint minimum crop side must be positive" }
        require(maxCropSidePx >= minCropSidePx) { "Inpaint maximum crop side must cover the minimum" }
        require(coreSidePx > 0 && stepPx in 1..coreSidePx) { "Invalid inpaint tile grid" }
        require(batchTiles > 0) { "Inpaint batch size must be positive" }
    }

    /** Width of the band in which two neighbouring cores overlap; `0` in fast mode. */
    val overlapPx: Int
        get() = (coreSidePx - stepPx).coerceAtLeast(0)

    /**
     * Largest mask bbox that still fits in a single tile.
     *
     * The threshold follows the **fine** core of the tier even when the user runs fast mode: the
     * spec decides "one tile" from the fine grid, so switching quality only changes how a large
     * hole is cut, not whether a small one becomes a grid.
     */
    val singleTileMaxSidePx: Int
        get() = if (memoryTier == DeviceMemoryTier.LOW) LOW_FINE_CORE_PX else STANDARD_FINE_CORE_PX

    companion object {

        /** Context margin proven best by the crop-radius sweep (spec §2.3). */
        const val CONTEXT_MARGIN_PX: Int = 128

        /** Smallest crop side; 192–320 crops already work, 512 would blur the surroundings in. */
        const val MIN_CROP_SIDE_PX: Int = 320

        /** Fine core on a `> 4 GiB` device: crop 896, step 512, overlapping cores by 128 px. */
        const val STANDARD_FINE_CORE_PX: Int = 640

        /** Fine core on a `<= 4 GiB` device: crop 768, step 384, overlapping cores by 128 px. */
        const val LOW_FINE_CORE_PX: Int = 512

        /** Fast core on a `> 4 GiB` device: crop 1024, step 768, non-overlapping cores. */
        const val STANDARD_FAST_CORE_PX: Int = 768

        /** Fast core on a `<= 4 GiB` device: crop 768, step 512, non-overlapping cores. */
        const val LOW_FAST_CORE_PX: Int = 512

        /** Largest crop on a `> 4 GiB` device; bounds the model input near the measured +320 MB. */
        const val STANDARD_MAX_CROP_SIDE_PX: Int = 1024

        /** Largest crop on a `<= 4 GiB` device. */
        const val LOW_MAX_CROP_SIDE_PX: Int = 768

        /** Tiles per batch on a `> 4 GiB` device. */
        const val STANDARD_BATCH_TILES: Int = 96

        /** Tiles per batch on a `<= 4 GiB` device. */
        const val LOW_BATCH_TILES: Int = 24

        /**
         * Resolves the table row of [tier] and [mode] (spec §7.3).
         *
         * @return The geometry every planner and weight calculation must share for this run.
         */
        fun of(tier: DeviceMemoryTier, mode: InpaintQualityMode): InpaintCropSpec = when (tier) {
            DeviceMemoryTier.STANDARD -> when (mode) {
                InpaintQualityMode.FINE -> InpaintCropSpec(
                    memoryTier = tier,
                    qualityMode = mode,
                    maxCropSidePx = STANDARD_MAX_CROP_SIDE_PX,
                    coreSidePx = STANDARD_FINE_CORE_PX,
                    stepPx = STANDARD_FINE_CORE_PX - CONTEXT_MARGIN_PX,
                    batchTiles = STANDARD_BATCH_TILES
                )
                InpaintQualityMode.FAST -> InpaintCropSpec(
                    memoryTier = tier,
                    qualityMode = mode,
                    maxCropSidePx = STANDARD_MAX_CROP_SIDE_PX,
                    coreSidePx = STANDARD_FAST_CORE_PX,
                    stepPx = STANDARD_FAST_CORE_PX,
                    batchTiles = STANDARD_BATCH_TILES
                )
            }
            DeviceMemoryTier.LOW -> when (mode) {
                InpaintQualityMode.FINE -> InpaintCropSpec(
                    memoryTier = tier,
                    qualityMode = mode,
                    maxCropSidePx = LOW_MAX_CROP_SIDE_PX,
                    coreSidePx = LOW_FINE_CORE_PX,
                    stepPx = LOW_FINE_CORE_PX - CONTEXT_MARGIN_PX,
                    batchTiles = LOW_BATCH_TILES
                )
                InpaintQualityMode.FAST -> InpaintCropSpec(
                    memoryTier = tier,
                    qualityMode = mode,
                    maxCropSidePx = LOW_MAX_CROP_SIDE_PX,
                    coreSidePx = LOW_FAST_CORE_PX,
                    stepPx = LOW_FAST_CORE_PX,
                    batchTiles = LOW_BATCH_TILES
                )
            }
        }

        /** Standard-tier fine mode, the default of [InpaintCropBlender] and the planner. */
        val DEFAULT: InpaintCropSpec = of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE)
    }
}
