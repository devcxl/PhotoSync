package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seam under test: [InpaintTilePatch] — the per-tile patch builder.
 *
 * The assertions lock the three consumption invariants: alpha is `0` outside the core, alpha is
 * `0` wherever the user did not paint, and the edge colour match only shifts generated pixels that
 * actually have alpha.
 */
class InpaintTilePatchTest {

    private val sourceWidth = 800
    private val sourceHeight = 800
    private val sourceGray = 0xFF646464.toInt()
    private val core = InpaintPixelRect(200, 200, 400, 400)
    private val crop = InpaintPixelRect(72, 72, 528, 528)
    private val painted = InpaintPixelRect(200, 200, 300, 400)
    private val generatedGray = 0xFF8C8C8C.toInt()

    @Test
    fun shouldKeepAlphaZeroOutsideTheCore_whenTheCropCarries128PxContext() {
        val mask = maskOf(painted)
        val tile = InpaintTilePlan(index = 0, coreRect = core, cropRect = crop)
        val patch = build(tile, mask)

        for (y in 0 until patch.height) {
            for (x in 0 until patch.width) {
                val sourceX = crop.left + x
                val sourceY = crop.top + y
                if (core.contains(sourceX, sourceY)) continue
                assertEquals(
                    "context pixel ($sourceX, $sourceY) must stay unpainted",
                    0,
                    patch.alphaAt(x, y)
                )
            }
        }
    }

    @Test
    fun shouldKeepAlphaZeroWhereverTheUserDidNotPaint_whenTheModelChangedTheSurroundings() {
        val mask = maskOf(painted)
        val tile = InpaintTilePlan(index = 0, coreRect = core, cropRect = crop)
        val patch = build(tile, mask)

        for (y in 0 until patch.height) {
            for (x in 0 until patch.width) {
                val sourceX = crop.left + x
                val sourceY = crop.top + y
                if (mask.coverageAt(sourceX, sourceY) > 0) continue
                assertEquals(
                    "unpainted pixel ($sourceX, $sourceY) must keep alpha 0",
                    0,
                    patch.alphaAt(x, y)
                )
                assertEquals(
                    "unpainted pixel ($sourceX, $sourceY) must stay byte-identical",
                    sourceGray,
                    patch.argb[y * patch.width + x]
                )
            }
        }
    }

    @Test
    fun shouldApplyTheEdgeMatchOnlyWhereAlphaIsPositive_whenTheGeneratedTileIsTinted() {
        val mask = maskOf(painted)
        val tile = InpaintTilePlan(index = 0, coreRect = core, cropRect = crop)
        val patch = build(tile, mask)

        val centreX = (painted.left + painted.right) / 2
        val centreY = (painted.top + painted.bottom) / 2
        val centreAlpha = patch.alphaAt(centreX - crop.left, centreY - crop.top)
        assertEquals("the hole centre must be fully repaired", 255, centreAlpha)
        // Source 100 vs generated 140 -> a -16 level offset (clamped), applied only inside alpha.
        assertEquals(124, (patch.argb[(centreY - crop.top) * patch.width + (centreX - crop.left)] shr 16) and 0xFF)

        val ringX = painted.right + 4 - crop.left
        val ringY = centreY - crop.top
        assertEquals("ring pixel must not be painted", 0, patch.alphaAt(ringX, ringY))
        assertEquals(
            "ring pixel must keep the source despite the colour match",
            sourceGray,
            patch.argb[ringY * patch.width + ringX]
        )
    }

    @Test
    fun shouldCoverExactlyTheTileCrop_whenThePatchIsPersisted() {
        val mask = maskOf(painted)
        val tile = InpaintTilePlan(index = 0, coreRect = core, cropRect = crop)
        val patch = build(tile, mask)

        assertEquals(crop.width, patch.width)
        assertEquals(crop.height, patch.height)
        assertEquals(crop.left.toFloat() / sourceWidth, patch.bbox.left, 1e-6f)
        assertEquals(crop.top.toFloat() / sourceHeight, patch.bbox.top, 1e-6f)
        assertEquals(crop.right.toFloat() / sourceWidth, patch.bbox.right, 1e-6f)
        assertEquals(crop.bottom.toFloat() / sourceHeight, patch.bbox.bottom, 1e-6f)
    }

    @Test
    fun shouldStayBoundedByTheTileCrop_whenThePaintedAreaIsLargerThanTheTile() {
        val mask = maskOf(InpaintPixelRect(180, 180, 440, 440))
        val tile = InpaintTilePlan(index = 0, coreRect = core, cropRect = crop)
        val patch = build(tile, mask)

        assertEquals("the patch must never union beyond its tile crop", crop.width, patch.width)
        assertEquals(crop.height, patch.height)
    }

    private fun build(tile: InpaintTilePlan, mask: InpaintMaskBuffer): InpaintBlendPatch {
        val source = IntArray(sourceWidth * sourceHeight) { sourceGray }
        val generated = GeneratedTilePixels.fromRgb(
            argb = IntArray(tile.cropRect.width * tile.cropRect.height) { generatedGray },
            width = tile.cropRect.width,
            height = tile.cropRect.height
        )
        return InpaintTilePatch.build(
            tile = tile,
            mask = mask,
            sourceArgb = source,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            generated = generated
        )
    }

    private fun maskOf(vararg painted: InpaintPixelRect): InpaintMaskBuffer {
        val coverage = ByteArray(sourceWidth * sourceHeight)
        for (rect in painted) {
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    coverage[y * sourceWidth + x] = 0xFF.toByte()
                }
            }
        }
        return InpaintMaskBuffer(sourceWidth, sourceHeight, coverage)
    }
}
