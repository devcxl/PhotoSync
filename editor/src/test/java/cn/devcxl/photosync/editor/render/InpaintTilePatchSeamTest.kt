package cn.devcxl.photosync.editor.render

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.GeneratedTilePixels
import cn.devcxl.photosync.editor.domain.InpaintBlendPatch
import cn.devcxl.photosync.editor.domain.InpaintCropPlanner
import cn.devcxl.photosync.editor.domain.InpaintCropSpec
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.domain.InpaintTilePatch
import cn.devcxl.photosync.editor.domain.InpaintTilePlan
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.InpaintDecodedPatch
import cn.devcxl.photosync.editor.vision.InpaintPatchDecoder
import cn.devcxl.photosync.editor.vision.InpaintPatchEncoder

/**
 * Seam under test: two adjacent per-tile patches composed through [InpaintSourceCompositor].
 *
 * This is the end-to-end check of the "tile = patch" decision (spec §6.3 / §7.3): the overlap band
 * cross-fades with the complementary ramps, no pixel outside the painted area changes, and the
 * non-overlapping cores are written exactly once instead of being double-blended.
 */
class InpaintTilePatchSeamTest {

    private val tempDir: File = Files.createTempDirectory("inpaint-tile-seam").toFile()
    private val sourceWidth = 1200
    private val sourceHeight = 800
    private val sourceGray = 0xFF404040.toInt()
    private val painted = InpaintPixelRect(100, 200, 1000, 400)
    private val store = AiAssetStore(tempDir)
    private val codec = DimensionAwareCodec()
    private val compositor = InpaintSourceCompositor(store, codec)

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldKeepEveryUnpaintedPixelByteIdentical_whenTwoTilesAreComposed() {
        val source = IntArray(sourceWidth * sourceHeight) { sourceGray }
        val mask = filledMask(painted)
        val seam = seamPatches(mask, source)

        val composed = source.copyOf()
        compositor.applyToArgb(composed, sourceWidth, sourceHeight, seam.refs)

        for (y in 0 until sourceHeight) {
            for (x in 0 until sourceWidth) {
                if (mask.coverageAt(x, y) > 0) continue
                assertEquals(
                    "unpainted ($x, $y) must stay byte-identical",
                    sourceGray,
                    composed[y * sourceWidth + x]
                )
            }
        }
    }

    @Test
    fun shouldCrossFadeTheOverlapBand_whenTheTwoPatchesAreAppliedInOrder() {
        val source = IntArray(sourceWidth * sourceHeight) { sourceGray }
        val mask = filledMask(painted)
        val seam = seamPatches(mask, source)

        val composed = source.copyOf()
        compositor.applyToArgb(composed, sourceWidth, sourceHeight, seam.refs)

        val overlapStart = seam.secondTile.coreRect.left
        val overlapEnd = seam.firstTile.coreRect.right
        val y = (painted.top + painted.bottom) / 2
        var maxStep = 0
        for (x in overlapStart + FEATHER_MARGIN_PX until overlapEnd - FEATHER_MARGIN_PX) {
            val left = redAt(composed, x, y)
            val right = redAt(composed, x + 1, y)
            maxStep = maxOf(maxStep, kotlin.math.abs(right - left))
        }
        assertTrue("the seam must not jump, max adjacent step was $maxStep", maxStep <= 3)

        val middle = (overlapStart + overlapEnd) / 2
        val middleRed = redAt(composed, middle, y)
        assertTrue("the middle of the band must be a real blend, was $middleRed", middleRed in 57..183)
    }

    @Test
    fun shouldWriteEachCoreOnlyOnce_whenTheTilesDoNotOverlapOutsideTheirRamps() {
        val source = IntArray(sourceWidth * sourceHeight) { sourceGray }
        val mask = filledMask(painted)
        val seam = seamPatches(mask, source)

        val composed = source.copyOf()
        compositor.applyToArgb(composed, sourceWidth, sourceHeight, seam.refs)

        val y = (painted.top + painted.bottom) / 2
        val onlyFirst = (seam.firstTile.coreRect.left + FEATHER_MARGIN_PX) until
            (seam.secondTile.coreRect.left - FEATHER_MARGIN_PX)
        for (x in onlyFirst) {
            assertEquals("first core ($x, $y) must carry only tile 1", RED_FIRST, redAt(composed, x, y))
        }
        val onlySecond = (seam.firstTile.coreRect.right + FEATHER_MARGIN_PX) until
            (seam.secondTile.coreRect.right - FEATHER_MARGIN_PX)
        for (x in onlySecond) {
            assertEquals("second core ($x, $y) must carry only tile 2", RED_SECOND, redAt(composed, x, y))
        }
    }

