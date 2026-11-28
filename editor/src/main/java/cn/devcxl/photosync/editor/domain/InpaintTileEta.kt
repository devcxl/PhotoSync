package cn.devcxl.photosync.editor.domain

import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Workload estimate of one inpaint confirmation (spec §7.5).
 *
 * The panel shows "预计 N 块 · 约 M 秒" before the user confirms. The per-tile time comes from the
 * previous run of the same device tier and model variant when available; otherwise a conservative
 * constant is used, so the estimate is never a made-up number and the copy keeps saying "约".
 *
 * @property tileCount Number of tiles the run will execute.
 * @property millisPerTile Per-tile time the estimate was built from; `null` when no estimate exists.
 * @property estimatedTotalMs Total time estimate; `null` when [tileCount] is zero.
 * @property isMeasured True when [millisPerTile] is a local measurement instead of the constant.
 */
data class InpaintTileEta(
    val tileCount: Int,
    val millisPerTile: Long?,
    val estimatedTotalMs: Long?,
    val isMeasured: Boolean
) {

    /** Whole seconds shown in the copy, rounded up so "约 1 秒" never hides a longer run. */
    val estimatedSeconds: Long?
        get() = estimatedTotalMs?.let { (it + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND }

    /** True when the run is expected to take more than [LONG_RUNNING_THRESHOLD_MS]. */
    val isLongRunning: Boolean
        get() = estimatedTotalMs != null && estimatedTotalMs > LONG_RUNNING_THRESHOLD_MS

    companion object {

        /** Conservative per-tile time on a `<= 4 GiB` device when nothing was measured yet. */
        const val LOW_FALLBACK_MS_PER_TILE: Long = 3_000L

        /** Conservative per-tile time on a `> 4 GiB` device when nothing was measured yet. */
        const val STANDARD_FALLBACK_MS_PER_TILE: Long = 1_200L

        /** Above this total the panel adds the non-blocking "分批处理" note. */
        const val LONG_RUNNING_THRESHOLD_MS: Long = 15_000L

        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * Builds the estimate.
         *
         * @param tileCount Planned tile count; `0` yields an estimate without seconds.
         * @param tier Device tier that selects the fallback constant.
         * @param measuredMsPerTile Local measurement for the tier and variant; non-positive values
         *   are ignored and the conservative constant is used instead.
         */
        fun of(tileCount: Int, tier: DeviceMemoryTier, measuredMsPerTile: Long?): InpaintTileEta {
            if (tileCount <= 0) {
                return InpaintTileEta(
                    tileCount = 0,
                    millisPerTile = null,
                    estimatedTotalMs = null,
                    isMeasured = false
                )
            }
            val measured = measuredMsPerTile?.takeIf { it > 0L }
            val perTile = measured ?: fallbackMsPerTile(tier)
            return InpaintTileEta(
                tileCount = tileCount,
                millisPerTile = perTile,
                estimatedTotalMs = tileCount.toLong() * perTile,
                isMeasured = measured != null
            )
        }

        private fun fallbackMsPerTile(tier: DeviceMemoryTier): Long = when (tier) {
            DeviceMemoryTier.LOW -> LOW_FALLBACK_MS_PER_TILE
            DeviceMemoryTier.STANDARD -> STANDARD_FALLBACK_MS_PER_TILE
        }
    }
}
