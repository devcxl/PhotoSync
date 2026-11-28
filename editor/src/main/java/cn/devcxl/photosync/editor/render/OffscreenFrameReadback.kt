package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20

import timber.log.Timber

/**
 * Reads a finished `Pass D` target back into top-down `ARGB_8888` pixels.
 *
 * Owns one reusable direct buffer so a chain that reads several frames (main frame, band) does not
 * allocate per read. Every failure is reported as `null` together with a log line, because the chain
 * contract is "never publish a partially corrected frame".
 */
internal class OffscreenFrameReadback {

    private var buffer: ByteBuffer? = null

    /**
     * Reads [width] x [height] pixels out of the currently bound [target].
     *
     * @return The pixels, or `null` when the driver reported an error or the context was lost.
     */
    fun read(target: OffscreenColorTarget, width: Int, height: Int): IntArray? {
        val requiredBytes = width * height * BYTES_PER_RGBA8_PIXEL
        val pixels = buffer?.takeIf { it.capacity() >= requiredBytes }
            ?: ByteBuffer.allocateDirect(requiredBytes)
                .order(ByteOrder.nativeOrder())
                .also { buffer = it }
        pixels.rewind()
        target.bind()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, BYTE_PACK_ALIGNMENT)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, DEFAULT_PACK_ALIGNMENT)
        target.unbind()
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Timber.w("Offscreen preview: GL error 0x%x after the Pass D read-back", error)
            return null
        }
        return OffscreenPreviewFrames.toArgb(pixels, width, height)
    }

    /** Drops the reusable buffer; the GL objects belong to the caller. */
    fun release() {
        buffer = null
    }

    private companion object {
        const val BYTES_PER_RGBA8_PIXEL = 4
        const val BYTE_PACK_ALIGNMENT = 1
        const val DEFAULT_PACK_ALIGNMENT = 4
    }
}
