package cn.devcxl.photosync.wrapper

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Streaming baseline JPEG encoder backed by the statically linked `libjpeg-turbo` encoder.
 *
 * The writer accepts `JCS_EXT_RGBA` scanlines straight from `glReadPixels` output, so the bake
 * pipeline never has to repack pixels. It is limited to a single baseline scan
 * (`optimize_coding = FALSE`), which is what keeps encoder memory at `O(1)` instead of allocating a
 * full-image DCT coefficient array.
 *
 * Full-width scanlines must be written top to bottom. The instance owns a native encoder handle;
 * call [finish] on success or [close] to abort.
 */
class JpegStreamWriter(
    output: OutputStream,
    width: Int,
    height: Int,
    quality: Int = JpegScanlineContract.DEFAULT_QUALITY
) : Closeable {

    private val contract = JpegScanlineContract(width, height, quality)
    private var handle: Long = NativeJpegEncoder.nativeBegin(output, width, height, quality)

    /** Encoded image width in pixels. */
    val width: Int get() = contract.width

    /** Encoded image height in pixels. */
    val height: Int get() = contract.height

    /** Scanlines already handed to the encoder. */
    val rowsWritten: Int get() = contract.rowsWritten

    init {
        if (handle == 0L) {
            throw IOException("libjpeg-turbo could not create a streaming encoder")
        }
    }

    /**
     * Encodes [rows] full-width RGBA scanlines from [rgba].
     *
     * @param rgba Direct buffer holding `width * 4` bytes per scanline.
     * @param rows Number of scanlines to consume.
     * @param bottomUp True when the buffer stores the bottom image row first, which is what
     *   `glReadPixels` produces; the native encoder then feeds the row pointers in reverse order
     *   instead of copying the strip.
     * @return The number of scanlines the encoder accepted.
     * @throws IOException when the native encoder accepted fewer rows than offered.
     */
    fun writeStrip(rgba: ByteBuffer, rows: Int, bottomUp: Boolean = false): Int {
        require(rgba.isDirect) { "JPEG scanlines must come from a direct buffer" }
        checkActive()
        contract.requireStrip(rows, rgba.capacity())
        val accepted = NativeJpegEncoder.nativeWrite(handle, rgba, rows, bottomUp)
        if (accepted != rows) {
            throw IOException("libjpeg-turbo accepted $accepted of $rows scanlines")
        }
        return contract.acceptRows(accepted)
    }

    /**
     * Flushes the image tail and releases the native encoder.
     *
     * @throws IOException when libjpeg-turbo fails to finish the image.
     */
    fun finish() {
        checkActive()
        val activeHandle = handle
        handle = 0L
        if (!NativeJpegEncoder.nativeFinish(activeHandle)) {
            throw IOException("libjpeg-turbo failed to finish the JPEG stream")
        }
    }

    /** Aborts the encode without emitting the missing scanlines. Safe to call twice. */
    override fun close() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) {
            NativeJpegEncoder.nativeAbort(activeHandle)
        }
    }

    private fun checkActive() {
        check(handle != 0L) { "The JPEG stream is already finished or aborted" }
    }
}

/**
 * JNI entry points of the streaming encoder. Callable only on a device with `libraw_wrapper.so`.
 */
internal object NativeJpegEncoder {

    init {
        System.loadLibrary("raw_wrapper")
    }

    external fun nativeBegin(output: OutputStream, width: Int, height: Int, quality: Int): Long

    external fun nativeWrite(handle: Long, rgba: ByteBuffer, rows: Int, bottomUp: Boolean): Int

    external fun nativeFinish(handle: Long): Boolean

    external fun nativeAbort(handle: Long)
}
