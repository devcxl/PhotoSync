package cn.devcxl.photosync.ptp.transport

import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PtpUsbRecovery], driven through real [PtpRecoveryControl] logic over a
 * scripted control transfer. Verifies the USB step order and every terminal outcome.
 */
class PtpUsbRecoveryTest {

    private val events = mutableListOf<String>()
    private val control = ScriptedControl(events)

    @Test
    fun shouldDrainResetClearPollAndDrain_whenResetting() {
        control.statuses += PtpStatus.OK

        recovery().resetDevice()

        assertEquals(listOf("drain", "reset", "clear", "status", "drain"), events)
    }

    @Test
    fun shouldCancelPollThenReset_whenTransactionIdIsKnown() {
        control.statuses += PtpStatus.DEVICE_BUSY
        control.statuses += PtpStatus.OK
        control.statuses += PtpStatus.OK

        recovery().cancelAndResetDevice(0x2A)

        assertEquals(listOf(0x2A), control.cancelledIds)
        assertEquals(
            listOf(
                "drain", "cancel:0x2a", "status", "status", "drain",
                "reset", "clear", "status", "drain",
            ),
            events,
        )
    }

    @Test
    fun shouldNotSendCancel_whenTransactionIdIsUnknown() {
        control.statuses += PtpStatus.OK

        recovery().cancelAndResetDevice(null)

        assertTrue(control.cancelledIds.isEmpty())
        assertEquals(listOf("drain", "reset", "clear", "status", "drain"), events)
    }

    @Test
    fun shouldFail_whenDeviceStaysBusyAfterReset() {
        control.defaultStatus = PtpStatus.DEVICE_BUSY

        assertThrows(PtpTransportException::class.java) { recovery().resetDevice() }

        assertEquals(10, control.statusCalls)
        assertEquals(listOf("drain", "reset", "clear") + List(10) { "status" }, events)
    }

    @Test
    fun shouldStillReset_whenDeviceStatusIsErrorDuringCancel() {
        control.statuses += 0x201F
        control.statuses += PtpStatus.OK

        recovery().cancelAndResetDevice(7)

        assertEquals(
            listOf("drain", "cancel:0x07", "status", "drain", "reset", "clear", "status", "drain"),
            events,
        )
    }

    @Test
    fun shouldThrow_whenResetStatusIsExplicitError() {
        control.statuses += 0x201F

        val error = assertThrows(PtpTransportException::class.java) { recovery().resetDevice() }

        assertTrue(error.message.orEmpty().contains("201f"))
        assertEquals(listOf("drain", "reset", "clear", "status"), events)
    }

    @Test
    fun shouldProceedWithFallback_whenStatusIsUnavailableAfterReset() {
        control.statusResult = -1

        recovery().resetDevice()

        assertEquals(listOf("drain", "reset", "clear", "status", "drain"), events)
    }

    @Test
    fun shouldPreserveStatusException_whenResetStatusRequestThrows() {
        val boom = IllegalStateException("synthetic")
        control.throwOnStatus = boom

        val error = assertThrows(PtpTransportException::class.java) { recovery().resetDevice() }

        assertSame(boom, error.cause)
    }

    private fun recovery(): PtpUsbRecovery = PtpUsbRecovery(
        recoveryControl = PtpRecoveryControl(control) { },
        drain = { events += "drain" },
        reset = { events += "reset" },
        clearHalt = { events += "clear" },
    )
}

private class ScriptedControl(private val events: MutableList<String>) : PtpControlTransfer {
    val statuses = ArrayDeque<Int>()
    val cancelledIds = mutableListOf<Int>()
    var defaultStatus = PtpStatus.OK
    var statusResult = 0
    var throwOnStatus: Throwable? = null
    var statusCalls = 0

    override fun transfer(
        requestType: Int,
        request: Int,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): Int = when (request) {
        0x64 -> {
            val id = (buffer[2].toInt() and 0xff) or
                ((buffer[3].toInt() and 0xff) shl 8) or
                ((buffer[4].toInt() and 0xff) shl 16) or
                ((buffer[5].toInt() and 0xff) shl 24)
            cancelledIds += id
            events += "cancel:0x%02x".format(id)
            CANCEL_PAYLOAD_BYTES
        }
        0x67 -> {
            statusCalls++
            events += "status"
            throwOnStatus?.let { throw it }
            if (statusResult < 0) {
                statusResult
            } else {
                writeStatus(buffer, statuses.removeFirstOrNull() ?: defaultStatus)
                STATUS_PAYLOAD_BYTES
            }
        }
        else -> -1
    }

    private fun writeStatus(buffer: ByteArray, code: Int) {
        buffer[0] = 4
        buffer[1] = 0
        buffer[2] = (code and 0xff).toByte()
        buffer[3] = ((code ushr 8) and 0xff).toByte()
    }

    private companion object {
        const val CANCEL_PAYLOAD_BYTES = 6
        const val STATUS_PAYLOAD_BYTES = 4
    }
}
