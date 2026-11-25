package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [InpaintCropBlender] and its planner — the verified MI-GAN crop math.
 *
 * The assertions pin the four numbers the design depends on: 128 px of context, a 512 px minimum
 * crop, 256 px grid cells with 128 px overlap, and the exact pre/post-processing arithmetic that the
 * P0 gate record proved bit-identical to the Python pipeline (MAE = 0).
 */
class InpaintCropBlenderTest {

    private val blender = InpaintCropBlender()

    @Test
    fun shouldAdd128PxContextAndKeepASquare512Crop_whenPlanningOneStroke() {
        val mask = maskOf(1000, 800, InpaintPixelRect(300, 200, 340, 230))

        val plan = blender.plan(mask)

        assertEquals(1, plan.components.size)
        val component = plan.components.single()
        assertEquals(InpaintPixelRect(300, 200, 340, 230), component.maskBounds)
        val tile = component.tiles.single()
        assertEquals(512, tile.crop.width)
        assertEquals(512, tile.crop.height)
        val expectedContext = InpaintPixelRect(172, 72, 468, 358)
        assertTrue(tile.crop.left <= expectedContext.left)
        assertTrue(tile.crop.top <= expectedContext.top)
        assertTrue(tile.crop.right >= expectedContext.right)
        assertTrue(tile.crop.bottom >= expectedContext.bottom)
        assertEquals(tile.crop, component.patch)
    }

    @Test
    fun shouldCutA256GridWith128Overlap_whenTheMaskBboxExceeds256() {
        val mask = maskOf(2000, 2000, InpaintPixelRect(100, 500, 612, 532))

        val plan = blender.plan(mask)

        val tiles = plan.components.single().tiles
        assertEquals(3, tiles.size)
        assertEquals(listOf(100, 228, 356), tiles.map { it.core.left })
        assertEquals(256, tiles[0].core.width)
        assertEquals(256, tiles[1].core.width)
        assertEquals(256, tiles[2].core.width)
        assertEquals(128, tiles[0].core.right - tiles[1].core.left)
        assertEquals(128, tiles[1].core.right - tiles[2].core.left)
        assertEquals(512, tiles[1].crop.width)
        assertEquals(512, tiles[1].crop.height)
        assertEquals(100, tiles[1].crop.left)
    }

    @Test
    fun shouldNeverExceedA1024PxCrop_whenTheMaskCoversALargeArea() {
        val mask = maskOf(4000, 4000, InpaintPixelRect(1200, 1200, 2700, 2700))

        val plan = blender.plan(mask)

        val tiles = plan.components.flatMap { it.tiles }
        assertTrue(tiles.size > 1)
        assertTrue(tiles.all { it.crop.width <= 1024 && it.crop.height <= 1024 })
        assertTrue(tiles.all { it.crop.width >= 256 && it.crop.height >= 256 })
    }

    @Test
    fun shouldMatchTheReferencePreprocessContract_whenBuildingTheModelInput() {
        val width = 512
        val height = 512
        val pixels = IntArray(width * height) { 0xFFFF0080.toInt() }
        val hole = InpaintPixelRect(10, 10, 20, 20)
        val mask = maskOf(width, height, hole)
        val plan = blender.plan(mask)

        val input = blender.preprocessTile(pixels, width, height, mask, plan.components.single().tiles.single())

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
    fun shouldFeatherOver3PxAndKeepPixelsOutsideTheMaskByteIdentical_whenBlending() {
        val width = 512
        val height = 512
        val pixels = IntArray(width * height) { 0xFF202020.toInt() }
        val hole = InpaintPixelRect(236, 236, 276, 276)
        val mask = maskOf(width, height, hole)
        val plan = blender.plan(mask)
        val component = plan.components.single()
        val builder = blender.beginComponent(plan, mask, component.index, pixels)

        builder.addTile(component.tiles.single(), generatedWhite())
        val patch = builder.finish()

        val holeCentre = (hole.top + hole.bottom) / 2 - patch.bboxTopPx(plan)
        val centreX = (hole.left + hole.right) / 2 - patch.bboxLeftPx(plan)
        val centreAlpha = patch.alphaAt(centreX, holeCentre)
        assertEquals(255, centreAlpha)
        val onePxOutsideAlpha = patch.alphaAt(
            hole.left - 1 - patch.bboxLeftPx(plan),
            holeCentre
        )
        assertTrue("edge must fade, not clip", onePxOutsideAlpha in 1..254)
        val threePxOutsideAlpha = patch.alphaAt(
            hole.left - 3 - patch.bboxLeftPx(plan),
            holeCentre
        )
        assertTrue("feather must cover 3 px", threePxOutsideAlpha > 0)
        val sixPxOutsideAlpha = patch.alphaAt(
            hole.left - 6 - patch.bboxLeftPx(plan),
            holeCentre
        )
        assertEquals(0, sixPxOutsideAlpha)
        for (y in 0 until patch.height) {
            for (x in 0 until patch.width) {
                if (patch.alphaAt(x, y) == 0) {
                    assertEquals(
                        "pixel outside the feather must not change",
                        pixels[(y + patch.bboxTopPx(plan)) * width + x + patch.bboxLeftPx(plan)],
                        patch.argb[y * patch.width + x]
                    )
                }
            }
        }
        val centre = patch.argb[holeCentre * patch.width + centreX]
        assertTrue("generated content must replace the source", (centre shr 16 and 0xFF) > 100)
        assertNotEquals(0xFF202020.toInt(), centre)
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

    private fun InpaintBlendPatch.bboxLeftPx(plan: InpaintCropPlan): Int =
        (bbox.left * plan.sourceWidth + 0.5f).toInt()

    private fun InpaintBlendPatch.bboxTopPx(plan: InpaintCropPlan): Int =
        (bbox.top * plan.sourceHeight + 0.5f).toInt()

    private fun generatedWhite(): FloatArray =
        FloatArray(InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) { 1.0f }

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
