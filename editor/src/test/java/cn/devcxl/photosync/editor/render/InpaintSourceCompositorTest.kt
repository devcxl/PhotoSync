package cn.devcxl.photosync.editor.render

import java.io.File
import java.nio.file.Files

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.vision.InpaintDecodedPatch
import cn.devcxl.photosync.editor.vision.InpaintPatchDecoder

/**
 * Seam under test: [InpaintSourceCompositor] — the Pre-Pass A ordered overlay.
 *
 * The compositor is the only place a repair patch is allowed to touch the original pixels, so the
 * tests pin the three safety properties: an empty list is a no-op, patches apply in graph order, and
 * a payload that fails its SHA-256 check is skipped instead of pasted.
 */
class InpaintSourceCompositorTest {

    private val tempDir: File = Files.createTempDirectory("inpaint-compositor").toFile()
    private val store = AiAssetStore(tempDir)
    private val decoder = FakeUniformDecoder()
    private val compositor = InpaintSourceCompositor(store, decoder)

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldDoNothingAndNotDecode_whenThePatchListIsEmpty() {
        val pixels = IntArray(10 * 10) { 0xFF102030.toInt() }
        val before = pixels.copyOf()

        val report = compositor.applyToArgb(pixels, 10, 10, emptyList())

        assertEquals(InpaintComposeReport.EMPTY, report)
        assertArrayEquals(before, pixels)
        assertEquals(0, decoder.calls)
    }

    @Test
    fun shouldApplyLaterPatchesOverEarlierOnes_whenTheirBoxesOverlap() {
        val pixels = IntArray(10 * 10) { 0xFF404040.toInt() }
        val bbox = InpaintBBox(0.1f, 0.1f, 0.9f, 0.9f)
        val red = storePatch(red = 220, green = 20, blue = 20, alpha = 255, bbox = bbox)
        val green = storePatch(red = 20, green = 220, blue = 20, alpha = 255, bbox = bbox)

        val report = compositor.applyToArgb(pixels, 10, 10, listOf(red, green))

        assertEquals(2, report.appliedPatches)
        assertEquals(0, report.skippedPatches)
        assertEquals(2L * 8 * 8, report.blendedPixels)
        assertEquals(0xFF14DC14.toInt(), pixels[5 * 10 + 5])
        assertEquals(0xFF404040.toInt(), pixels[0])
    }

    @Test
    fun shouldBlendHalfAlpha_whenTheMaskFeatherIsPartial() {
        val pixels = IntArray(10 * 10) { 0xFF000000.toInt() }
        val patch = storePatch(
            red = 200,
            green = 0,
            blue = 0,
            alpha = 128,
            bbox = InpaintBBox(0.0f, 0.0f, 1.0f, 1.0f)
        )

        compositor.applyToArgb(pixels, 10, 10, listOf(patch))

        val red = (pixels[0] shr 16) and 0xFF
        assertTrue("half alpha must land near half of the patch value", red in 98..102)
    }

    @Test
    fun shouldSkipTheTamperedPatchAndKeepTheValidOne_whenItsDigestNoLongerMatches() {
        val pixels = IntArray(10 * 10) { 0xFF000000.toInt() }
        val tampered = storePatch(red = 220, green = 0, blue = 0, alpha = 255)
        val valid = storePatch(red = 0, green = 220, blue = 0, alpha = 255)
        File(tempDir, tampered.patchRelPath).writeBytes(byteArrayOf(9, 9, 9))

        val report = compositor.applyToArgb(pixels, 10, 10, listOf(tampered, valid))

        assertEquals(1, report.appliedPatches)
        assertEquals(1, report.skippedPatches)
        assertEquals(0xFF00DC00.toInt(), pixels[5 * 10 + 5])
    }

    @Test
    fun shouldSkipThePatch_whenItsMaskDigestNoLongerMatches() {
        val pixels = IntArray(10 * 10) { 0xFF000000.toInt() }
        val patch = storePatch(red = 220, green = 0, blue = 0, alpha = 255)
        File(tempDir, patch.maskRelPath).writeBytes(byteArrayOf(1, 2, 3))

        val report = compositor.applyToArgb(pixels, 10, 10, listOf(patch))

        assertEquals(0, report.appliedPatches)
        assertEquals(1, report.skippedPatches)
        assertEquals(0xFF000000.toInt(), pixels[0])
    }

    @Test
    fun shouldConvertThePatchIntoTheTargetDomain_whenBlendingInto16BitRgb() {
        val width = 4
        val height = 4
        val displayPixels = ShortArray(width * height * 3) { (128 * 257).toShort() }
        val linearPixels = ShortArray(width * height * 3) { (128 * 257).toShort() }
        val patch = storePatch(
            red = 128,
            green = 128,
            blue = 128,
            alpha = 255,
            bbox = InpaintBBox(0.0f, 0.0f, 1.0f, 1.0f)
        )

        compositor.applyToLinear16Rgb(
            displayPixels,
            width,
            height,
            listOf(patch),
            Inpaint16BitDomain.DISPLAY
        )
        compositor.applyToLinear16Rgb(
            linearPixels,
            width,
            height,
            listOf(patch),
            Inpaint16BitDomain.LINEAR
        )

        assertEquals(128 * 257, displayPixels[0].toInt() and 0xFFFF)
        val expectedLinear = (ExposureShaderMath.decodeSrgb(128 / 255.0f) * 65535.0f).toInt()
        val actualLinear = linearPixels[0].toInt() and 0xFFFF
        assertTrue(
            "linear domain expected ~$expectedLinear, got $actualLinear",
            kotlin.math.abs(actualLinear - expectedLinear) <= 2
        )
    }

    private fun storePatch(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        bbox: InpaintBBox = InpaintBBox(0.2f, 0.2f, 0.8f, 0.8f)
    ): InpaintPatchRef {
        val patchPayload = byteArrayOf(red.toByte(), green.toByte(), blue.toByte())
        val maskPayload = byteArrayOf(alpha.toByte())
        val patchRef = checkNotNull(store.write(3L, AiAssetKind.INPAINT_PATCH, patchPayload))
        val maskRef = checkNotNull(store.write(3L, AiAssetKind.INPAINT_MASK, maskPayload))
        return InpaintPatchRef(
            bbox = bbox,
            patchRelPath = patchRef.relativePath,
            maskRelPath = maskRef.relativePath,
            sha256 = patchRef.sha256
        )
    }

    /** Interprets a three-byte patch as a uniform colour and a one-byte mask as uniform alpha. */
    private class FakeUniformDecoder : InpaintPatchDecoder {
        var calls = 0

        override fun decode(patchPng: ByteArray, maskPng: ByteArray): InpaintDecodedPatch {
            calls++
            val width = 8
            val height = 8
            val color = 0xFF000000.toInt() or
                ((patchPng[0].toInt() and 0xFF) shl 16) or
                ((patchPng[1].toInt() and 0xFF) shl 8) or
                (patchPng[2].toInt() and 0xFF)
            val alpha = maskPng[0].toInt() and 0xFF
            return InpaintDecodedPatch(
                width = width,
                height = height,
                argb = IntArray(width * height) { color },
                alpha = ByteArray(width * height) { alpha.toByte() }
            )
        }
    }
}
