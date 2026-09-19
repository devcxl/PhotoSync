package cn.devcxl.photosync.ptp.usbcamera.nikon

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [NikonEventParser] against the wire layout documented by libgphoto2's
 * `ptp_unpack_Nikon_EC`: a little-endian uint16 event count followed by fixed-size
 * 6 byte records of `{uint16 code, uint32 param1}`.
 */
class NikonEventParserTest {

    private fun payload(eventCount: Int, vararg events: Pair<Int, Int>): ByteArray {
        val buffer = ByteBuffer.allocate(2 + events.size * 6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(eventCount.toShort())
        for ((code, param) in events) {
            buffer.putShort(code.toShort())
            buffer.putInt(param)
        }
        return buffer.array()
    }

    @Test
    fun shouldReadCodeAndParam_whenSingleObjectAddedEvent() {
        val parser = NikonEventParser(
            payload(1, NikonEventConstants.NK_EC_ObjectAddedInSDRAM to 0xffff0001.toInt())
        )

        assertTrue(parser.hasEvents())
        val event = parser.getNextEvent()

        assertEquals(NikonEventConstants.NK_EC_ObjectAddedInSDRAM, event.code)
        assertEquals(0xffff0001.toInt(), event.getIntParam(1))
        assertFalse(parser.hasEvents())
    }

    @Test
    fun shouldReadEveryEvent_whenPayloadHoldsSeveral() {
        val parser = NikonEventParser(
            payload(
                3,
                NikonEventConstants.NK_EC_ObjectAddedInSDRAM to 0x0000000A,
                NikonEventConstants.NK_EC_CaptureOverflow to 0x00000000,
                NikonEventConstants.NK_EC_AdvancedTransfer to 0x0000FFEE
            )
        )

        val events = mutableListOf<NikonEvent>()
        while (parser.hasEvents()) {
            events.add(parser.getNextEvent())
        }

        assertEquals(3, events.size)
        assertEquals(
            listOf(
                NikonEventConstants.NK_EC_ObjectAddedInSDRAM,
                NikonEventConstants.NK_EC_CaptureOverflow,
                NikonEventConstants.NK_EC_AdvancedTransfer
            ),
            events.map { it.code }
        )
        assertEquals(0x0000000A, events[0].getIntParam(1))
        assertEquals(0x0000FFEE, events[2].getIntParam(1))
    }

    @Test
    fun shouldReportNoEvents_whenCountIsZero() {
        val parser = NikonEventParser(payload(0))

        assertFalse(parser.hasEvents())
    }

    @Test
    fun shouldReportNoEvents_whenPayloadIsShorterThanCountField() {
        val parser = NikonEventParser(byteArrayOf(0x01))

        assertFalse(parser.hasEvents())
    }

    /**
     * A payload truncated mid-record must yield the complete events and stop, rather than
     * reading past the end or throwing.
     */
    @Test
    fun shouldYieldCompleteEventsOnly_whenTrailingRecordIsTruncated() {
        val complete = payload(
            2,
            NikonEventConstants.NK_EC_ObjectAddedInSDRAM to 0x11,
            NikonEventConstants.NK_EC_AdvancedTransfer to 0x22
        )
        val truncated = complete.copyOf(complete.size - 3)

        val parser = NikonEventParser(truncated)

        assertTrue(parser.hasEvents())
        assertEquals(NikonEventConstants.NK_EC_ObjectAddedInSDRAM, parser.getNextEvent().code)
        assertFalse(parser.hasEvents())
    }

    @Test
    fun shouldNotTreatHeaderPaddingAsSecondEvent_whenCountIsOne() {
        // Some payloads pad the data phase; padding after the declared events must be ignored.
        val padded = payload(1, NikonEventConstants.NK_EC_ObjectAddedInSDRAM to 0x22) + ByteArray(20)
        val parser = NikonEventParser(padded)

        assertTrue(parser.hasEvents())
        assertEquals(NikonEventConstants.NK_EC_ObjectAddedInSDRAM, parser.getNextEvent().code)
        assertFalse(parser.hasEvents())
    }
}
