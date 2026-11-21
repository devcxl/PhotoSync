package cn.devcxl.photosync.ptp.camera

import cn.devcxl.photosync.ptp.camera.vendor.CanonEosEventReader
import cn.devcxl.photosync.ptp.camera.vendor.NikonEventReader
import cn.devcxl.photosync.ptp.protocol.PtpProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CanonEosEventReaderTest {

    @Test
    fun shouldReadHandle_whenRecordIsObjectAddedEx64() {
        val handle = 0x6E3A1C0F
        val events = CanonEosEventReader.read(objectAddedEx64(handle, "IMG_0550.JPG"))

        assertEquals(1, events.size)
        assertEquals(handle, events.single().newObjectHandle)
    }

    @Test
    fun shouldReadHandle_whenRecordIsTheOlderObjectAddedEx() {
        val events = CanonEosEventReader.read(objectAddedEx(0x1234, "IMG_0001.JPG"))

        assertEquals(0x1234, events.single().newObjectHandle)
    }

    @Test
    fun shouldStayAligned_whenNamesHaveDifferentPaddedLengths() {
        val bytes = objectAddedEx64(101, "A.JPG") + objectAddedEx64(102, "IMG_0551.JPG")

        val events = CanonEosEventReader.read(bytes)

        assertEquals(listOf(101, 102), events.map { it.newObjectHandle })
    }

    @Test
    fun shouldReturnNoEvents_whenBatchHoldsOnlyTheEmptySentinel() {
        val events = CanonEosEventReader.read(record(0x0000, ByteArray(0)))

        assertTrue(events.isEmpty())
    }

    @Test
    fun shouldSkipSentinel_whenItPrecedesARealEvent() {
        val bytes = record(0x0000, ByteArray(0)) + objectAddedEx64(0x42, "IMG_2.JPG")

        assertEquals(0x42, CanonEosEventReader.read(bytes).single().newObjectHandle)
    }

    @Test
    fun shouldStayAligned_whenAnUnknownVariableLengthRecordComesFirst() {
        val propertyChange = record(
            0xc189,
            byteArrayOf(0x20, 0xd1.toByte(), 0, 0, 2, 0, 0, 0, 0x10, 0, 0, 0, 0x20, 0, 0, 0),
        )
        val handle = 0x77112233

        val events = CanonEosEventReader.read(propertyChange + objectAddedEx64(handle, "IMG_0544.JPG"))

        assertEquals(2, events.size)
        assertEquals(handle, events[1].newObjectHandle)
        assertNull(events[0].newObjectHandle)
    }

    @Test
    fun shouldRequestReconcile_whenRecordIsUnreportedStatus() {
        val events = CanonEosEventReader.read(record(0x400e, ByteArray(0)))

        assertTrue(events.single().reconcileRequested)
        assertNull(events.single().newObjectHandle)
    }

    @Test
    fun shouldIgnoreTruncatedTrailingRecord_insteadOfFailingTheBatch() {
        val complete = objectAddedEx64(7, "A.JPG")
        val truncated = (complete + objectAddedEx64(8, "B.JPG")).copyOf(complete.size + 10)

        val events = CanonEosEventReader.read(truncated)

        assertEquals(listOf(7), events.map { it.newObjectHandle })
    }

    @Test
    fun shouldRejectRecord_whenDeclaredLengthIsImpossible() {
        val bytes = littleEndian(4) + littleEndian(0xc181)

        assertThrows(PtpProtocolException::class.java) { CanonEosEventReader.read(bytes) }
    }

    private fun objectAddedEx64(handle: Int, name: String): ByteArray =
        RecordWriter()
            .u32(handle)
            .u32(0x0001_0001)
            .u16(0x3801)
            .zeros(10)
            .u32(4_000_000)
            .zeros(4)
            .u32(0x4000)
            .u32(0)
            .ptpString(name)
            .zeros(4)
            .record(0xc1a7)

    private fun objectAddedEx(handle: Int, name: String): ByteArray =
        RecordWriter()
            .u32(handle)
            .u32(0x0001_0001)
            .u16(0x3801)
            .zeros(10)
            .u32(4_000_000)
            .u32(0x4000)
            .zeros(4)
            .ptpString(name)
            .zeros(4)
            .record(0xc181)

    private fun record(code: Int, payload: ByteArray): ByteArray =
        littleEndian(payload.size + 8) + littleEndian(code) + payload

    private fun littleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    private class RecordWriter {
        private val bytes = ByteArrayOutputStream()

        fun u16(value: Int): RecordWriter {
            bytes.write(value and 0xff)
            bytes.write((value shr 8) and 0xff)
            return this
        }

        fun u32(value: Int): RecordWriter {
            u16(value)
            u16(value shr 16)
            return this
        }

        fun zeros(count: Int): RecordWriter {
            bytes.write(ByteArray(count))
            return this
        }

        /** A C-style NUL-terminated name, then padding to a 4-byte boundary. */
        fun ptpString(value: String): RecordWriter {
            bytes.write(value.toByteArray(Charsets.US_ASCII))
            bytes.write(0)
            bytes.write(ByteArray((4 - bytes.size() % 4) % 4))
            return this
        }

        fun record(code: Int): ByteArray {
            val body = bytes.toByteArray()
            return encode(body.size + 8) + encode(code) + body
        }

        private fun encode(value: Int): ByteArray = byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        )
    }
}

