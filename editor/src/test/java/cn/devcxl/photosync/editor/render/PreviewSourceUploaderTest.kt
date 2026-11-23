package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Seam under test: [PreviewSourceUploader] — a rejected source upload must never be mistaken for a
 * successful one, because every later texture upload drains the GL error queue and would hide it.
 */
class PreviewSourceUploaderTest {

    @Test
    fun shouldReturnTheTexture_whenTheUploadAndTheErrorCheckSucceed() {
        val uploader = PreviewSourceUploader(
            uploadLinearHalfFloat = { _, _ -> 42 },
            readGlError = { GL_NO_ERROR },
            clearGlErrors = {}
        )

        assertEquals(42, uploader.upload(linearSource(), fullFrame = true))
    }

    @Test
    fun shouldReturnNull_whenTheDriverAllocatesNoTexture() {
        var releases = 0
        val uploader = PreviewSourceUploader(
            uploadLinearHalfFloat = { _, _ -> 0 },
            releaseTexture = { releases++ },
            readGlError = { GL_NO_ERROR },
            clearGlErrors = {}
        )

        assertNull(uploader.upload(linearSource(), fullFrame = true))
        assertEquals("nothing was created, so nothing may be deleted", 0, releases)
    }

    @Test
    fun shouldReleaseTheTextureAndFail_whenTheUploadLeavesAGlError() {
        val released = mutableListOf<Int>()
        val uploader = PreviewSourceUploader(
            uploadLinearHalfFloat = { _, _ -> 7 },
            releaseTexture = { released += it },
            readGlError = { GL_INVALID_OPERATION },
            clearGlErrors = {}
        )

        assertNull(uploader.upload(linearSource(), fullFrame = false))
        assertEquals(listOf(7), released)
    }

    @Test
    fun shouldDropStaleErrorsBeforeUploading() {
        var clears = 0
        var errorsRead = 0
        val uploader = PreviewSourceUploader(
            uploadLinearHalfFloat = { _, _ -> 5 },
            readGlError = { errorsRead++; GL_NO_ERROR },
            clearGlErrors = { clears++ }
        )

        uploader.upload(linearSource(), fullFrame = true)

        assertEquals("stale errors must be drained first", 1, clears)
        assertEquals("the check must belong to this upload", 1, errorsRead)
    }

    @Test
    fun shouldNeverUseTheBitmapPath_whenTheSourceIsLinear() {
        var bitmapUploads = 0
        val uploader = PreviewSourceUploader(
            uploadBitmap = { _, _ -> bitmapUploads++; 9 },
            uploadLinearHalfFloat = { _, _ -> 11 },
            readGlError = { GL_NO_ERROR },
            clearGlErrors = {}
        )

        assertEquals(11, uploader.upload(linearSource(), fullFrame = true))
        assertEquals(0, bitmapUploads)
    }

    private fun linearSource(): EditorSource.LinearRawSource {
        val buffer = LinearRawBuffer(
            width = 2,
            height = 2,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgba16fBuffer = ByteBuffer.allocateDirect(2 * 2 * 8).order(ByteOrder.nativeOrder())
        )
        return EditorSource.LinearRawSource(full = buffer, proxy = buffer)
    }

    private companion object {
        private const val GL_NO_ERROR = 0
        private const val GL_INVALID_OPERATION = 0x0502
    }
}
