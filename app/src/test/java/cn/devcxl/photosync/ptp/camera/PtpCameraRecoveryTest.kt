package cn.devcxl.photosync.ptp.camera

import java.io.ByteArrayOutputStream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.ptp.protocol.PtpContainer
import cn.devcxl.photosync.ptp.protocol.PtpContainerHeader
import cn.devcxl.photosync.ptp.protocol.PtpOp
import cn.devcxl.photosync.ptp.protocol.PtpProtocolException
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.transport.PtpBody
import cn.devcxl.photosync.ptp.transport.PtpTransport

/**
 * Seam under test: [PtpCamera] reconnect and teardown recovery when a previous USB transfer left
 * stale bytes in the Bulk-IN pipe (such as `got type 25088` on `GetDeviceInfo 0x1001`).
 */
class PtpCameraRecoveryTest {

    @Test
    fun shouldRecoverFromStaleBulkInContainer_whenFirstGetDeviceInfoGetsCorruptType() = runBlocking {
        val transport = RecoveryFakeTransport(
            staleHeadersBeforeCancel = mutableListOf(
                PtpContainerHeader(length = 128, type = 25088, code = 0, transactionId = 0),
            ),
            deviceInfoPayload = minimalDeviceInfoPayload("Sony", "ILCE-7M4"),
        )

        val camera = PtpCamera.connectWithTransport(
            usbDevice = null,
            transport = transport,
            ioDispatcher = Dispatchers.Unconfined,
            settleDelaysMs = longArrayOf(0L, 0L, 0L),
        )

        assertNotNull(camera.info)
        assertEquals("Sony", camera.info?.manufacturer)
        assertEquals("ILCE-7M4", camera.info?.model)
        assertEquals(1, transport.resetCalls)
        assertEquals(1, transport.cancelAndResetCalls)
        assertTrue(transport.drainCalls >= 2)
        assertFalse(transport.closed)
    }

    @Test
    fun shouldSkipCloseSessionAndCancelReset_whenClosingAfterProtocolFailure() = runBlocking {
        val transport = RecoveryFakeTransport(
            staleHeadersBeforeCancel = mutableListOf(),
            deviceInfoPayload = minimalDeviceInfoPayload("Canon", "EOS R5"),
        )
        val camera = PtpCamera.connectWithTransport(
            usbDevice = null,
            transport = transport,
            ioDispatcher = Dispatchers.Unconfined,
            settleDelaysMs = longArrayOf(0L),
        )
        camera.openSession()

        // Inject a mid-session framing corruption so objectInfo() breaks the link.
        transport.nextReadHeaderOverride = PtpContainerHeader(length = 64, type = 25088, code = 0, transactionId = 99)
        assertThrows(PtpProtocolException::class.java) {
            runBlocking { camera.objectInfo(0x1001) }
        }

        val closeCommandsBefore = transport.writtenOpcodes.count { it == PtpOp.CLOSE_SESSION }
        camera.close()

        assertEquals(
            "Broken link must not send CloseSession onto an out-of-sync pipe",
            closeCommandsBefore,
            transport.writtenOpcodes.count { it == PtpOp.CLOSE_SESSION },
        )
        assertEquals(1, transport.cancelAndResetCalls)
        assertTrue(transport.closed)
    }

    private fun minimalDeviceInfoPayload(manufacturer: String, model: String): ByteArray {
        val out = ByteArrayOutputStream(128)
        fun u16(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
        fun u32(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }
        fun ptpString(value: String) {
            if (value.isEmpty()) {
                out.write(0)
                return
            }
            out.write(value.length + 1)
            for (ch in value) u16(ch.code)
            u16(0)
        }
        u16(100) // standardVersion
        u32(0) // vendorExtensionId
        u16(100) // vendorExtensionVersion
        ptpString("") // vendorExtensionDesc
        u16(0) // functionalMode
        u32(2) // operationsSupported
        u16(PtpOp.GET_DEVICE_INFO)
        u16(PtpOp.CLOSE_SESSION)
        u32(0) // eventsSupported
        u32(0) // devicePropertiesSupported
        u32(0) // captureFormats
        u32(0) // imageFormats
        ptpString(manufacturer)
        ptpString(model)
        ptpString("1.0")
        ptpString("SN001")
        return out.toByteArray()
    }

    private class RecoveryFakeTransport(
        private val staleHeadersBeforeCancel: MutableList<PtpContainerHeader>,
        private val deviceInfoPayload: ByteArray,
    ) : PtpTransport {

        var resetCalls: Int = 0
        var cancelAndResetCalls: Int = 0
        var drainCalls: Int = 0
        var closed: Boolean = false
        var nextReadHeaderOverride: PtpContainerHeader? = null
        val writtenOpcodes = mutableListOf<Int>()

        private val pendingHeaders = ArrayDeque<PtpContainerHeader>()
        private val pendingBodies = ArrayDeque<ByteArray>()

        override fun writeContainer(bytes: ByteArray) {
            val header = PtpContainerHeader.parse(bytes)
            writtenOpcodes += header.code
            if (staleHeadersBeforeCancel.isNotEmpty()) {
                pendingHeaders.addLast(staleHeadersBeforeCancel.removeAt(0))
                return
            }
            when (header.code) {
                PtpOp.GET_DEVICE_INFO -> {
                    pendingHeaders.addLast(
                        PtpContainerHeader(
                            length = PtpContainer.HEADER_BYTES + deviceInfoPayload.size,
                            type = PtpContainer.TYPE_DATA,
                            code = PtpOp.GET_DEVICE_INFO,
                            transactionId = header.transactionId,
                        )
                    )
                    pendingBodies.addLast(deviceInfoPayload)
                    pendingHeaders.addLast(
                        PtpContainerHeader(
                            length = PtpContainer.HEADER_BYTES,
                            type = PtpContainer.TYPE_RESPONSE,
                            code = PtpStatus.OK,
                            transactionId = header.transactionId,
                        )
                    )
                    pendingBodies.addLast(ByteArray(0))
                }
                PtpOp.OPEN_SESSION, PtpOp.CLOSE_SESSION -> {
                    pendingHeaders.addLast(
                        PtpContainerHeader(
                            length = PtpContainer.HEADER_BYTES,
                            type = PtpContainer.TYPE_RESPONSE,
                            code = PtpStatus.OK,
                            transactionId = header.transactionId,
                        )
                    )
                    pendingBodies.addLast(ByteArray(0))
                }
            }
        }

        override fun readHeader(): PtpContainerHeader {
            nextReadHeaderOverride?.let { override ->
                nextReadHeaderOverride = null
                return override
            }
            return pendingHeaders.removeFirst()
        }

        override fun readBody(length: Int): ByteArray =
            if (length == 0) {
                if (pendingBodies.isNotEmpty() && pendingBodies.first().isEmpty()) {
                    pendingBodies.removeFirst()
                }
                ByteArray(0)
            } else {
                pendingBodies.removeFirst()
            }

        override fun openBody(length: Int): PtpBody = object : PtpBody {
            override val remaining: Int = 0
            override fun read(dest: ByteArray, offset: Int, count: Int): Int = 0
        }

        override fun drainPendingInput(): Int {
            drainCalls++
            pendingHeaders.clear()
            pendingBodies.clear()
            return 0
        }

        override fun resetDevice() {
            resetCalls++
            pendingHeaders.clear()
            pendingBodies.clear()
        }

        override fun cancelAndResetDevice() {
            cancelAndResetCalls++
            staleHeadersBeforeCancel.clear()
            pendingHeaders.clear()
            pendingBodies.clear()
        }

        override fun close() {
            closed = true
        }
    }
}
