package cn.devcxl.photosync.ptp.camera

import cn.devcxl.photosync.ptp.camera.vendor.CanonEosProfile
import cn.devcxl.photosync.ptp.protocol.PtpContainer
import cn.devcxl.photosync.ptp.protocol.PtpDeviceException
import cn.devcxl.photosync.ptp.protocol.PtpOp
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import cn.devcxl.photosync.ptp.transport.FakeStillImageDevice
import cn.devcxl.photosync.ptp.transport.FakeStillImageDevice.ReplyPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Integration tests for the reconnect recovery decision in [PtpCamera.connectWithTransport].
 *
 * The fake device models USB Still Image Capture cancellation: a Cancel Request must carry
 * the actual PIMA TransactionID, and the device stays busy until Get_Device_Status (0x67)
 * reports OK. The cancel-based recovery test targets only the host's own GetDeviceInfo
 * transaction. For an unknown transaction left by a dead process, the tests require reset-only
 * recovery and forbid any guessed Cancel. The tests run the real production initialisation
 * loop, not a test copy of it.
 *
 * Field context: USB 04a9:3312, reported as a Canon R100. These tests run at the transport
 * seam with `usbDevice = null`, so USB enumeration is out of scope; the fake identifies
 * itself as "Canon EOS R100" and the connect path uses [CanonEosProfile].
 *
 * Model scope: the only transaction id the reconnecting host can name is its own
 * GetDeviceInfo, which is 0 while no PTP session is open. The killed process's transaction id
 * is unknown; the model does not claim it, and the stale-tail/reset behavior reproduces the
 * field report rather than proving how a real R100 behaves.
 */
class StillImageRecoveryProtocolTest {

    /**
     * The host's own first GetDeviceInfo (tid 0) times out, and recovery cancels that known
     * transaction, polls device status, then retries.
     *
     * This test does not model the previous process's transaction: the fake's active
     * transaction is the new host's GetDeviceInfo, not an unknown old id. It therefore does
     * not prove that an arbitrary unknown old transaction can be cancelled. The reset-only
     * cases for an unknown old transaction are covered by the two tests below.
     */
    @Test
    fun shouldRecover_whenBulkInTimeoutIsCancelledWithTheNewGetDeviceInfoTransactionId() {
        val device = FakeStillImageDevice(
            plan = ReplyPlan.AbandonedTransfer,
            deviceInfoPayload = deviceInfo("Canon", "EOS R100"),
        )

        val outcome = runCatching { connect(device) }
        val camera = outcome.getOrElse { error ->
            throw AssertionError(
                buildString {
                    appendLine("connectWithTransport did not recover after the GetDeviceInfo Bulk-IN timeout.")
                    appendLine(
                        "Expected: Cancel Request (0x64) carrying the id the new host actually " +
                            "issued (GetDeviceInfo tid 0; the killed process's id is unknown), " +
                            "then Get_Device_Status (0x67) until OK, then a retried GetDeviceInfo " +
                            "succeeds."
                    )
                    appendLine("Actual error: $error")
                    append(device.renderTrace())
                }
            )
        }

        assertEquals("EOS R100", camera.info?.model)
        val firstInfo = device.commands.first { it.opcode == PtpOp.GET_DEVICE_INFO }
        assertEquals(
            "With no open session the first GetDeviceInfo uses transaction 0",
            0,
            firstInfo.transactionId,
        )
        assertEquals(2, device.getDeviceInfoCommands)
        assertEquals(
            "cancelAndResetDevice must receive the id of the GetDeviceInfo that timed out",
            listOf(0),
            device.recoveryRequests,
        )
        assertTrue(
            "Cancel must carry the transaction id that timed out",
            device.cancelRequests.any { it.transactionId == firstInfo.transactionId },
        )
        assertTrue(
            "Get_Device_Status must be polled until the device leaves the busy state",
            device.deviceStatusPolls >= 1,
        )
        assertFalse(device.closed)
    }

