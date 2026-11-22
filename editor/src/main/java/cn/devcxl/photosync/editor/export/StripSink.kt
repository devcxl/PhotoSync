package cn.devcxl.photosync.editor.export

import java.nio.ByteBuffer

import cn.devcxl.photosync.wrapper.JpegStreamWriter

/**
 * Consumer of one fully assembled row strip of a bake.
 *
 * The production sink streams the strip into `libjpeg-turbo`; tests capture it so the rendered
 * `RGBA8` pixels can be compared without paying for JPEG quantization.
 */
internal fun interface StripSink {
    /**
     * Consumes [rows] full-width `RGBA8` scanlines.
     *
     * @param rgba Row buffer with `width * 4` bytes per row, bottom image row first (the order
     *   `glReadPixels` produces).
     * @param rows Number of valid rows in the strip.
     */
    fun writeStrip(rgba: ByteBuffer, rows: Int)
}

/**
 * Streams strips into the native baseline JPEG encoder, flipping rows for free by handing the
 * native `jpeg_write_scanlines` row pointers in reverse order.
 */
internal class JpegStripSink(private val jpeg: JpegStreamWriter) : StripSink {

    override fun writeStrip(rgba: ByteBuffer, rows: Int) {
        jpeg.writeStrip(rgba, rows, bottomUp = true)
    }
}
