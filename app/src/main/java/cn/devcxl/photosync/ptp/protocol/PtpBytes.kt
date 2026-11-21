package cn.devcxl.photosync.ptp.protocol

import java.io.ByteArrayOutputStream

/**
 * Sequential reader for PTP datasets.
 *
 * Every field in a PTP dataset is little-endian and variable-length sections are prefixed
 * with their element count, so datasets are read front to back and never seeked. Reads past
 * the end raise [PtpProtocolException] with the offset, because a truncated dataset from a
 * camera is a protocol error rather than an empty value.
 */
internal class PtpByteReader(
    private val bytes: ByteArray,
    start: Int = 0,
    private val end: Int = bytes.size,
) {
    var position: Int = start
        private set

    val remaining: Int get() = end - position

    fun u8(): Int {
        requireAvailable(1)
        return bytes[position++].toInt() and 0xff
    }

    fun u16(): Int {
        requireAvailable(2)
        val value = (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8)
        position += 2
        return value
    }

    fun u32(): Int {
        requireAvailable(4)
        val value = (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8) or
            ((bytes[position + 2].toInt() and 0xff) shl 16) or
            ((bytes[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return value
    }

    /** Unsigned 32-bit value, widened so a camera can report sizes above 2 GiB. */
    fun u32AsLong(): Long = u32().toLong() and 0xffff_ffffL

    fun u64(): Long = (u32AsLong()) or (u32AsLong() shl 32)

    fun skip(count: Int) {
        requireAvailable(count)
        position += count
    }

    /** A `UINT32` count followed by that many 32-bit values, i.e. a `*Array` dataset field. */
    fun u32Array(): IntArray {
        val count = u32()
        if (count < 0 || count > remaining / 4) {
            throw PtpProtocolException("Array count $count exceeds ${remaining / 4} available elements at $position")
        }
        return IntArray(count) { u32() }
    }

    /** A `UINT16` array, used for the operation/event/property tables of the device info. */
    fun u16Array(): IntArray {
        val count = u32()
        if (count < 0 || count > remaining / 2) {
            throw PtpProtocolException("Array count $count exceeds ${remaining / 2} available elements at $position")
        }
        return IntArray(count) { u16() }
    }

    /**
     * A PTP string: one octet holding the character count including the terminating NUL,
     * followed by that many UTF-16LE code units.
     *
     * An empty count means "no value", which is how cameras signal an absent optional field
     * such as a serial number.
     */
    fun string(): String? {
        val characters = u8()
        if (characters == 0) {
            return null
        }
        val byteCount = characters * 2
        requireAvailable(byteCount)
        val text = String(bytes, position, byteCount, Charsets.UTF_16LE)
        position += byteCount
        return text.trimEnd('\u0000')
    }

    fun rest(): ByteArray {
        val copy = bytes.copyOfRange(position, end)
        position = end
        return copy
    }

    private fun requireAvailable(count: Int) {
        if (count < 0 || position + count > end) {
            throw PtpProtocolException(
                "Need $count bytes at offset $position, dataset holds ${end - position}"
            )
        }
    }
}

/**
 * Builder for PTP containers and data payloads.
 *
 * Kept separate from the reader because writing is append-only and never fails: the only
 * variable part of a container is its parameter list, which the caller builds from known
 * values.
 */
internal class PtpByteWriter(initialCapacity: Int = 64) {
    private val out = ByteArrayOutputStream(initialCapacity)

    fun u8(value: Int): PtpByteWriter {
        out.write(value and 0xff)
        return this
    }

    fun u16(value: Int): PtpByteWriter {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
        return this
    }

    fun u32(value: Int): PtpByteWriter {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
        out.write((value shr 16) and 0xff)
        out.write((value shr 24) and 0xff)
        return this
    }

    fun bytes(value: ByteArray): PtpByteWriter {
        out.write(value)
        return this
    }

    fun build(): ByteArray = out.toByteArray()

    val size: Int get() = out.size()
}
