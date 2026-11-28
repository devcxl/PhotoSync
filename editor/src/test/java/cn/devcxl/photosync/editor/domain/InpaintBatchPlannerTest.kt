package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Seam under test: [InpaintBatchPlanner] — the budget slicing and the workload estimate.
 *
 * The table of spec §7.3 is pinned here: a 24 MP frame is 96 / 176 tiles in fine mode and 48 / 96
 * in fast mode, and the batch budget is 96 on STANDARD and 24 on LOW.
 */
class InpaintBatchPlannerTest {

    private val standardFine = InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE)
    private val standardFast = InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FAST)
    private val lowFine = InpaintCropSpec.of(DeviceMemoryTier.LOW, InpaintQualityMode.FINE)
    private val lowFast = InpaintCropSpec.of(DeviceMemoryTier.LOW, InpaintQualityMode.FAST)
    private val frame24Mp = InpaintPixelRect(0, 0, 6000, 4000)

    @Test
    fun shouldCutTilesIntoOrderedBudgetSizedBatches_whenThePlanIsLongerThanTheBudget() {
        val tiles = tiles(10)
        val batches = InpaintBatchPlanner.of(tiles, batchTiles = 4)

        assertEquals(3, batches.count)
        assertEquals(listOf(4, 4, 2), batches.batches.map { it.size })
        assertEquals(tiles, batches.batches.flatMap { it.tiles })
        assertEquals(listOf(0, 1, 2), batches.batches.map { it.index })
        assertEquals(10, batches.tileCount)
    }

    @Test
    fun shouldExposeTheUncommittedTilesOfARun_whenABatchHasCommitted() {
        val batches = InpaintBatchPlanner.of(tiles(10), batchTiles = 4)

        assertEquals(6, batches.remainingTilesAfter(0).size)
        assertEquals(2, batches.remainingTilesAfter(1).size)
        assertTrue(batches.remainingTilesAfter(2).isEmpty())
        assertEquals(10, batches.remainingTilesAfter(-1).size)
    }

    @Test
    fun shouldUseTheDeviceBatchBudget_whenThePlanCarriesItsOwnSpec() {
        assertEquals(96, standardFine.batchTiles)
        assertEquals(96, standardFast.batchTiles)
        assertEquals(24, lowFine.batchTiles)
        assertEquals(24, lowFast.batchTiles)

        val tiles = tiles(100)
        val plan = InpaintCropPlan(
            sourceWidth = 6000,
            sourceHeight = 4000,
            spec = standardFine,
            components = listOf(InpaintComponentPlan(0, frame24Mp, tiles))
        )
        val batches = InpaintBatchPlanner.plan(plan)

        assertEquals(2, batches.count)
        assertEquals(listOf(96, 4), batches.batches.map { it.size })
    }

    @Test
    fun shouldMatchThe24MegapixelTable_whenTheWholeFrameIsPainted() {
        assertEquals(96, InpaintBatchPlanner.estimateTileCount(frame24Mp, standardFine))
        assertEquals(48, InpaintBatchPlanner.estimateTileCount(frame24Mp, standardFast))
        assertEquals(176, InpaintBatchPlanner.estimateTileCount(frame24Mp, lowFine))
        assertEquals(96, InpaintBatchPlanner.estimateTileCount(frame24Mp, lowFast))
    }

    @Test
    fun shouldAgreeWithTheCropPlanner_whenTheMaskFillsOneBlob() {
        val mask = filledMask(800, 600)
        val plan = InpaintCropPlanner(standardFine).plan(mask)
        val bounds = InpaintPixelRect(0, 0, 800, 600)

        assertEquals(2, plan.tileCount)
        assertEquals(plan.tileCount, InpaintBatchPlanner.estimateTileCount(bounds, standardFine))
    }

    @Test
    fun shouldStayASingleTile_whenTheBboxFitsTheFineCore() {
        val bounds = InpaintPixelRect(100, 100, 600, 500)

        assertEquals(1, InpaintBatchPlanner.estimateTileCount(bounds, standardFine))
        assertEquals(1, InpaintBatchPlanner.estimateTileCount(bounds, lowFine))
    }

    @Test
    fun shouldReturnAnEmptyPlanAndNoWork_whenNothingIsPainted() {
        val empty = InpaintBatchPlanner.of(emptyList(), batchTiles = 96)

        assertEquals(0, empty.count)
        assertEquals(0, empty.tileCount)
        assertEquals(0, InpaintBatchPlanner.estimateTileCount(InpaintPixelRect(10, 10, 10, 10), standardFine))
    }

    private fun tiles(count: Int): List<InpaintTilePlan> = (0 until count).map { index ->
        val core = InpaintPixelRect(index * 20, 0, index * 20 + 20, 20)
        InpaintTilePlan(index = index, coreRect = core, cropRect = core)
    }

    private fun filledMask(width: Int, height: Int): InpaintMaskBuffer {
        val coverage = ByteArray(width * height) { 0xFF.toByte() }
        return InpaintMaskBuffer(width, height, coverage)
    }
}
