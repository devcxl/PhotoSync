package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Seam under test: [InpaintCropBlender] and its planner — the verified MI-GAN crop math.
 *
 * The assertions pin the numbers the E1 design depends on: 128 px of context, a 320 px minimum
 * crop, a 640 px fine core with 128 px overlap on a standard-tier device, and the exact
 * pre/post-processing arithmetic that the P0 gate record proved bit-identical to the Python
 * pipeline (MAE = 0).
 */
class InpaintCropBlenderTest {

    private val blender = InpaintCropBlender()

    @Test
    fun shouldAdd128PxContextAndKeepA320MinimumCrop_whenPlanningOneSmallStroke() {
        val mask = maskOf(1000, 800, InpaintPixelRect(300, 200, 340, 230))

        val plan = blender.plan(mask)

        assertEquals(InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE), plan.spec)
        assertEquals(1, plan.components.size)
        val component = plan.components.single()
        assertEquals(InpaintPixelRect(300, 200, 340, 230), component.maskBounds)
        val tile = component.tiles.single()
        assertEquals(320, tile.cropRect.width)
        assertEquals(320, tile.cropRect.height)
        val expectedContext = InpaintPixelRect(172, 72, 468, 358)
        assertTrue(tile.cropRect.containsRect(expectedContext))
        assertEquals(component.maskBounds, tile.coreRect)
        assertEquals(0, tile.leftOverlapPx + tile.rightOverlapPx + tile.topOverlapPx + tile.bottomOverlapPx)
    }

    @Test
    fun shouldCutAFineCoreGridWithOverlappingCores_whenTheMaskBboxExceeds640() {
        val mask = maskOf(2000, 2000, InpaintPixelRect(100, 500, 900, 700))

        val plan = blender.plan(mask)

        val tiles = plan.components.single().tiles
        assertEquals(2, tiles.size)
        assertEquals(listOf(100, 260), tiles.map { it.coreRect.left })
        assertEquals(640, tiles[0].coreRect.width)
        assertEquals(640, tiles[1].coreRect.width)
        assertEquals(480, tiles[0].rightOverlapPx)
        assertEquals(480, tiles[1].leftOverlapPx)
        assertEquals(0, tiles[0].leftOverlapPx)
        assertEquals(896, tiles[1].cropRect.width)
        assertEquals(896, tiles[1].cropRect.height)
    }

    @Test
    fun shouldNeverExceedTheTierCropLimit_whenTheMaskCoversALargeArea() {
        val mask = maskOf(4000, 4000, InpaintPixelRect(1200, 1200, 2700, 2700))

        val plan = blender.plan(mask)

        val tiles = plan.tiles
        assertTrue(tiles.size > 1)
        assertTrue(tiles.all { it.cropRect.width <= 1024 && it.cropRect.height <= 1024 })
        assertTrue(tiles.all { it.cropRect.width >= 320 && it.cropRect.height >= 320 })
    }

    @Test
    fun shouldMatchTheReferencePreprocessContract_whenBuildingTheModelInput() {
        val width = 512
        val height = 512
        val pixels = IntArray(width * height) { 0xFFFF0080.toInt() }
        val hole = InpaintPixelRect(10, 10, 20, 20)
        val mask = maskOf(width, height, hole)
        val tile = InpaintTilePlan(
            index = 0,
            coreRect = hole,
            cropRect = InpaintPixelRect(0, 0, width, height)
        )

        val input = blender.preprocessTile(pixels, width, height, mask, tile)

        assertEquals(InpaintCropBlender.MODEL_INPUT_ELEMENTS, input.size)
        val plane = width * height
        // Known pixel (0, 0): m = 1, mask channel = 0.5, x = 2 * channel / 255 - 1.
        assertEquals(0.5f, input[0], 1e-6f)
        assertEquals(1.0f, input[plane], 1e-6f)
        assertEquals(-1.0f, input[plane * 2], 1e-6f)
        assertEquals(128.0f / 255.0f * 2.0f - 1.0f, input[plane * 3], 1e-6f)
        // Hole pixel (15, 15): m = 0, so the mask channel is -0.5 and RGB are zeroed.
        val holeIndex = 15 * width + 15
        assertEquals(-0.5f, input[holeIndex], 1e-6f)
        assertEquals(0.0f, input[plane + holeIndex], 1e-6f)
        assertEquals(0.0f, input[plane * 2 + holeIndex], 1e-6f)
        assertEquals(0.0f, input[plane * 3 + holeIndex], 1e-6f)
    }

    @Test
    fun shouldTruncateLikeTheReferencePostprocess_whenDecodingAnOutputChannel() {
        assertEquals(127, InpaintCropBlender.encodeOutputChannel(0.0f))
        assertEquals(255, InpaintCropBlender.encodeOutputChannel(1.0f))
        assertEquals(0, InpaintCropBlender.encodeOutputChannel(-1.0f))
        assertEquals(191, InpaintCropBlender.encodeOutputChannel(0.5f))
        assertEquals(0, InpaintCropBlender.encodeOutputChannel(-4.0f))
        assertEquals(255, InpaintCropBlender.encodeOutputChannel(4.0f))
    }

    @Test
    fun shouldMergeStrokes_whenTheir128PxContextRectsOverlap() {
        val close = maskOf(
            1000,
            500,
            InpaintPixelRect(100, 100, 110, 110),
            InpaintPixelRect(350, 100, 360, 110)
        )
        val far = maskOf(
            1000,
            500,
            InpaintPixelRect(100, 100, 110, 110),
            InpaintPixelRect(500, 100, 510, 110)
        )

        assertEquals(1, blender.plan(close).components.size)
        assertEquals(2, blender.plan(far).components.size)
    }

    @Test
    fun shouldTreatDiagonalPixelsAsOneBlob_whenExtractingComponents() {
        val coverage = ByteArray(20 * 20)
        coverage[5 * 20 + 5] = 0xFF.toByte()
        coverage[6 * 20 + 6] = 0xFF.toByte()

        val components = InpaintComponentExtractor()
            .extract(InpaintMaskBuffer(20, 20, coverage))

        assertEquals(1, components.size)
        assertEquals(InpaintPixelRect(5, 5, 7, 7), components.single())
    }

    private fun maskOf(
        width: Int,
        height: Int,
        vararg painted: InpaintPixelRect
    ): InpaintMaskBuffer {
        val coverage = ByteArray(width * height)
        for (rect in painted) {
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    coverage[y * width + x] = 0xFF.toByte()
                }
            }
        }
        return InpaintMaskBuffer(width, height, coverage)
    }
}