    /**
     * Unknown-old-transaction case A: ResetDevice (0x66) recovers the device.
     *
     * Concurrency premise: the process that owned the old transaction is dead and no second
     * host is issuing Bulk traffic while the new host resets. The fake supplies the reset
     * outcome; this is not evidence that every camera honors ResetDevice after an abnormal
     * exit. The host must connect without sending a Cancel, because it cannot know the old id.
     */
    @Test
    fun shouldConnectWithoutCancel_whenOldTransactionIsUnknownAndResetRecovers() {
        val device = FakeStillImageDevice(
            plan = ReplyPlan.UnknownOldTransactionResetRecovers,
            deviceInfoPayload = deviceInfo("Canon", "EOS R100"),
        )

        val outcome = runCatching { connect(device) }
        val camera = outcome.getOrElse { error ->
            throw AssertionError(
                buildString {
                    appendLine("connectWithTransport did not connect after ResetDevice recovered the device.")
                    appendLine("The old transaction id is unknown; no guessed Cancel was expected.")
                    appendLine("Actual error: $error")
                    append(device.renderTrace())
                }
            )
        }

        assertEquals("EOS R100", camera.info?.model)
        assertEquals(1, device.getDeviceInfoCommands)
        assertTrue("ResetDevice must be attempted", device.deviceResets >= 1)
        assertTrue(
            "No Cancel may be sent when the old transaction id is unknown",
            device.cancelRequests.isEmpty(),
        )
        assertTrue(
            "The init path must not pass a guessed id to cancelAndResetDevice",
            device.recoveryRequests.all { it == null },
        )
        assertFalse("ResetDevice must clear the old transaction in this model", device.oldTransactionActive)
        assertFalse(device.closed)
    }

    /**
     * Unknown-old-transaction case B: ResetDevice never clears it and the device answers
     * Device_Busy forever.
     *
     * Concurrency premise: the old process is dead and no second host is issuing Bulk traffic;
     * the device is simply wedged. The init path must stop at its retry budget and fail
     * without inventing a transaction id for the old transfer. Even the host's own
     * GetDeviceInfo tid cannot name the old transaction, so no integer Cancel is acceptable.
     */
    @Test
    fun shouldFailWithinBoundWithoutGuessingOldTransactionId_whenResetNeverClearsDeviceBusy() {
        val device = FakeStillImageDevice(
            plan = ReplyPlan.UnknownOldTransactionResetNeverWorks,
            deviceInfoPayload = deviceInfo("Canon", "EOS R100"),
        )

        val error = assertThrows(PtpDeviceException::class.java) {
            connect(device)
        }

        assertEquals(PtpStatus.DEVICE_BUSY, error.code)
        assertEquals(INIT_SETTLE_DELAYS.size, device.getDeviceInfoCommands)
        assertTrue(
            "No Cancel with an integer transaction id may be sent for an unknown old id",
            device.cancelRequests.isEmpty(),
        )
        assertTrue(
            "Only null/unknown recovery calls are acceptable; ids=${device.recoveryRequests}",
            device.recoveryRequests.all { it == null },
        )
        assertTrue("The old transaction must still be pending after the bounded failure", device.oldTransactionActive)
        assertTrue(device.closed)
    }

    @Test
    fun shouldRetryWithinBound_whenFirstGetDeviceInfoAnswersDeviceBusy() {
        val device = FakeStillImageDevice(
            plan = ReplyPlan.DeviceBusyThenOk,
            deviceInfoPayload = deviceInfo("Canon", "EOS R100"),
        )

        val outcome = runCatching { connect(device) }
        val camera = outcome.getOrElse { error ->
            throw AssertionError(
                buildString {
                    appendLine("connectWithTransport treated a PTP Device_Busy (0x2019) response as fatal.")
                    appendLine(
                        "Expected a bounded retry within the ${INIT_SETTLE_DELAYS.size}-attempt init " +
                            "budget; the device became available on attempt 2."
                    )
                    appendLine("Actual error: $error")
                    append(device.renderTrace())
                }
            )
        }

        assertEquals("EOS R100", camera.info?.model)
        assertEquals(2, device.getDeviceInfoCommands)
        assertTrue(device.getDeviceInfoCommands <= INIT_SETTLE_DELAYS.size)
    }

    @Test
    fun shouldStopAtTheInitBudget_whenDeviceBusyNeverClears() {
        val device = FakeStillImageDevice(
            plan = ReplyPlan.RejectAlways(PtpStatus.DEVICE_BUSY),
            deviceInfoPayload = deviceInfo("Canon", "EOS R100"),
        )

        val error = assertThrows(PtpDeviceException::class.java) {
            connect(device)
        }

        assertEquals(PtpStatus.DEVICE_BUSY, error.code)
        assertTrue(
            "DeviceBusy retries must stop at the init budget; " +
                "attempts=${device.getDeviceInfoCommands}",
            device.getDeviceInfoCommands <= INIT_SETTLE_DELAYS.size,
        )
    }

