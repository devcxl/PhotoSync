package cn.devcxl.photosync.ptp.transport

import cn.devcxl.photosync.ptp.protocol.PtpStatus
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PtpRecoveryControl], driven through the injected control transfer and
 * delay. Covers the payload/request shape, bounded BUSY polling, and every terminal result.
 */
class PtpRecoveryControlTest {

    @Test
    fun shouldSendCancelWithRealTransactionIdInLittleEndian() {
        val control = FakeControl()

        val result = recovery(control).sendCancel(0x12345678)

        assertEquals(RecoveryResult.Ready, result)
        val call = control.calls.single()
        assertEquals(0x21, call.requestType)
        assertEquals(0x64, call.request)
        assertEquals(6, call.length)
        assertEquals(500, call.timeoutMs)
        assertArrayEquals(
            byteArrayOf(0x01, 0x40, 0x78, 0x56, 0x34, 0x12),
            call.payload,
        )
    }

    @Test
    fun shouldAcceptZeroAsARealTransactionId() {
        val control = FakeControl()

        assertEquals(RecoveryResult.Ready, recovery(control).sendCancel(0))

        assertArrayEquals(
            byteArrayOf(0x01, 0x40, 0x00, 0x00, 0x00, 0x00),
            control.calls.single().payload,
        )
    }

    @Test
    fun shouldReturnUnavailableWithoutSendingWhenTransactionIdIsUnknown() {
        val control = FakeControl()
        val recovery = recovery(control)

        assertEquals(RecoveryResult.Unavailable, recovery.sendCancel(null))
        assertEquals(RecoveryResult.Unavailable, recovery.sendCancel(-1))
        assertTrue(control.calls.isEmpty())
    }

    @Test
    fun shouldReturnUnavailableWhenCancelControlCallFails() {
        val control = FakeControl().apply { cancelResult = -1 }

        assertEquals(RecoveryResult.Unavailable, recovery(control).sendCancel(7))
    }

    @Test
    fun shouldRejectShortCancelWrite() {
        val control = FakeControl().apply { cancelResult = 3 }

        assertIs<RecoveryResult.Error>(recovery(control).sendCancel(7))
    }

    @Test
    fun shouldPreserveCauseWhenCancelRequestThrows() {
        val boom = IllegalStateException("synthetic")
        val control = FakeControl().apply { throwOnCancel = boom }

        val result = assertIs<RecoveryResult.Error>(recovery(control).sendCancel(7))

        assertSame(boom, result.cause)
    }

    @Test
    fun shouldPropagateCancellation_whenControlRequestIsCancelled() {
        val control = FakeControl().apply { throwOnStatus = CancellationException("cancelled") }

        assertThrows(CancellationException::class.java) {
            recovery(control).awaitReady()
        }
    }

    @Test
    fun shouldReadOkStatusWithBoundedRequest() {
        val control = FakeControl()
        control.statusResponses += statusPayload(PtpStatus.OK)

        val status = assertIs<DeviceStatus.Code>(recovery(control).getDeviceStatus())

        assertEquals(PtpStatus.OK, status.code)
        val call = control.calls.single()
        assertEquals(0xA1, call.requestType)
        assertEquals(0x67, call.request)
        assertEquals(16, call.length)
        assertEquals(500, call.timeoutMs)
    }

    @Test
    fun shouldAcceptLongStatusWithParameters() {
        val control = FakeControl()
        control.statusResponses += statusPayload(PtpStatus.DEVICE_BUSY, declaredLength = 8)

        val status = assertIs<DeviceStatus.Code>(recovery(control).getDeviceStatus())

        assertEquals(PtpStatus.DEVICE_BUSY, status.code)
    }

    @Test
    fun shouldIgnoreTrailingBytesBeyondDeclaredLength() {
        val control = FakeControl()
        val payload = ByteArray(16) { 0x7f }
        payload[0] = 4
        payload[1] = 0
        payload[2] = (PtpStatus.OK and 0xff).toByte()
        payload[3] = ((PtpStatus.OK ushr 8) and 0xff).toByte()
        control.statusResponses += payload

        val status = assertIs<DeviceStatus.Code>(recovery(control).getDeviceStatus())

        assertEquals(PtpStatus.OK, status.code)
    }

    @Test
    fun shouldRejectShortStatusRead() {
        val control = FakeControl()
        control.statusResponses += byteArrayOf(0x04, 0x00)

        assertIs<DeviceStatus.Error>(recovery(control).getDeviceStatus())
    }

    @Test
    fun shouldRejectDeclaredLengthBeyondReceivedBytes() {
        val control = FakeControl()
        control.statusResponses += statusPayload(PtpStatus.OK, declaredLength = 8).copyOf(4)

        assertIs<DeviceStatus.Error>(recovery(control).getDeviceStatus())
    }

    @Test
    fun shouldRejectDeclaredLengthBelowHeader() {
        val control = FakeControl()
        control.statusResponses += statusPayload(PtpStatus.OK, declaredLength = 2)

        assertIs<DeviceStatus.Error>(recovery(control).getDeviceStatus())
    }

    @Test
    fun shouldRejectUnalignedDeclaredLength() {
        val control = FakeControl()
        control.statusResponses += statusPayload(PtpStatus.OK, declaredLength = 6)

        assertIs<DeviceStatus.Error>(recovery(control).getDeviceStatus())
    }

    @Test
    fun shouldReturnUnavailableWhenStatusControlCallFails() {
        val control = FakeControl().apply { statusResult = -1 }

        assertEquals(DeviceStatus.Unavailable, recovery(control).getDeviceStatus())
    }

