package cn.devcxl.photosync.editor.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Independent PNG oracle for the streaming sink tests: parses every chunk (validating CRCs) and
 * reverses the Sub/Paeth row filters so tests can compare raw `RGBA8` bytes without ImageIO, which
 * is unavailable on the Android unit-test compile classpath.
 */
internal object PngTestDecoder {

    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    data class Chunk(val type: String, val data: ByteArray)

    data class DecodedPng(val width: Int, val height: Int, val rgba: ByteArray)

    fun chunks(bytes: ByteArray): List<Chunk> {
        assertTrue("PNG signature is missing", bytes.size > SIGNATURE.size)
        assertArrayEquals(SIGNATURE, bytes.copyOfRange(0, SIGNATURE.size))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val chunks = mutableListOf<Chunk>()
        var position = SIGNATURE.size
        while (position < bytes.size) {
            val length = buffer.getInt(position)
            require(length >= 0) { "Corrupt chunk length at $position" }
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            val dataStart = position + 8
            val storedCrc = buffer.getInt(dataStart + length)
            val crc = CRC32().apply { update(bytes, position + 4, 4 + length) }
            assertEquals("CRC mismatch for $type", crc.value.toInt(), storedCrc)
            chunks += Chunk(type, bytes.copyOfRange(dataStart, dataStart + length))
            position = dataStart + length + 4
        }
        return chunks
    }

    fun decode(bytes: ByteArray): DecodedPng {
        val chunks = chunks(bytes)
        val ihdr = chunks.first { it.type == "IHDR" }.data
        val width = ByteBuffer.wrap(ihdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val height = ByteBuffer.wrap(ihdr, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val compressed = ByteArrayOutputStream()
        chunks.filter { it.type == "IDAT" }.forEach { compressed.write(it.data) }

        val rowSize = width * 4
        val expectedSize = height * (rowSize + 1)
        val filtered = ByteArray(expectedSize)
        val inflater = Inflater()
        var filled = 0
        try {
            inflater.setInput(compressed.toByteArray())
            while (filled < expectedSize) {
                val read = inflater.inflate(filtered, filled, expectedSize - filled)
                if (read == 0) break
                filled += read
            }
        } finally {
            inflater.end()
        }
        assertEquals("Inflated PNG payload size", expectedSize, filled)

        val rgba = ByteArray(width * height * 4)
        val previous = ByteArray(rowSize)
        val current = ByteArray(rowSize)
        var position = 0
        for (row in 0 until height) {
            val filter = filtered[position++].toInt() and 0xFF
            filtered.copyInto(
                current,
                destinationOffset = 0,
                startIndex = position,
                endIndex = position + rowSize
            )
            position += rowSize
            unfilterRow(filter, current, previous)
            current.copyInto(rgba, destinationOffset = row * rowSize)
            current.copyInto(previous)
        }
        return DecodedPng(width, height, rgba)
    }

    private fun unfilterRow(filter: Int, row: ByteArray, previous: ByteArray) {
        when (filter) {
            1 -> for (index in row.indices) {
                val left = if (index >= BYTES_PER_PIXEL) row[index - BYTES_PER_PIXEL].toInt() and 0xFF else 0
                row[index] = ((row[index].toInt() and 0xFF) + left).toByte()
            }
            4 -> for (index in row.indices) {
                val left = if (index >= BYTES_PER_PIXEL) row[index - BYTES_PER_PIXEL].toInt() and 0xFF else 0
                val up = previous[index].toInt() and 0xFF
                val upLeft = if (index >= BYTES_PER_PIXEL) {
                    previous[index - BYTES_PER_PIXEL].toInt() and 0xFF
                } else {
                    0
                }
                row[index] = ((row[index].toInt() and 0xFF) + paeth(left, up, upLeft)).toByte()
            }
            else -> throw AssertionError("Unexpected PNG filter type $filter")
        }
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

    private const val BYTES_PER_PIXEL = 4
}
