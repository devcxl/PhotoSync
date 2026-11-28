package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [InpaintTileWeight] — the only implementation of the tile alpha rule.
 *
 * The assertions lock the invariants E2's seam tests depend on: two ramps over an overlap band are
 * complementary, a four-tile junction never exceeds full opacity, and a border or a
 * non-overlapping fast-mode cell needs no ramp at all.
 */
class InpaintTileWeightTest {

    @Test
    fun shouldMakeTwoOverlappingRampsComplementary_whenTheCoresShareA128PxBand() {
        val first = tile(core = rect(100, 100, 740, 740), rightOverlapPx = 128)
        val second = tile(core = rect(612, 100, 1252, 740), leftOverlapPx = 128)
        val mask = fullyPainted(1400, 900)

        for (x in 612 until 740) {
            val firstWeight = InpaintTileWeight.rampWeight(first, x, 400)
            val secondWeight = InpaintTileWeight.rampWeight(second, x, 400)
            assertEquals(1.0f, firstWeight + secondWeight, 1e-5f)
        }

        val firstAlpha = InpaintTileWeight.alpha(mask, first)
        val secondAlpha = InpaintTileWeight.alpha(mask, second)
        var overlaps = 0
        for (sourceX in 612 until 740) {
            val firstIndex = (400 - first.cropRect.top) * first.cropRect.width +
                (sourceX - first.cropRect.left)
            val secondIndex = (400 - second.cropRect.top) * second.cropRect.width +
                (sourceX - second.cropRect.left)
            val sum = (firstAlpha[firstIndex].toInt() and 0xFF) +
                (secondAlpha[secondIndex].toInt() and 0xFF)
            assertTrue("overlap must never exceed full opacity, was $sum", sum <= 255)
            assertTrue("overlap must stay effectively seamless, was $sum", sum >= 254)
            overlaps++
        }
        assertEquals(128, overlaps)
    }

    @Test
    fun shouldKeepTheFourTileJunctionAtMostFullOpacity_whenAllFourCoresOverlap() {
        val topLeft = tile(
            core = rect(0, 0, 640, 640),
            rightOverlapPx = 128,
            bottomOverlapPx = 128
        )
        val topRight = tile(
            core = rect(512, 0, 1152, 640),
            leftOverlapPx = 128,
            rightOverlapPx = 128,
            bottomOverlapPx = 128
        )
        val bottomLeft = tile(
            core = rect(0, 512, 640, 1152),
            rightOverlapPx = 128,
            topOverlapPx = 128
        )
        val bottomRight = tile(
            core = rect(512, 512, 1152, 1152),
            leftOverlapPx = 128,
            topOverlapPx = 128
        )

        val junctionSum = InpaintTileWeight.rampWeight(topLeft, 600, 600) +
            InpaintTileWeight.rampWeight(topRight, 600, 600) +
            InpaintTileWeight.rampWeight(bottomLeft, 600, 600) +
            InpaintTileWeight.rampWeight(bottomRight, 600, 600)

        assertEquals(1.0f, junctionSum, 1e-5f)
        val alphaSum = alphaAt(InpaintTileWeight.alpha(fullyPainted(1400, 1400), topLeft), topLeft, 600, 600) +
            alphaAt(InpaintTileWeight.alpha(fullyPainted(1400, 1400), topRight), topRight, 600, 600) +
            alphaAt(InpaintTileWeight.alpha(fullyPainted(1400, 1400), bottomLeft), bottomLeft, 600, 600) +
            alphaAt(InpaintTileWeight.alpha(fullyPainted(1400, 1400), bottomRight), bottomRight, 600, 600)
        assertTrue("four tile junction must not over-expose, was $alphaSum", alphaSum <= 255)
        assertTrue("all four tiles must contribute", alphaSum >= 250)
    }

    @Test
    fun shouldNotApplyARamp_whenThereIsNoOverlappingNeighbour() {
        val tile = tile(core = rect(100, 100, 740, 740))

        assertEquals(1.0f, InpaintTileWeight.rampWeight(tile, 100, 400), 1e-6f)
        assertEquals(1.0f, InpaintTileWeight.rampWeight(tile, 739, 400), 1e-6f)
        assertEquals(0.0f, InpaintTileWeight.rampWeight(tile, 740, 400), 1e-6f)
    }

    private fun alphaAt(alpha: ByteArray, tile: InpaintTilePlan, x: Int, y: Int): Int {
        val index = (y - tile.cropRect.top) * tile.cropRect.width + (x - tile.cropRect.left)
        return alpha[index].toInt() and 0xFF
    }

    private fun tile(
        core: InpaintPixelRect,
        leftOverlapPx: Int = 0,
        rightOverlapPx: Int = 0,
        topOverlapPx: Int = 0,
        bottomOverlapPx: Int = 0
    ): InpaintTilePlan = InpaintTilePlan(
        index = 0,
        coreRect = core,
        cropRect = core.expandedBy(128).clampedTo(2000, 2000),
        leftOverlapPx = leftOverlapPx,
        rightOverlapPx = rightOverlapPx,
        topOverlapPx = topOverlapPx,
        bottomOverlapPx = bottomOverlapPx
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): InpaintPixelRect =
        InpaintPixelRect(left, top, right, bottom)

    private fun fullyPainted(width: Int, height: Int): InpaintMaskBuffer =
        InpaintMaskBuffer(width, height, ByteArray(width * height) { 0xFF.toByte() })
}
