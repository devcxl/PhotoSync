package cn.devcxl.photosync.ptp.transport

import cn.devcxl.photosync.ptp.protocol.PtpContainer
import cn.devcxl.photosync.ptp.protocol.PtpOp
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Verifies bulk-IN packet framing against real USB host-controller constraints: every read
 * submitted to the pipe must be an exact multiple of `wMaxPacketSize` and at most 16 KiB,
 * otherwise real Android USB hardware rejects the transfer with `EOVERFLOW` or `EINVAL`.
 */
class PtpBulkInReaderTest {

    @Test
    fun shouldPreserveInlinePayload_whenHeaderAndBodyArriveInOnePacket() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val pipe = FakeBulkInPipe(
            maxPacketSize = 512,
            transfers = listOf(
                dataContainer(PtpOp.GET_STORAGE_IDS, tid = 1, payload = payload),
                responseContainer(PtpStatus.OK, tid = 1),
            ),
        )
        val reader = PtpBulkInReader(maxPacketSize = 512, readUsb = pipe::read)

        val dataHeader = reader.readHeader(timeoutMs = 5_000)
        assertEquals(PtpContainer.TYPE_DATA, dataHeader.type)
        assertEquals(PtpContainer.HEADER_BYTES + payload.size, dataHeader.length)

        val body = ByteArray(payload.size)
        assertEquals(payload.size, reader.readBodyBytes(body, 0, body.size, timeoutMs = 15_000))
        assertArrayEquals(payload, body)

        val responseHeader = reader.readHeader(timeoutMs = 5_000)
        assertEquals(PtpContainer.TYPE_RESPONSE, responseHeader.type)
        assertEquals(PtpStatus.OK, responseHeader.code)
        assertTrue(pipe.allReadsWerePacketAligned)
    }

    @Test
    fun shouldConsumeZeroLengthTerminator_whenContainerMatchesPacketSize() {
        val payload = ByteArray(512 - PtpContainer.HEADER_BYTES) { (it and 0xff).toByte() }
        val pipe = FakeBulkInPipe(
            maxPacketSize = 512,
            transfers = listOf(
                dataContainer(PtpOp.GET_DEVICE_INFO, tid = 0, payload = payload),
                ByteArray(0), // ZLP terminating the exact 512-byte USB transfer
                responseContainer(PtpStatus.OK, tid = 0),
            ),
        )
        val reader = PtpBulkInReader(maxPacketSize = 512, readUsb = pipe::read)

        val dataHeader = reader.readHeader(5_000)
        val body = ByteArray(payload.size)
        var filled = 0
        while (filled < body.size) {
            filled += reader.readBodyBytes(body, filled, body.size - filled, 15_000)
        }
        assertArrayEquals(payload, body)

        val responseHeader = reader.readHeader(5_000)
        assertEquals(PtpStatus.OK, responseHeader.code)
        assertTrue(pipe.allReadsWerePacketAligned)
    }

    @Test
    fun shouldKeepReadsAligned_acrossMultiPacketStreamAndSmallCallerBuffers() {
        val payload = ByteArray(20_000) { ((it * 31) and 0xff).toByte() }
        val pipe = FakeBulkInPipe(
            maxPacketSize = 512,
            transfers = listOf(
                dataContainer(PtpOp.GET_OBJECT, tid = 7, payload = payload),
                responseContainer(PtpStatus.OK, tid = 7),
            ),
        )
        val reader = PtpBulkInReader(maxPacketSize = 512, readUsb = pipe::read)

        val header = reader.readHeader(5_000)
        val total = header.length - PtpContainer.HEADER_BYTES
        assertEquals(payload.size, total)

        val out = ByteArrayOutputStream()
        // Mix tiny reads (< maxPacketSize) and large reads (> 16 KiB) to exercise both paths.
        val readSizes = intArrayOf(100, 65_536, 73, 16_384)
        var step = 0
        while (out.size() < total) {
            val request = minOf(readSizes[step++ % readSizes.size], total - out.size())
            val chunk = ByteArray(request)
            val got = reader.readBodyBytes(chunk, 0, request, 15_000)
            assertTrue(got > 0)
            out.write(chunk, 0, got)
        }
        assertArrayEquals(payload, out.toByteArray())

        val responseHeader = reader.readHeader(5_000)
        assertEquals(PtpStatus.OK, responseHeader.code)
        assertTrue(pipe.allReadsWerePacketAligned)
    }

    @Test
    fun shouldDropBufferedTail_whenCleared() {
        val pipe = FakeBulkInPipe(
            maxPacketSize = 512,
            transfers = listOf(
                dataContainer(PtpOp.GET_STORAGE_IDS, tid = 1, payload = byteArrayOf(1, 2, 3, 4)),
                responseContainer(PtpStatus.OK, tid = 2),
            ),
        )
        val reader = PtpBulkInReader(maxPacketSize = 512, readUsb = pipe::read)

        reader.readHeader(5_000)
        reader.clear()
        val next = reader.readHeader(5_000)
        assertEquals(PtpContainer.TYPE_RESPONSE, next.type)
        assertEquals(2, next.transactionId)
    }

    @Test
    fun shouldFailAfterTooManyZeroLengthPackets() {
        val pipe = FakeBulkInPipe(
            maxPacketSize = 512,
            transfers = listOf(ByteArray(0), ByteArray(0), ByteArray(0)),
        )
        val reader = PtpBulkInReader(maxPacketSize = 512, readUsb = pipe::read)

        assertThrows(PtpTransportException::class.java) {
            reader.readHeader(5_000)
        }
    }

    private fun dataContainer(opcode: Int, tid: Int, payload: ByteArray): ByteArray =
        PtpContainer.dataHeader(payload.size, opcode, tid) + payload

    private fun responseContainer(code: Int, tid: Int): ByteArray {
        val out = ByteArrayOutputStream(PtpContainer.HEADER_BYTES)
        out.write(u32(PtpContainer.HEADER_BYTES))
        out.write(u16(PtpContainer.TYPE_RESPONSE))
        out.write(u16(code))
        out.write(u32(tid))
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun u32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte(),
    )

    /**
     * Simulates USB bulk-IN semantics: each queued byte array is one USB transfer that ends
     * on a short packet or explicit ZLP, and any read not aligned to `maxPacketSize` or larger
     * than 16 KiB fails like Android `usbdevfs`.
     */
    private class FakeBulkInPipe(
        private val maxPacketSize: Int,
        transfers: List<ByteArray>,
    ) {
        private val queue = transfers.toMutableList()
        private var offset = 0
        var allReadsWerePacketAligned: Boolean = true
            private set

        fun read(dest: ByteArray, destOffset: Int, length: Int, @Suppress("UNUSED_PARAMETER") timeoutMs: Int): Int {
            if (length <= 0 || length % maxPacketSize != 0 || length > 16 * 1024) {
                allReadsWerePacketAligned = false
                return -1
            }
            val current = queue.firstOrNull() ?: return -1
            if (current.isEmpty()) {
                queue.removeAt(0)
                offset = 0
                return 0
            }
            val available = current.size - offset
            val toCopy = minOf(available, length)
            System.arraycopy(current, offset, dest, destOffset, toCopy)
            offset += toCopy
            if (offset == current.size) {
                queue.removeAt(0)
                offset = 0
            }
            return toCopy
        }
    }
}
