package cn.devcxl.photosync.editor.render

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.render.InpaintSourceCompositor
import cn.devcxl.photosync.editor.vision.InpaintDecodedPatch
import cn.devcxl.photosync.editor.vision.InpaintPatchDecoder
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Seam under test: [InpaintOverlay] — the one object the preview, the preview cache and the bake all
 * ask for the graph's repair patches.
 *
 * The overlay must be a zero-cost `null` for a graph that was never erased, and a copy must never
 * mutate the decode it was derived from: the editor keeps that decode for Before/After and for every
 * later recomposition.
 */
class InpaintOverlayTest {

    private val tempDir: File = Files.createTempDirectory("inpaint-overlay").toFile()
    private val store = AiAssetStore(tempDir)

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldReturnNull_whenTheGraphCarriesNoPatches() {
        assertNull(InpaintOverlay.of(store, EditGraph()))
    }

    @Test
    fun shouldReturnAnOverlay_whenTheGraphCarriesAPatch() {
        val overlay = InpaintOverlay.of(store, EditGraph(inpaint = listOf(redPatch())))

        assertEquals(false, overlay?.isEmpty)
        assertEquals(1, overlay?.patches?.size)
    }

    @Test
    fun shouldLeaveTheDecodeUntouched_whenComposingALinearCopy() {
        val source = LinearRawBuffer.fromSamples(
            width = 8,
            height = 8,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgbaSamples = FloatArray(8 * 8 * 4) { 0.125f }
        )
        val before = bytesOf(source.rgba16fBuffer)
        val overlay = InpaintOverlay(
            InpaintSourceCompositor(store, FakeUniformDecoder()),
            listOf(redPatch())
        )

        val composed = overlay.applyToLinearCopy(source) ?: error("the copy must be composed")

        assertEquals(before.toList(), bytesOf(source.rgba16fBuffer).toList())
        assertNotEquals(before.toList(), bytesOf(composed.rgba16fBuffer).toList())
        assertEquals(source.width, composed.width)
        assertEquals(source.height, composed.height)
        assertEquals(source.autoBrightGain, composed.autoBrightGain, 0.0f)
    }

    private fun redPatch(): InpaintPatchRef {
        val patchPayload = byteArrayOf(220.toByte(), 20, 20)
        val maskPayload = byteArrayOf(255.toByte())
        val patchRef = checkNotNull(store.write(3L, AiAssetKind.INPAINT_PATCH, patchPayload))
        val maskRef = checkNotNull(store.write(3L, AiAssetKind.INPAINT_MASK, maskPayload))
        return InpaintPatchRef(
            bbox = InpaintBBox(0.25f, 0.25f, 0.75f, 0.75f),
            patchRelPath = patchRef.relativePath,
            maskRelPath = maskRef.relativePath,
            sha256 = patchRef.sha256
        )
    }

    private fun bytesOf(buffer: ByteBuffer): ByteArray {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        view.rewind()
        return ByteArray(view.remaining()).also(view::get)
    }

    /** Interprets a three-byte patch as a uniform colour and a one-byte mask as uniform alpha. */
    private class FakeUniformDecoder : InpaintPatchDecoder {

        override fun decode(patchPng: ByteArray, maskPng: ByteArray): InpaintDecodedPatch {
            val color = 0xFF000000.toInt() or
                ((patchPng[0].toInt() and 0xFF) shl 16) or
                ((patchPng[1].toInt() and 0xFF) shl 8) or
                (patchPng[2].toInt() and 0xFF)
            val alpha = maskPng[0].toInt() and 0xFF
            return InpaintDecodedPatch(
                width = 4,
                height = 4,
                argb = IntArray(4 * 4) { color },
                alpha = ByteArray(4 * 4) { alpha.toByte() }
            )
        }
    }
}