    @Test
    fun shouldFailFast_whenGetDeviceInfoIsDeterministicallyRejected() {
        val deterministicCodes = listOf(
            PtpStatus.OPERATION_NOT_SUPPORTED,
            PtpStatus.ACCESS_DENIED,
            PtpStatus.INVALID_TRANSACTION_ID,
        )

        for (code in deterministicCodes) {
            val device = FakeStillImageDevice(
                plan = ReplyPlan.RejectAlways(code),
                deviceInfoPayload = deviceInfo("Canon", "EOS R100"),
            )

            val error = assertThrows(PtpDeviceException::class.java) {
                connect(device)
            }

            assertEquals("response code must reach the caller", code, error.code)
            assertEquals(
                "a deterministic rejection must fail fast, not consume the retry budget",
                1,
                device.getDeviceInfoCommands,
            )
        }
    }

    /**
     * Model self-check only: proves the fake device is satisfiable by the specified handshake.
     * This does not exercise production code; the production tests above do.
     */
    @Test
    fun shouldKeepDeviceBusyAfterValidCancel_untilDeviceStatusReportsOk() {
        val payload = deviceInfo("Canon", "EOS R100")
        val device = FakeStillImageDevice(ReplyPlan.AbandonedTransfer, payload)

        device.writeContainer(PtpContainer.command(PtpOp.GET_DEVICE_INFO, 0))
        assertThrows(PtpTransportException::class.java) { device.readHeader() }

        device.sendCancelRequest(0)
        assertTrue(device.awaitingDeviceStatus)

        device.writeContainer(PtpContainer.command(PtpOp.GET_DEVICE_INFO, 0))
        val busy = device.readHeader()
        assertEquals(PtpContainer.TYPE_RESPONSE, busy.type)
        assertEquals(PtpStatus.DEVICE_BUSY, busy.code)

        assertEquals(PtpStatus.DEVICE_BUSY, device.pollDeviceStatus())
        assertTrue(device.awaitingDeviceStatus)
        assertEquals(PtpStatus.OK, device.pollDeviceStatus())
        assertFalse(device.awaitingDeviceStatus)

        device.writeContainer(PtpContainer.command(PtpOp.GET_DEVICE_INFO, 0))
        val data = device.readHeader()
        assertEquals(PtpContainer.TYPE_DATA, data.type)
        assertArrayEquals(payload, device.readBody(data.length - PtpContainer.HEADER_BYTES))
        assertEquals(PtpStatus.OK, device.readHeader().code)
        assertTrue("The cancelled transfer must not leak stale headers", device.staleHeaderTypes.isEmpty())
    }

    private fun connect(device: FakeStillImageDevice): PtpCamera = runBlocking {
        PtpCamera.connectWithTransport(
            usbDevice = null,
            transport = device,
            profile = CanonEosProfile(),
            ioDispatcher = Dispatchers.Unconfined,
            settleDelaysMs = INIT_SETTLE_DELAYS,
        )
    }

    private fun deviceInfo(manufacturer: String, model: String): ByteArray {
        val out = ByteArrayOutputStream(160)

        fun u16(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }

        fun u32(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
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

        val operations = intArrayOf(
            PtpOp.GET_DEVICE_INFO,
            PtpOp.OPEN_SESSION,
            PtpOp.CLOSE_SESSION,
            PtpOp.GET_STORAGE_IDS,
            PtpOp.GET_STORAGE_INFO,
            PtpOp.GET_OBJECT_HANDLES,
            PtpOp.GET_OBJECT_INFO,
            PtpOp.GET_OBJECT,
            PtpOp.DELETE_OBJECT,
        )

        u16(100) // standardVersion
        u32(0) // vendorExtensionId
        u16(100) // vendorExtensionVersion
        ptpString("") // vendorExtensionDesc
        u16(0) // functionalMode
        u32(operations.size)
        operations.forEach { u16(it) }
        u32(0) // eventsSupported
        u32(0) // devicePropertiesSupported
        u32(0) // captureFormats
        u32(0) // imageFormats
        ptpString(manufacturer)
        ptpString(model)
        ptpString("1.0.0")
        ptpString("000000000000")
        return out.toByteArray()
    }

    private companion object {
        val INIT_SETTLE_DELAYS = longArrayOf(0L, 0L, 0L)
    }
}
