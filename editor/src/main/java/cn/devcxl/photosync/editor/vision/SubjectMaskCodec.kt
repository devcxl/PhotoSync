package cn.devcxl.photosync.editor.vision

import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Lossless 8-bit grayscale PNG codec for [SubjectMask] payloads.
 *
 * The `SubjectMask` is stored as a plain grayscale PNG so it stays inspectable with any image tool
 * and the content-addressed store can keep the `.png` extension of
 * [cn.devcxl.photosync.editor.domain.AiAssetKind.SUBJECT_MASK].
 * Only the subset written by [encode] is supported: 8-bit grayscale, no interlacing, filter type 0.
 * Decoding accepts the full PNG filter set so an externally produced mask can still be imported.
 */
internal object SubjectMaskCodec {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private const val CHUNK_IHDR = "IHDR"
    private const val CHUNK_IDAT = "IDAT"
    private const val CHUNK_IEND = "IEND"
    private const val BIT_DEPTH_8 = 8
    private const val COLOR_TYPE_GRAY = 0
    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4

    /**
     * Encodes [mask] as an 8-bit grayscale PNG.
     *
     * @return The encoded file, or `null` when the mask is invalid or the deflater fails.
     */
    fun encode(mask: SubjectMask): ByteArray? {
        if (mask.width <= 0 || mask.height <= 0 || mask.alpha.size != mask.width * mask.height) {
            return null
        }
        val rowBytes = mask.width + 1
        val raw = ByteArray(rowBytes * mask.height)
        for (row in 0 until mask.height) {
            raw[row * rowBytes] = FILTER_NONE.toByte()
            val source = row * mask.width
            val target = row * rowBytes + 1
            for (column in 0 until mask.width) {
                val value = (mask.alpha[source + column].coerceIn(0.0f, 1.0f) * 255.0f + 0.5f)
                raw[target + column] = value.toInt().toByte()
            }
        }
        val compressed = deflate(raw) ?: return null
        return buildPng(mask.width, mask.height, compressed)
    }

    /**
     * Decodes a grayscale PNG written by [encode].
     *
     * @return The mask, or `null` when the chunk layout, dimensions or pixel format is unsupported.
     */
    fun decode(payload: ByteArray): SubjectMask? {
        val chunks = readChunks(payload) ?: return null
        val header = chunks.firstOrNull { it.type == CHUNK_IHDR }?.data ?: return null
        if (header.size < 13) return null
        val width = readInt(header, 0)
        val height = readInt(header, 4)
        val bitDepth = header[8].toInt() and 0xFF
        val colorType = header[9].toInt() and 0xFF
        val interlace = header[12].toInt() and 0xFF
        if (width <= 0 || height <= 0) return null
        if (bitDepth != BIT_DEPTH_8 || colorType != COLOR_TYPE_GRAY || interlace != 0) return null
        val compressed = concatIdat(chunks) ?: return null
        val expected = width * height
        val decoded = inflate(compressed, expected + height) ?: return null
        return unfilter(decoded, width, height)?.let { SubjectMask(width, height, it) }
    }

    /**
     * Extracts the SHA-256 digest embedded in a content-addressed mask path.
     *
     * @return The 64-character digest, or `null` when [maskRef] is not a `subject_mask_<sha>.png`.
     */
    fun digestFromRef(maskRef: String): String? {
        val name = maskRef.substringAfterLast('/', "")
        return MASK_NAME_REGEX.matchEntire(name)?.groupValues?.get(1)
    }

    private fun buildPng(width: Int, height: Int, compressed: ByteArray): ByteArray {
        val header = ByteArray(13)
        writeInt(header, 0, width)
        writeInt(header, 4, height)
        header[8] = BIT_DEPTH_8.toByte()
        header[9] = COLOR_TYPE_GRAY.toByte()
        header[10] = 0
        header[11] = 0
        header[12] = 0
        val output = java.io.ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        writeChunk(output, CHUNK_IHDR, header)
        writeChunk(output, CHUNK_IDAT, compressed)
        writeChunk(output, CHUNK_IEND, ByteArray(0))
        return output.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray? {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val produced = deflater.deflate(buffer)
                if (produced <= 0) return null
                out.write(buffer, 0, produced)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray? {
        val inflater = Inflater(false)
        return try {
            inflater.setInput(compressed)
            val out = ByteArray(expectedSize)
            var written = 0
            while (!inflater.finished() && written < expectedSize) {
                val produced = inflater.inflate(out, written, expectedSize - written)
                if (produced <= 0) break
                written += produced
            }
            if (written != expectedSize) null else out
        } catch (error: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun unfilter(decoded: ByteArray, width: Int, height: Int): FloatArray? {
        val rowBytes = width
        if (decoded.size < (rowBytes + 1) * height) return null
        val alpha = FloatArray(width * height)
        var previous = ByteArray(rowBytes)
        for (row in 0 until height) {
            val filter = decoded[row * (rowBytes + 1)].toInt() and 0xFF
            val sourceOffset = row * (rowBytes + 1) + 1
            val current = ByteArray(rowBytes)
            for (column in 0 until rowBytes) {
                val raw = decoded[sourceOffset + column].toInt() and 0xFF
                val left = if (column >= 1) current[column - 1].toInt() and 0xFF else 0
                val up = previous[column].toInt() and 0xFF
                val upLeft = if (column >= 1) previous[column - 1].toInt() and 0xFF else 0
                current[column] = when (filter) {
                    FILTER_NONE -> raw
                    FILTER_SUB -> raw + left
                    FILTER_UP -> raw + up
                    FILTER_AVERAGE -> raw + (left + up) / 2
                    FILTER_PAETH -> raw + paeth(left, up, upLeft)
                    else -> return null
                }.toByte()
            }
            for (column in 0 until width) {
                alpha[row * width + column] = (current[column].toInt() and 0xFF) / 255.0f
            }
            previous = current
        }
        return alpha
    }

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
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

    private data class Chunk(val type: String, val data: ByteArray)

    private fun readChunks(payload: ByteArray): List<Chunk>? {
        if (payload.size < PNG_SIGNATURE.size + 12) return null
        for (index in PNG_SIGNATURE.indices) {
            if (payload[index] != PNG_SIGNATURE[index]) return null
        }
        val chunks = mutableListOf<Chunk>()
        var offset = PNG_SIGNATURE.size
        while (offset + 12 <= payload.size) {
            val length = readInt(payload, offset)
            if (length < 0 || offset + 12 + length > payload.size) return null
            val type = String(payload, offset + 4, 4, Charsets.US_ASCII)
            val data = payload.copyOfRange(offset + 8, offset + 8 + length)
            chunks.add(Chunk(type, data))
            offset += 12 + length
            if (type == CHUNK_IEND) break
        }
        return chunks
    }

    private fun concatIdat(chunks: List<Chunk>): ByteArray? {
        val idat = chunks.filter { it.type == CHUNK_IDAT }
        if (idat.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        idat.forEach { out.write(it.data) }
        return out.toByteArray()
    }

    private fun writeChunk(output: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(output, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(output, crc.value.toInt())
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeInt(output: java.io.ByteArrayOutputStream, value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    private val MASK_NAME_REGEX = Regex("subject_mask_([0-9a-fA-F]{64})\\.png")
}
