package cn.devcxl.photosync.ptp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PtpByteReaderTest {

    @Test
    fun shouldReadLittleEndianValues_inDeclarationOrder() {
        val bytes = byteArrayOf(
            0x34, 0x12,                         // u16 0x1234
            0x78, 0x56, 0x34, 0x12,             // u32 0x12345678
            0xff.toByte(),                      // u8 0xff, read as unsigned
        )

        val reader = PtpByteReader(bytes)

        assertEquals(0x1234, reader.u16())
        assertEquals(0x12345678, reader.u32())
        assertEquals(255, reader.u8())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun shouldWidenUnsigned32_whenValueExceedsIntRange() {
        val reader = PtpByteReader(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))

        assertEquals(4_294_967_295L, reader.u32AsLong())
    }

    @Test
    fun shouldReadCountPrefixedArray_whenLengthsAreConsistent() {
        val bytes = byteArrayOf(
            0x02, 0x00, 0x00, 0x00,             // count = 2
            0x01, 0x00, 0x00, 0x00,             // 1
            0x02, 0x00, 0x00, 0x00,             // 2
        )

        assertArrayEquals(intArrayOf(1, 2), PtpByteReader(bytes).u32Array())
    }

    @Test
    fun shouldRejectArray_whenCountExceedsAvailableBytes() {
        // A camera reporting more elements than it sent must not make the reader allocate
        // or read past the dataset.
        val bytes = byteArrayOf(
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01, // count = 33 554 431
            0x01, 0x00, 0x00, 0x00,
        )

        assertThrows(PtpProtocolException::class.java) { PtpByteReader(bytes).u32Array() }
    }

    @Test
    fun shouldTrimTerminator_whenReadingString() {
        // PTP strings carry the character count including the terminating NUL.
        val bytes = byteArrayOf(0x04) + "IMG\u0000".toByteArray(Charsets.UTF_16LE)

        assertEquals("IMG", PtpByteReader(bytes).string())
    }

    @Test
    fun shouldReturnNull_whenStringCountIsZero() {
        assertNull(PtpByteReader(byteArrayOf(0x00)).string())
    }

    @Test
    fun shouldRejectRead_whenDatasetIsTruncated() {
        val reader = PtpByteReader(byteArrayOf(0x01, 0x02))

        assertThrows(PtpProtocolException::class.java) { reader.u32() }
    }

    @Test
    fun shouldReportOffset_whenReadFails() {
        val reader = PtpByteReader(byteArrayOf(0x01, 0x02, 0x03))
        reader.skip(2)

        val error = assertThrows(PtpProtocolException::class.java) { reader.u16() }

        assertEquals(true, error.message!!.contains("offset 2"))
    }
}

class PtpByteWriterTest {

    @Test
    fun shouldWriteLittleEndian_andReportSize() {
        val writer = PtpByteWriter()
        writer.u16(0x1234)
        writer.u32(0x7abcdef0)

        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xf0.toByte(), 0xde.toByte(), 0xbc.toByte(), 0x7a),
            writer.build(),
        )
        assertEquals(6, writer.size)
    }
}