    @Test
    fun shouldNotDoubleExposeTheOverlap_whenTheTwoAlphaBuffersAreSummed() {
        val source = IntArray(sourceWidth * sourceHeight) { sourceGray }
        val mask = filledMask(painted)
        val seam = seamPatches(mask, source)

        val composed = source.copyOf()
        compositor.applyToArgb(composed, sourceWidth, sourceHeight, seam.refs)

        val y = (painted.top + painted.bottom) / 2
        for (x in seam.secondTile.coreRect.left until seam.firstTile.coreRect.right) {
            val alphaFirst = alphaAt(seam.firstPatch, seam.firstTile, x, y)
            val alphaSecond = alphaAt(seam.secondPatch, seam.secondTile, x, y)
            assertTrue(
                "overlap alpha at $x must not exceed one full write",
                alphaFirst + alphaSecond <= 255
            )
            val red = redAt(composed, x, y)
            assertTrue("overlap red at $x overshot its two sources: $red", red in RED_SECOND..RED_FIRST)
        }
    }

    @Test
    fun shouldKeepTheContextMarginUntouched_whenOnlyTheCoreHasAlpha() {
        val source = IntArray(sourceWidth * sourceHeight) { sourceGray }
        val mask = filledMask(painted)
        val seam = seamPatches(mask, source)

        for (tile in listOf(seam.firstTile, seam.secondTile)) {
            val patch = if (tile === seam.firstTile) seam.firstPatch else seam.secondPatch
            for (y in 0 until patch.height) {
                for (x in 0 until patch.width) {
                    val sourceX = tile.cropRect.left + x
                    val sourceY = tile.cropRect.top + y
                    if (tile.coreRect.contains(sourceX, sourceY)) continue
                    assertEquals(
                        "context ($sourceX, $sourceY) must stay at alpha 0",
                        0,
                        patch.alphaAt(x, y)
                    )
                }
            }
        }
    }

    private data class Seam(
        val refs: List<InpaintPatchRef>,
        val firstTile: InpaintTilePlan,
        val secondTile: InpaintTilePlan,
        val firstPatch: InpaintBlendPatch,
        val secondPatch: InpaintBlendPatch
    )

    /**
     * Builds the first two tiles of a plan whose one painted blob spans the standard fine grid.
     *
     * The mask is a 900 x 200 bar, so the fine standard planner cuts two horizontally adjacent
     * cores with a real overlap band and a 128 px context margin on every side.
     */
    private fun seamPatches(mask: InpaintMaskBuffer, source: IntArray): Seam {
        val spec = InpaintCropSpec.of(DeviceMemoryTier.STANDARD, InpaintQualityMode.FINE)
        val tiles = InpaintCropPlanner(spec).plan(mask).tiles
        assertTrue("the seam fixture needs at least two tiles, got ${tiles.size}", tiles.size >= 2)
        val first = tiles[0]
        val second = tiles[1]
        assertTrue("the first two tiles must be adjacent in one row", first.coreRect.top == second.coreRect.top)
        assertTrue(
            "the seam fixture needs an overlap band",
            second.coreRect.left < first.coreRect.right
        )
        val firstPatch = buildPatch(first, mask, source, RED_FIRST_PIXEL)
        val secondPatch = buildPatch(second, mask, source, RED_SECOND_PIXEL)
        return Seam(
            refs = listOf(persist(firstPatch), persist(secondPatch)),
            firstTile = first,
            secondTile = second,
            firstPatch = firstPatch,
            secondPatch = secondPatch
        )
    }

