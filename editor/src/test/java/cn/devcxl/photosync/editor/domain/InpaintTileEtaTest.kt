package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Seam under test: [InpaintTileEta] — the workload copy behind "预计 N 块 · 约 M 秒".
 *
 * The rules pinned here are spec §7.5: a local measurement wins, the conservative constants are
 * `LOW = 3.0 s/tile` and `STANDARD = 1.2 s/tile`, no valid estimate means tiles only, and a total
 * above 15 s is marked long-running without becoming a blocking condition.
 */
class InpaintTileEtaTest {

    @Test
    fun shouldPreferTheMeasuredTileTime_whenThePreviousRunPersistedOne() {
        val eta = InpaintTileEta.of(10, DeviceMemoryTier.STANDARD, measuredMsPerTile = 900L)

        assertEquals(900L, eta.millisPerTile)
        assertEquals(9_000L, eta.estimatedTotalMs)
        assertEquals(9L, eta.estimatedSeconds)
        assertTrue(eta.isMeasured)
        assertFalse(eta.isLongRunning)
    }

    @Test
    fun shouldUseTheStandardConservativeConstant_whenNothingWasMeasured() {
        val eta = InpaintTileEta.of(10, DeviceMemoryTier.STANDARD, measuredMsPerTile = null)

        assertEquals(InpaintTileEta.STANDARD_FALLBACK_MS_PER_TILE, eta.millisPerTile)
        assertEquals(12_000L, eta.estimatedTotalMs)
        assertEquals(12L, eta.estimatedSeconds)
        assertFalse(eta.isMeasured)
    }

    @Test
    fun shouldUseTheLowConservativeConstant_whenNothingWasMeasured() {
        val eta = InpaintTileEta.of(10, DeviceMemoryTier.LOW, measuredMsPerTile = null)

        assertEquals(InpaintTileEta.LOW_FALLBACK_MS_PER_TILE, eta.millisPerTile)
        assertEquals(30_000L, eta.estimatedTotalMs)
        assertEquals(30L, eta.estimatedSeconds)
        assertTrue(eta.isLongRunning)
    }

    @Test
    fun shouldFallBackToTheConstant_whenTheMeasurementIsNotPositive() {
        val eta = InpaintTileEta.of(4, DeviceMemoryTier.STANDARD, measuredMsPerTile = 0L)

        assertEquals(InpaintTileEta.STANDARD_FALLBACK_MS_PER_TILE, eta.millisPerTile)
        assertFalse(eta.isMeasured)
        assertEquals(4_800L, eta.estimatedTotalMs)
    }

    @Test
    fun shouldReportTilesOnly_whenNoTileWillRun() {
        val eta = InpaintTileEta.of(0, DeviceMemoryTier.STANDARD, measuredMsPerTile = 900L)

        assertEquals(0, eta.tileCount)
        assertNull(eta.millisPerTile)
        assertNull(eta.estimatedTotalMs)
        assertNull(eta.estimatedSeconds)
        assertFalse(eta.isLongRunning)
    }

    @Test
    fun shouldRoundSecondsUpAndMarkTheLongGate_whenTheTotalIsJustAbove15Seconds() {
        val justUnder = InpaintTileEta.of(12, DeviceMemoryTier.STANDARD, measuredMsPerTile = 1_250L)
        val justOver = InpaintTileEta.of(13, DeviceMemoryTier.STANDARD, measuredMsPerTile = 1_250L)

        assertEquals(15L, justUnder.estimatedSeconds)
        assertFalse(justUnder.isLongRunning)
        assertEquals(16_250L, justOver.estimatedTotalMs)
        assertEquals(17L, justOver.estimatedSeconds)
        assertTrue(justOver.isLongRunning)
    }

    @Test
    fun shouldMatchTheTierConstants_whenTheyAreReadFromTheSpecTable() {
        assertEquals(3_000L, InpaintTileEta.LOW_FALLBACK_MS_PER_TILE)
        assertEquals(1_200L, InpaintTileEta.STANDARD_FALLBACK_MS_PER_TILE)
        assertEquals(15_000L, InpaintTileEta.LONG_RUNNING_THRESHOLD_MS)
    }
}
