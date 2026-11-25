package cn.devcxl.photosync.editor.render

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.HalfFloatBits
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.vision.InpaintDecodedPatch
import cn.devcxl.photosync.editor.vision.InpaintPatchDecoder
import cn.devcxl.photosync.wrapper.LinearRawBuffer

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
    fun shouldConvertThePatchIntoTheTargetDomain_whenBlendingInto16BitRgb() {        val width = 4
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

    @Test
    fun shouldBlendOnlyTheSliceWindow_whenTheSliceDoesNotCoverTheWholeFrame() {
        val base = LinearRawBuffer.fromSamples(
            width = FRAME_EDGE,
            height = FRAME_EDGE,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgbaSamples = FloatArray(FRAME_EDGE * FRAME_EDGE * 4) { BASE_LINEAR }
        )
        val patch = storePatch(
            red = 240,
            green = 40,
            blue = 40,
            alpha = 255,
            bbox = InpaintBBox(0.25f, 0.25f, 0.75f, 0.75f)
        )
        // A slice that misses the patch bbox entirely (the right column band of the frame).
        val outside = sliceOf(base.rgba16fBuffer, InpaintPixelRect(12, 0, 16, 16))
        val untouched = nativeBytes(outside)

        val report = compositor.applyToLinearRgba16f(
            buffer = outside,
            width = 4,
            height = FRAME_EDGE,
            patches = listOf(patch),
            originX = 12,
            originY = 0,
            sourceWidth = FRAME_EDGE,
            sourceHeight = FRAME_EDGE
        )

        assertEquals(0L, report.blendedPixels)
        assertArrayEquals(untouched, nativeBytes(outside))

        // A slice that only partly overlaps the patch: the top-left 4x4 of it must change.
        val straddling = sliceOf(base.rgba16fBuffer, InpaintPixelRect(8, 8, 16, 16))
        val beforeStraddling = nativeBytes(straddling)
        compositor.applyToLinearRgba16f(
            buffer = straddling,
            width = 8,
            height = 8,
            patches = listOf(patch),
            originX = 8,
            originY = 8,
            sourceWidth = FRAME_EDGE,
            sourceHeight = FRAME_EDGE
        )
        val afterStraddling = nativeBytes(straddling)
        // Patch pixels [4,12) x [4,12) intersect the slice [8,16) x [8,16) in a 4x4 corner.
        var changed = 0
        for (pixel in 0 until 8 * 8) {
            if (!samePixel(beforeStraddling, afterStraddling, pixel)) changed++
        }
        assertEquals(4 * 4, changed)
        assertEquals(
            ExposureShaderMath.decodeSrgb(240 / 255.0f).toDouble(),
            halfAt(afterStraddling, pixel = 0, channel = 0).toDouble(),
            1e-3
        )
        assertEquals(
            BASE_LINEAR.toDouble(),
            halfAt(afterStraddling, pixel = 4 * 8 + 4, channel = 0).toDouble(),
            1e-4
        )
    }

    @Test
    fun shouldMatchTheFullFrameComposition_whenTheSamePatchesAreComposedSliceBySlice() {
        val base = LinearRawBuffer.fromSamples(
            width = FRAME_EDGE,
            height = FRAME_EDGE,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgbaSamples = FloatArray(FRAME_EDGE * FRAME_EDGE * 4) { BASE_LINEAR }
        )
        val first = storePatch(
            red = 30,
            green = 200,
            blue = 90,
            alpha = 255,
            bbox = InpaintBBox(0.1f, 0.1f, 0.6f, 0.6f)
        )
        val second = storePatch(
            red = 220,
            green = 120,
            blue = 10,
            alpha = 160,
            bbox = InpaintBBox(0.4f, 0.4f, 0.95f, 0.95f)
        )
        val patches = listOf(first, second)

        val whole = wholeComposed(base, patches)
        val bySlices = sliceComposed(base, patches, sliceEdge = 5)

        assertArrayEquals(
            "a band composed from slices must equal the same band composed in one pass",
            whole,
            bySlices
        )
    }

    /** Copies [base] and composes [patches] over the whole frame in one call. */
    private fun wholeComposed(base: LinearRawBuffer, patches: List<InpaintPatchRef>): ByteArray {
        val buffer = copyOf(base)
        compositor.applyToLinearRgba16f(
            buffer = buffer,
            width = base.width,
            height = base.height,
            patches = patches
        )
        return nativeBytes(buffer)
    }

    /** Copies [base] and composes [patches] slice by slice, in band order. */
    private fun sliceComposed(
        base: LinearRawBuffer,
        patches: List<InpaintPatchRef>,
        sliceEdge: Int
    ): ByteArray {
        val assembled = copyOf(base)
        for (top in 0 until base.height step sliceEdge) {
            for (left in 0 until base.width step sliceEdge) {
                val rect = InpaintPixelRect(
                    left = left,
                    top = top,
                    right = minOf(left + sliceEdge, base.width),
                    bottom = minOf(top + sliceEdge, base.height)
                )
                val slice = sliceOf(assembled, rect)
                compositor.applyToLinearRgba16f(
                    buffer = slice,
                    width = rect.width,
                    height = rect.height,
                    patches = patches,
                    originX = rect.left,
                    originY = rect.top,
                    sourceWidth = base.width,
                    sourceHeight = base.height
                )
                writeSlice(assembled, slice, base.width, rect)
            }
        }
        return nativeBytes(assembled)
    }

    private fun copyOf(base: LinearRawBuffer): ByteBuffer {
        val target = ByteBuffer
            .allocateDirect(base.width * base.height * LinearRawBuffer.BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        val view = base.rgba16fBuffer.duplicate().order(ByteOrder.nativeOrder())
        view.rewind()
        target.put(view)
        target.rewind()
        return target
    }

    private fun sliceOf(
        buffer: ByteBuffer,
        rect: InpaintPixelRect,
        frameWidth: Int = FRAME_EDGE
    ): ByteBuffer {
        val source = buffer.duplicate().order(ByteOrder.nativeOrder())
        val slice = ByteBuffer
            .allocateDirect(rect.width * rect.height * LinearRawBuffer.BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        for (y in 0 until rect.height) {
            val rowStart = ((rect.top + y) * frameWidth + rect.left) * LinearRawBuffer.BYTES_PER_PIXEL
            source.limit(rowStart + rect.width * LinearRawBuffer.BYTES_PER_PIXEL)
            source.position(rowStart)
            slice.put(source)
        }
        slice.rewind()
        return slice
    }

    private fun writeSlice(
        target: ByteBuffer,
        slice: ByteBuffer,
        frameWidth: Int,
        rect: InpaintPixelRect
    ) {
        val destination = target.duplicate().order(ByteOrder.nativeOrder())
        val source = slice.duplicate().order(ByteOrder.nativeOrder())
        source.rewind()
        for (y in 0 until rect.height) {
            val rowStart = ((rect.top + y) * frameWidth + rect.left) * LinearRawBuffer.BYTES_PER_PIXEL
            source.limit((y + 1) * rect.width * LinearRawBuffer.BYTES_PER_PIXEL)
            source.position(y * rect.width * LinearRawBuffer.BYTES_PER_PIXEL)
            destination.position(rowStart)
            destination.put(source)
        }
    }

    private fun nativeBytes(buffer: ByteBuffer): ByteArray {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        view.rewind()
        return ByteArray(view.remaining()).also(view::get)
    }

    private fun samePixel(first: ByteArray, second: ByteArray, pixel: Int): Boolean {
        val offset = pixel * LinearRawBuffer.BYTES_PER_PIXEL
        return (0 until 3).all { channel ->
            first[offset + channel * 2] == second[offset + channel * 2] &&
                first[offset + channel * 2 + 1] == second[offset + channel * 2 + 1]
        }
    }

    private fun halfAt(bytes: ByteArray, pixel: Int, channel: Int): Float {
        val offset = pixel * LinearRawBuffer.BYTES_PER_PIXEL + channel * 2
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        return HalfFloatBits.fromBits(((high shl 8) or low).toShort())
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

    private companion object {
        const val FRAME_EDGE = 16

        /** A dim linear-light base so a blended patch is unmistakably different from it. */
        const val BASE_LINEAR = 0.25f
    }
}