class NikonEventReaderTest {

    @Test
    fun shouldReadCodeAndParameter() {
        val events = NikonEventReader.read(payload(1, 0xc101 to 0xffff0001.toInt()))

        assertEquals(1, events.size)
        assertEquals(0xffff0001.toInt(), events.single().newObjectHandle)
    }

    @Test
    fun shouldReplaceZeroHandle_whenCameraReportsTheSdramPlaceholder() {
        val events = NikonEventReader.read(payload(1, 0xc101 to 0))

        assertEquals(NikonEventReader.SDRAM_PLACEHOLDER_HANDLE, events.single().newObjectHandle)
    }

    @Test
    fun shouldKeepHandle_whenEventIsTheStandardObjectAdded() {
        val events = NikonEventReader.read(payload(1, 0x4002 to 0x2a))

        assertEquals(0x2a, events.single().newObjectHandle)
    }

    @Test
    fun shouldRequestReconcile_whenPayloadCarriesUnreportedStatus() {
        val events = NikonEventReader.read(payload(1, 0x400e to 0))

        assertTrue(events.single().reconcileRequested)
        assertNull(events.single().newObjectHandle)
    }

    @Test
    fun shouldNotTreatOtherEvents_asObjectAdded() {
        val events = NikonEventReader.read(payload(1, 0xc103 to 0x99))

        assertNull(events.single().newObjectHandle)
    }

    @Test
    fun shouldReadEveryEvent_whenPayloadHoldsSeveral() {
        val events = NikonEventReader.read(payload(3, 0xc101 to 1, 0xc102 to 0, 0xc103 to 0xff))

        assertEquals(listOf(0xc101, 0xc102, 0xc103), events.map { it.code })
    }

    @Test
    fun shouldReturnNoEvents_whenCountIsZero() {
        assertTrue(NikonEventReader.read(payload(0)).isEmpty())
    }

    @Test
    fun shouldReturnNoEvents_whenPayloadIsShorterThanTheCountField() {
        assertTrue(NikonEventReader.read(byteArrayOf(0x01)).isEmpty())
    }

    @Test
    fun shouldYieldCompleteEventsOnly_whenTrailingRecordIsTruncated() {
        val complete = payload(2, 0xc101 to 0x11, 0xc103 to 0x22)

        val events = NikonEventReader.read(complete.copyOf(complete.size - 3))

        assertEquals(listOf(0xc101), events.map { it.code })
    }

    @Test
    fun shouldIgnorePadding_whenCountIsOne() {
        val padded = payload(1, 0xc101 to 0x22) + ByteArray(20)

        assertEquals(1, NikonEventReader.read(padded).size)
    }

    private fun payload(count: Int, vararg events: Pair<Int, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(count and 0xff)
        out.write((count shr 8) and 0xff)
        for ((code, param) in events) {
            out.write(code and 0xff)
            out.write((code shr 8) and 0xff)
            out.write(param and 0xff)
            out.write((param shr 8) and 0xff)
            out.write((param shr 16) and 0xff)
            out.write((param shr 24) and 0xff)
        }
        return out.toByteArray()
    }
}