    @Test
    fun shouldPreserveCauseWhenStatusRequestThrows() {
        val boom = IllegalStateException("synthetic")
        val control = FakeControl().apply { throwOnStatus = boom }

        val result = assertIs<RecoveryResult.Error>(recovery(control).awaitReady())

        assertSame(boom, result.cause)
    }

    @Test
    fun shouldPollBusyUntilReady() {
        val control = FakeControl()
        val delay = DelayRecorder()
        control.statusResponses += statusPayload(PtpStatus.DEVICE_BUSY)
        control.statusResponses += statusPayload(PtpStatus.DEVICE_BUSY)
        control.statusResponses += statusPayload(PtpStatus.OK)

        val result = recovery(control, delay.delay).awaitReady()

        assertEquals(RecoveryResult.Ready, result)
        assertEquals(listOf(50L, 50L), delay.sleeps)
        assertEquals(3, control.calls.size)
    }

    @Test
    fun shouldReturnBusyTimeoutAfterBoundedPolls() {
        val control = FakeControl()
        val delay = DelayRecorder()
        repeat(10) { control.statusResponses += statusPayload(PtpStatus.DEVICE_BUSY) }

        val result = recovery(control, delay.delay).awaitReady()

        assertEquals(RecoveryResult.BusyTimeout, result)
        assertEquals(10, control.calls.size)
        assertEquals(List(9) { 50L }, delay.sleeps)
    }

    @Test
    fun shouldStopOnUnavailableStatusWithoutPollingAgain() {
        val control = FakeControl().apply { statusResult = -1 }
        val delay = DelayRecorder()

        val result = recovery(control, delay.delay).awaitReady()

        assertEquals(RecoveryResult.Unavailable, result)
        assertEquals(1, control.calls.size)
        assertTrue(delay.sleeps.isEmpty())
    }

    @Test
    fun shouldNotClaimSuccessOnTransactionCancelledStatus() {
        val control = FakeControl()
        val delay = DelayRecorder()
        control.statusResponses += statusPayload(0x201f)

        val result = assertIs<RecoveryResult.Error>(recovery(control, delay.delay).awaitReady())

        assertTrue(result.detail.contains("201f"))
        assertEquals(1, control.calls.size)
        assertTrue(delay.sleeps.isEmpty())
    }

    @Test
    fun shouldStopOnMalformedStatusWithoutPollingAgain() {
        val control = FakeControl()
        val delay = DelayRecorder()
        control.statusResponses += byteArrayOf(0x04, 0x00)

        assertIs<RecoveryResult.Error>(recovery(control, delay.delay).awaitReady())
        assertEquals(1, control.calls.size)
        assertTrue(delay.sleeps.isEmpty())
    }

    @Test
    fun shouldPropagateInterruptWithoutWrappingIt() {
        val control = FakeControl().apply { throwOnStatus = InterruptedException("synthetic") }
        try {
            assertThrows(InterruptedException::class.java) {
                recovery(control).awaitReady()
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun shouldRestoreInterruptFlag_whenStatusPollDelayIsInterrupted() {
        val control = FakeControl()
        control.statusResponses += statusPayload(PtpStatus.DEVICE_BUSY)
        try {
            assertThrows(InterruptedException::class.java) {
                recovery(control) { throw InterruptedException("synthetic") }.awaitReady()
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun recovery(
        control: PtpControlTransfer,
        delay: (Long) -> Unit = {},
    ): PtpRecoveryControl = PtpRecoveryControl(control, delay)
}

private class FakeControl : PtpControlTransfer {
    data class Call(
        val requestType: Int,
        val request: Int,
        val payload: ByteArray,
        val length: Int,
        val timeoutMs: Int,
    )

    val calls = mutableListOf<Call>()
    val statusResponses = ArrayDeque<ByteArray>()
    var cancelResult = 6
    var statusResult = 0
    var throwOnCancel: Throwable? = null
    var throwOnStatus: Throwable? = null

    override fun transfer(
        requestType: Int,
        request: Int,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): Int {
        calls += Call(
            requestType = requestType,
            request = request,
            payload = if (requestType == 0x21) buffer.copyOf(length) else ByteArray(0),
            length = length,
            timeoutMs = timeoutMs,
        )
        return when (request) {
            0x64 -> {
                throwOnCancel?.let { throw it }
                cancelResult
            }
            0x67 -> {
                throwOnStatus?.let { throw it }
                if (statusResult < 0) {
                    return statusResult
                }
                val response = statusResponses.removeFirstOrNull() ?: return -1
                response.copyInto(buffer)
                response.size
            }
            else -> -1
        }
    }
}

private class DelayRecorder {
    val sleeps = mutableListOf<Long>()
    val delay: (Long) -> Unit = { sleeps += it }
}

private fun statusPayload(code: Int, declaredLength: Int = 4): ByteArray {
    val bytes = ByteArray(maxOf(declaredLength, 4))
    bytes[0] = (declaredLength and 0xff).toByte()
    bytes[1] = ((declaredLength ushr 8) and 0xff).toByte()
    bytes[2] = (code and 0xff).toByte()
    bytes[3] = ((code ushr 8) and 0xff).toByte()
    return bytes
}

private inline fun <reified T> assertIs(value: Any?): T {
    assertTrue(
        "Expected ${T::class.simpleName}, got ${value?.javaClass?.simpleName ?: "null"}",
        value is T,
    )
    return value as T
}
