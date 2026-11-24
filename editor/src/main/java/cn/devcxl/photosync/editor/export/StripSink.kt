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
     * @param rgba Row buffer with `width * 4` bytes per row, top image row first (because every
     *   offscreen FBO in the bake chain renders top-down with `QuadOrientation.OFFSCREEN`).
     * @param rows Number of valid rows in the strip.
     */
    fun writeStrip(rgba: ByteBuffer, rows: Int)
}

/**
 * Streams top-down strips directly into the native baseline JPEG encoder.
 */
internal class JpegStripSink(private val jpeg: JpegStreamWriter) : StripSink {

    override fun writeStrip(rgba: ByteBuffer, rows: Int) {
        jpeg.writeStrip(rgba, rows, bottomUp = false)
    }
}
