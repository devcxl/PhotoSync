package cn.devcxl.photosync.editor.export

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Streaming lossless RGBA8 PNG encoder for full-resolution bakes.
 *
 * The sink writes the PNG signature and a `color type 6` IHDR, then consumes full-width `RGBA8`
 * scanlines strip by strip. Every row is filtered with either the Sub or the Paeth predictor
 * (whichever has the smaller signed-byte cost) and handed straight to [Deflater], whose output is
 * emitted as `IDAT` chunks of [idatChunkBytes]. Only three row-sized scratch buffers exist at any
 * time, so peak memory is `O(image width)` even for 60MP transparent exports.
 *
 * The caller owns [output] and closes it after the last strip; an aborted bake leaves the stream
 * without `IEND` so a partially written gallery entry can never be mistaken for a valid image.
 *
 * @param output Destination of the encoded bytes; not closed by this sink.
 * @param width Image width in pixels.
 * @param height Image height in pixels.
 * @param isActive Cancellation probe evaluated before every row.
 * @param idatChunkBytes Maximum payload size of one `IDAT` chunk.
 */
internal class PngStripSink(
    private val output: OutputStream,
    private val width: Int,
    private val height: Int,
    private val isActive: () -> Boolean = { true },
    private val idatChunkBytes: Int = DEFAULT_IDAT_CHUNK_BYTES
) : StripSink, Closeable {

    private val rowBytes = width * BYTES_PER_PIXEL
    private val rawRow = ByteArray(rowBytes)
    private val subRow = ByteArray(rowBytes)
    private val paethRow = ByteArray(rowBytes)
    private val previousRow = ByteArray(rowBytes)
    private val filteredRow = ByteArray(rowBytes + 1)
    private val idatBuffer = ByteArray(idatChunkBytes)
    private val crc = CRC32()
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)

    private var exifPayload: ByteArray? = null
    private var headerWritten = false
    private var rowsWritten = 0
    private var idatFill = 0
    private var finished = false
    private var closed = false
    private var deflaterReleased = false

    init {
        require(width > 0) { "PNG width must be positive" }
        require(height > 0) { "PNG height must be positive" }
        require(idatChunkBytes > 0) { "IDAT chunk size must be positive" }
    }

    /**
     * Registers the optional `eXIf` payload written directly after `IHDR`.
     *
     * The payload is a bare TIFF/EXIF block (no `Exif\0\0` prefix), matching the PNG eXIf chunk
     * contract. It must be supplied before the first strip so the chunk precedes every `IDAT`.
     *
     * @throws IllegalStateException when image data or the header was already written.
     */
    fun writeExifChunk(exifBytes: ByteArray) {
        check(!headerWritten) { "eXIf must be written before the first PNG strip" }
        exifPayload = exifBytes.copyOf()
    }

    override fun writeStrip(rgba: ByteBuffer, rows: Int) {
        check(!closed) { "PNG stream is already closed" }
        check(!finished) { "PNG stream is already finished" }
        if (rows <= 0) return
        if (rows > height - rowsWritten) {
            throw IOException("PNG strip of $rows rows exceeds the remaining image height")
        }
        if (rgba.capacity() < rows * rowBytes) {
            throw IOException("RGBA strip buffer is smaller than $rows rows of $width pixels")
        }
        ensureHeader()
        val source = rgba.duplicate()
        source.rewind()
        for (row in 0 until rows) {
            if (!isActive()) {
                throw CancellationException("PNG export cancelled at row ${rowsWritten}")
            }
            source.get(rawRow, 0, rowBytes)
            encodeRow()
        }
    }

    /**
     * Flushes the deflated tail, writes `IEND` and releases the deflater.
     *
     * @throws IOException when the stream holds fewer rows than the declared height.
     */
    fun finish() {
        check(!closed) { "PNG stream is already closed" }
        if (finished) return
        ensureHeader()
        if (rowsWritten != height) {
            throw IOException("PNG stream ended after $rowsWritten of $height rows")
        }
        deflater.finish()
        while (!deflater.finished()) {
            if (idatFill == idatBuffer.size) flushIdatChunk()
            val produced = deflater.deflate(idatBuffer, idatFill, idatBuffer.size - idatFill)
            if (produced <= 0) break
            idatFill += produced
        }
        if (!deflater.finished()) {
            throw IOException("Deflater could not finish the PNG stream")
        }
        if (idatFill > 0) flushIdatChunk()
        writeChunk(CHUNK_IEND, EMPTY_CHUNK)
        output.flush()
        finished = true
        releaseDeflater()
    }

    /**
     * Releases the native deflater without writing `IEND`. Safe to call twice and after [finish].
     */
    override fun close() {
        closed = true
        releaseDeflater()
    }

    private fun ensureHeader() {
        if (headerWritten) return
        output.write(PNG_SIGNATURE)
        writeChunk(CHUNK_IHDR, ihdrPayload())
        exifPayload?.let { writeChunk(CHUNK_EXIF, it) }
        headerWritten = true
    }

    private fun ihdrPayload(): ByteArray {
        val payload = ByteArray(IHDR_PAYLOAD_SIZE)
        writeInt(payload, 0, width)
        writeInt(payload, 4, height)
        payload[8] = BIT_DEPTH_8
        payload[9] = COLOR_TYPE_RGBA
        payload[10] = COMPRESSION_DEFLATE
        payload[11] = FILTER_METHOD_ADAPTIVE
        payload[12] = INTERLACE_NONE
        return payload
    }

    private fun encodeRow() {
        PngRowFilter.applySub(rawRow, subRow)
        PngRowFilter.applyPaeth(rawRow, previousRow, paethRow)
        val usePaeth = PngRowFilter.cost(paethRow) < PngRowFilter.cost(subRow)
        filteredRow[0] = if (usePaeth) FILTER_PAETH else FILTER_SUB
        if (usePaeth) {
            paethRow.copyInto(filteredRow, destinationOffset = 1)
        } else {
            subRow.copyInto(filteredRow, destinationOffset = 1)
        }
        deflater.setInput(filteredRow)
        drainDeflater()
        rawRow.copyInto(previousRow)
        rowsWritten++
    }

    private fun drainDeflater() {
        while (!deflater.needsInput()) {
            if (idatFill == idatBuffer.size) flushIdatChunk()
            val produced = deflater.deflate(idatBuffer, idatFill, idatBuffer.size - idatFill)
            if (produced <= 0) return
            idatFill += produced
        }
    }

    private fun flushIdatChunk() {
        writeChunk(CHUNK_IDAT, idatBuffer, idatFill)
        idatFill = 0
    }

    private fun writeChunk(type: String, data: ByteArray, length: Int = data.size) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(output, length)
        output.write(typeBytes)
        if (length > 0) {
            output.write(data, 0, length)
        }
        crc.reset()
        crc.update(typeBytes)
        if (length > 0) {
            crc.update(data, 0, length)
        }
        writeInt(output, crc.value.toInt())
    }

    private fun releaseDeflater() {
        if (deflaterReleased) return
        deflaterReleased = true
        deflater.end()
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        const val CHUNK_IHDR = "IHDR"
        const val CHUNK_EXIF = "eXIf"
        const val CHUNK_IDAT = "IDAT"
        const val CHUNK_IEND = "IEND"
        val EMPTY_CHUNK = ByteArray(0)

        const val BYTES_PER_PIXEL = 4
        const val IHDR_PAYLOAD_SIZE = 13
        const val BIT_DEPTH_8: Byte = 8
        const val COLOR_TYPE_RGBA: Byte = 6
        const val COMPRESSION_DEFLATE: Byte = 0
        const val FILTER_METHOD_ADAPTIVE: Byte = 0
        const val INTERLACE_NONE: Byte = 0

        const val FILTER_SUB: Byte = 1
        const val FILTER_PAETH: Byte = 4
        const val DEFAULT_IDAT_CHUNK_BYTES = 64 * 1024

        fun writeInt(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        fun writeInt(target: OutputStream, value: Int) {
            target.write(value ushr 24)
            target.write(value ushr 16)
            target.write(value ushr 8)
            target.write(value)
        }
    }
}

