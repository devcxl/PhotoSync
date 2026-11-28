package cn.devcxl.photosync.editor.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.InpaintCropPlan
import cn.devcxl.photosync.editor.domain.InpaintCropPlanner
import cn.devcxl.photosync.editor.domain.InpaintCropSpec
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.settings.InpaintQualityMode

/**
 * Seam under test: [InpaintCropPlanner] — the §6.3/§7.3 tile geometry.
 *
 * The assertions lock the numbers the shift to tile-granularity depends on: no hard rejection at
 * 24 MP, the fine/fast grid counts of the spec table, 128 px of context, a 320 px minimum crop,
 * border clipping, and the overlap flags the tile weight consumes.
 */
class InpaintCropPlannerTest {

    @Test
    fun shouldPlanTheFineStandardGrid_whenA24MegapixelMaskIsFullyPainted() {
        val plan = plan(borderMask(6000, 4000), InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE))

        assertEquals(96, plan.tileCount)
        assertEquals(640, plan.tiles.first().coreRect.width)
        assertEquals(896, interiorTile(plan).cropRect.width)
    }

    @Test
    fun shouldPlanTheFastStandardGrid_whenA24MegapixelMaskIsFullyPainted() {
        val plan = plan(borderMask(6000, 4000), InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FAST))

        assertEquals(48, plan.tileCount)
        assertEquals(768, plan.tiles.first().coreRect.width)
        assertEquals(1024, interiorTile(plan).cropRect.width)
    }

    @Test
    fun shouldPlanTheFineLowTierGrid_whenA24MegapixelMaskIsFullyPainted() {
        val plan = plan(borderMask(6000, 4000), InpaintCropSpec.of(DeviceMemoryTier.LOW, InpaintQualityMode.FINE))

        assertEquals(176, plan.tileCount)
        assertEquals(512, plan.tiles.first().coreRect.width)
        assertEquals(768, interiorTile(plan).cropRect.width)
    }

    @Test
    fun shouldPlanTheFastLowTierGrid_whenA24MegapixelMaskIsFullyPainted() {
        val plan = plan(borderMask(6000, 4000), InpaintCropSpec.of(DeviceMemoryTier.LOW, InpaintQualityMode.FAST))

        assertEquals(96, plan.tileCount)
        assertEquals(512, plan.tiles.first().coreRect.width)
        assertEquals(768, interiorTile(plan).cropRect.width)
    }

    @Test
    fun shouldNeverRejectA24MegapixelPlan_whenEveryPixelIsPainted() {
        val plan = plan(borderMask(6000, 4000), InpaintCropSpec.DEFAULT)

        assertFalse(plan.isEmpty)
        assertTrue("all crops must respect the standard limit", plan.tiles.all {
            it.cropRect.width <= 1024 && it.cropRect.height <= 1024
        })
    }

    @Test
    fun shouldUseA320CropWithA128Context_whenTheHoleIsTiny() {
        val plan = plan(
            holeMask(1000, 800, InpaintPixelRect(300, 200, 310, 210)),
            InpaintCropSpec.DEFAULT
        )

        val tile = plan.tiles.single()
        assertEquals(320, tile.cropRect.width)
        assertEquals(320, tile.cropRect.height)
        assertEquals(InpaintPixelRect(300, 200, 310, 210), tile.coreRect)
        assertTrue(tile.cropRect.containsRect(InpaintPixelRect(172, 72, 438, 338)))
    }

    @Test
    fun shouldKeepTheFineCoreInASingleTile_whenTheHoleFits640() {
        val plan = plan(
            holeMask(3000, 3000, InpaintPixelRect(300, 300, 940, 700)),
            InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE)
        )

        assertEquals(1, plan.tileCount)
        assertEquals(640, plan.tiles.single().coreRect.width)
        assertEquals(896, plan.tiles.single().cropRect.width)
    }

    @Test
    fun shouldCutTheGrid_whenTheHoleExceedsTheFineCore() {
        val plan = plan(
            holeMask(3000, 3000, InpaintPixelRect(300, 300, 941, 700)),
            InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE)
        )

        assertEquals(2, plan.tileCount)
        assertTrue(plan.tiles.all { it.coreRect.width == 640 })
    }

    @Test
    fun shouldClipCropsToTheImage_whenTheHoleTouchesTheBorder() {
        val plan = plan(
            holeMask(1000, 800, InpaintPixelRect(0, 0, 40, 40)),
            InpaintCropSpec.DEFAULT
        )

        val tile = plan.tiles.single()
        assertEquals(0, tile.cropRect.left)
        assertEquals(0, tile.cropRect.top)
        assertTrue(tile.cropRect.right <= tile.cropRect.left + 320)
        assertTrue(tile.cropRect.bottom <= tile.cropRect.top + 320)
    }

    @Test
    fun shouldClipTheBottomRightCrop_whenTheHoleSitsAtTheCorner() {
        val plan = plan(
            holeMask(1000, 800, InpaintPixelRect(960, 760, 1000, 800)),
            InpaintCropSpec.DEFAULT
        )

        val tile = plan.tiles.single()
        assertEquals(1000, tile.cropRect.right)
        assertEquals(800, tile.cropRect.bottom)
        assertEquals(0, tile.cropRect.right - tile.cropRect.left - 320)
    }

    @Test
    fun shouldFlagOverlappingNeighbours_whenRunningTheFineGrid() {
        val plan = plan(borderMask(3000, 2000), InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE))

        val first = plan.tiles.first()
        assertFalse(first.hasLeftNeighbor)
        assertFalse(first.hasTopNeighbor)
        assertTrue(first.hasRightNeighbor)
        assertTrue(first.hasBottomNeighbor)
        assertEquals(128, first.rightOverlapPx)
        assertEquals(128, first.bottomOverlapPx)
        val inner = plan.components.single().tiles.first { it.index > 0 }
        assertTrue(inner.hasLeftNeighbor)
    }

    @Test
    fun shouldNotFlagNeighbours_whenRunningTheFastGrid() {
        val plan = plan(borderMask(3000, 2000), InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FAST))

        val first = plan.tiles.first()
        assertFalse(first.hasLeftNeighbor)
        assertFalse(first.hasTopNeighbor)
        assertFalse(first.hasRightNeighbor)
        assertFalse(first.hasBottomNeighbor)
        assertEquals(0, first.rightOverlapPx)
        assertEquals(0, first.bottomOverlapPx)
    }

    @Test
    fun shouldReturnAnEmptyPlan_whenNothingIsPainted() {
        val plan = plan(InpaintMaskBuffer(320, 320, ByteArray(320 * 320)), InpaintCropSpec.DEFAULT)

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.tileCount)
    }

    private fun plan(mask: InpaintMaskBuffer, spec: InpaintCropSpec): InpaintCropPlan =
        InpaintCropPlanner(spec).plan(mask)

    /** A tile whose crop is not clipped by the image border, i.e. the nominal crop side. */
    private fun interiorTile(plan: InpaintCropPlan) = plan.tiles.first { tile ->
        tile.cropRect.left > 0 &&
            tile.cropRect.top > 0 &&
            tile.cropRect.right < plan.sourceWidth &&
            tile.cropRect.bottom < plan.sourceHeight
    }

    /** A 2 px border ring: one connected blob whose bbox is exactly the whole image. */
    private fun borderMask(width: Int, height: Int): InpaintMaskBuffer {
        val coverage = ByteArray(width * height)
        val ring = 2
        for (y in 0 until height) {
            val top = y < ring
            val bottom = y >= height - ring
            for (x in 0 until width) {
                if (top || bottom || x < ring || x >= width - ring) {
                    coverage[y * width + x] = 0xFF.toByte()
                }
            }
        }
        return InpaintMaskBuffer(width, height, coverage)
    }

    private fun holeMask(
        width: Int,
        height: Int,
        hole: InpaintPixelRect
    ): InpaintMaskBuffer {
        val coverage = ByteArray(width * height)
        for (y in hole.top until hole.bottom) {
            for (x in hole.left until hole.right) {
                coverage[y * width + x] = 0xFF.toByte()
            }
        }
        return InpaintMaskBuffer(width, height, coverage)
    }
}