    private fun buildPatch(
        tile: InpaintTilePlan,
        mask: InpaintMaskBuffer,
        source: IntArray,
        generatedColor: Int
    ): InpaintBlendPatch = InpaintTilePatch.build(
        tile = tile,
        mask = mask,
        sourceArgb = source,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        generated = GeneratedTilePixels.fromRgb(
            argb = IntArray(tile.cropRect.width * tile.cropRect.height) { generatedColor },
            width = tile.cropRect.width,
            height = tile.cropRect.height
        )
    )

    private fun persist(patch: InpaintBlendPatch): InpaintPatchRef {
        val patchPng = checkNotNull(codec.encodeRgb(patch.argb, patch.width, patch.height))
        val maskPng = checkNotNull(codec.encodeAlpha(patch.alpha, patch.width, patch.height))
        val patchRef = checkNotNull(store.write(9L, AiAssetKind.INPAINT_PATCH, patchPng))
        val maskRef = checkNotNull(store.write(9L, AiAssetKind.INPAINT_MASK, maskPng))
        return InpaintPatchRef(
            bbox = patch.bbox,
            patchRelPath = patchRef.relativePath,
            maskRelPath = maskRef.relativePath,
            sha256 = patchRef.sha256
        )
    }

    private fun alphaAt(patch: InpaintBlendPatch, tile: InpaintTilePlan, x: Int, y: Int): Int {
        val localX = x - tile.cropRect.left
        val localY = y - tile.cropRect.top
        if (localX !in 0 until patch.width || localY !in 0 until patch.height) return 0
        return patch.alphaAt(localX, localY)
    }

    private fun redAt(pixels: IntArray, x: Int, y: Int): Int =
        (pixels[y * sourceWidth + x] shr 16) and 0xFF

    private fun filledMask(rect: InpaintPixelRect): InpaintMaskBuffer {
        val coverage = ByteArray(sourceWidth * sourceHeight)
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                coverage[y * sourceWidth + x] = 0xFF.toByte()
            }
        }
        return InpaintMaskBuffer(sourceWidth, sourceHeight, coverage)
    }

    /**
     * PNG stand-in that carries its real dimensions: the compositor owns scaling, so a uniform
     * decoder like the existing one could not prove that both crop sizes compose pixel-exactly.
     */
    private class DimensionAwareCodec : InpaintPatchEncoder, InpaintPatchDecoder {

        override fun encodeRgb(argb: IntArray, width: Int, height: Int): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_BYTES + argb.size * Int.SIZE_BYTES)
            buffer.putInt(width)
            buffer.putInt(height)
            argb.forEach(buffer::putInt)
            return buffer.array()
        }

        override fun encodeAlpha(alpha: ByteArray, width: Int, height: Int): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_BYTES + alpha.size)
            buffer.putInt(width)
            buffer.putInt(height)
            buffer.put(alpha)
            return buffer.array()
        }

        override fun decode(patchPng: ByteArray, maskPng: ByteArray): InpaintDecodedPatch {
            val patch = ByteBuffer.wrap(patchPng)
            val width = patch.int
            val height = patch.int
            val argb = IntArray(width * height)
            for (index in argb.indices) argb[index] = patch.int
            val mask = ByteBuffer.wrap(maskPng)
            require(mask.int == width && mask.int == height) { "patch and mask dimensions differ" }
            val alpha = ByteArray(width * height).also(mask::get)
            return InpaintDecodedPatch(width = width, height = height, argb = argb, alpha = alpha)
        }

        private companion object {
            const val HEADER_BYTES = 8
        }
    }

    private companion object {
        /** Generated colour of the first tile: red-ish, shifted by the clamped edge match. */
        const val RED_FIRST_PIXEL = 0xFFC82828.toInt()

        /** Generated colour of the second tile: blue-ish. */
        const val RED_SECOND_PIXEL = 0xFF2828C8.toInt()

        /** Edge match clamps a 64-vs-200 difference to 16 levels, so red becomes 184 and 56. */
        const val RED_FIRST = 184
        const val RED_SECOND = 56

        /** Keeps ramp assertions away from the feathered hole edge. */
        const val FEATHER_MARGIN_PX = 20
    }
}