/**
 * PNG row predictors used by [PngStripSink] (`filter type 1 = Sub`, `filter type 4 = Paeth`).
 *
 * The arithmetic follows the PNG specification: bytes are treated as unsigned and the difference
 * is truncated back to eight bits.
 */
internal object PngRowFilter {

    /** Fills [out] with the Sub-filtered [row] (`R - Left`). */
    fun applySub(row: ByteArray, out: ByteArray) {
        require(row.size == out.size) { "Filter buffers must match" }
        for (index in row.indices) {
            val left = if (index >= BYTES_PER_PIXEL) row[index - BYTES_PER_PIXEL].toInt() and 0xFF else 0
            out[index] = ((row[index].toInt() and 0xFF) - left).toByte()
        }
    }

    /** Fills [out] with the Paeth-filtered [row] using [previous] as the row above. */
    fun applyPaeth(row: ByteArray, previous: ByteArray, out: ByteArray) {
        require(row.size == out.size && row.size == previous.size) { "Filter buffers must match" }
        for (index in row.indices) {
            val value = row[index].toInt() and 0xFF
            val left = if (index >= BYTES_PER_PIXEL) row[index - BYTES_PER_PIXEL].toInt() and 0xFF else 0
            val up = previous[index].toInt() and 0xFF
            val upLeft = if (index >= BYTES_PER_PIXEL) {
                previous[index - BYTES_PER_PIXEL].toInt() and 0xFF
            } else {
                0
            }
            out[index] = (value - paethPredictor(left, up, upLeft)).toByte()
        }
    }

    /** Sum of the absolute signed values, the standard heuristic for picking a PNG filter. */
    fun cost(filtered: ByteArray): Int {
        var sum = 0
        for (value in filtered) {
            val signed = value.toInt()
            sum += if (signed < 0) -signed else signed
        }
        return sum
    }

    private fun paethPredictor(left: Int, up: Int, upLeft: Int): Int {
        val estimate = left + up - upLeft
        val distanceLeft = kotlin.math.abs(estimate - left)
        val distanceUp = kotlin.math.abs(estimate - up)
        val distanceUpLeft = kotlin.math.abs(estimate - upLeft)
        return when {
            distanceLeft <= distanceUp && distanceLeft <= distanceUpLeft -> left
            distanceUp <= distanceUpLeft -> up
            else -> upLeft
        }
    }

    private const val BYTES_PER_PIXEL = 4
}
