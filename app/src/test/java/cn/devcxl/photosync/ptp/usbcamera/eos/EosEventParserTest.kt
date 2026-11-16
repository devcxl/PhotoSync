package cn.devcxl.photosync.ptp.usbcamera.eos

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EosEventParserTest {

    @Test
    fun shouldReadTheObjectHandle_whenTheEventIsObjectAddedEx64() {
        val handle = 0x6E3A1C0F
        val parser = EosEventParser(ByteArrayInputStream(objectAddedEx64(handle, "IMG_0550.JPG")))

        val event = parser.getNextEvent()

        assertEquals(EosEventConstants.EosEventObjectAddedEx64, event.code)
        assertEquals(handle, event.getIntParam(1))
    }

    @Test
    fun shouldLeaveTheStreamAligned_whenTheObjectAddedEx64NameIsShorterThanTheRecord() {
        // A name that pads to a different width than the capture that first exposed this:
        // the parser must consume the record's tail instead of assuming four bytes.
        val parser = EosEventParser(ByteArrayInputStream(objectAddedEx64(7, "A.JPG")))

        parser.getNextEvent()

        assertFalse(parser.hasEvents())
    }

    @Test
    fun shouldStillReadTheObjectHandle_whenTheEventIsTheOlderObjectAddedEx() {
        val handle = 0x1234
        val parser = EosEventParser(ByteArrayInputStream(objectAddedEx(handle, "IMG_0001.JPG")))

        val event = parser.getNextEvent()

        assertEquals(EosEventConstants.EosEventObjectAddedEx, event.code)
        assertEquals(handle, event.getIntParam(1))
    }

    // ---------------------------------------------------------------- record builders

    /** Builds one `ObjectAddedEx64` record as the camera framed it on the wire. */
    private fun objectAddedEx64(handle: Int, name: String): ByteArray {
        val payload = RecordWriter()
        payload.u32(handle)          // 0x08 object handle
        payload.u32(0x00010001)      // 0x0c storage id
        payload.u16(0x3801)          // 0x10 object format (JPEG)
        payload.zeros(10)            // 0x12..0x1b
        payload.u32(4_000_000)       // 0x1c object size
        payload.zeros(4)             // 0x20..0x23
        payload.u32(0x4000)          // 0x24 parent handle
        payload.u32(0)               // 0x28 second object id
        payload.paddedString(name)   // 0x2c file name
        payload.zeros(PADDING_TAIL)
        return payload.record(EosEventConstants.EosEventObjectAddedEx64)
    }

    /** Builds one `ObjectAddedEx` record (the older, 32-bit variant). */
    private fun objectAddedEx(handle: Int, name: String): ByteArray {
        val payload = RecordWriter()
        payload.u32(handle)          // 0x08 object handle
        payload.u32(0x00010001)      // 0x0c storage id
        payload.u16(0x3801)          // 0x10 object format
        payload.zeros(10)            // 0x12..0x1b
        payload.u32(4_000_000)       // 0x1c object size
        payload.u32(0x4000)          // 0x20 parent handle
        payload.zeros(4)             // 0x24 unknown
        payload.paddedString(name)   // 0x28 file name
        payload.zeros(PADDING_TAIL)
        return payload.record(EosEventConstants.EosEventObjectAddedEx)
    }

    private class RecordWriter {
        private val bytes = ByteArrayOutputStream()

        fun u16(value: Int) {
            bytes.write(value and 0xff)
            bytes.write((value shr 8) and 0xff)
        }

        fun u32(value: Int) {
            u16(value)
            u16(value shr 16)
        }

        fun zeros(count: Int) = bytes.write(ByteArray(count))

        fun paddedString(value: String) {
            bytes.write(value.toByteArray(Charsets.US_ASCII))
            bytes.write(0)
            val padding = (4 - (bytes.size() % 4)) % 4
            bytes.write(ByteArray(padding))
        }

        fun record(code: Int): ByteArray {
            val body = bytes.toByteArray()
            val out = ByteArrayOutputStream()
            val length = body.size + RECORD_HEADER_BYTES
            out.write(byteArrayOf(
                (length and 0xff).toByte(),
                ((length shr 8) and 0xff).toByte(),
                ((length shr 16) and 0xff).toByte(),
                ((length shr 24) and 0xff).toByte()
            ))
            out.write(byteArrayOf(
                (code and 0xff).toByte(),
                ((code shr 8) and 0xff).toByte(),
                ((code shr 16) and 0xff).toByte(),
                ((code shr 24) and 0xff).toByte()
            ))
            out.write(body)
            return out.toByteArray()
        }
    }

    private companion object {
        const val RECORD_HEADER_BYTES = 8
        const val PADDING_TAIL = 4
    }
}
