package cn.devcxl.photosync.ptp.transport

import cn.devcxl.photosync.ptp.protocol.PtpStatus
import kotlinx.coroutines.CancellationException

/**
 * Minimal USB Still Image Capture control primitives for an interrupted transaction.
 *
 * It does not reset the device, drain Bulk-IN or touch sessions; `UsbPtpTransport` coordinates
 * those. Only a real PIMA transaction id is accepted: `null` or reserved `0xFFFFFFFF` return
 * [RecoveryResult.Unavailable] without touching the device, because there is no transaction to
 * cancel. A negative control result is also unavailable, not proof of missing support: Android
 * cannot distinguish timeout, disconnect and STALL. Blocking and not thread-safe.
 */
internal class PtpRecoveryControl(
    private val controlTransfer: PtpControlTransfer,
    private val delay: (Long) -> Unit = { millis -> Thread.sleep(millis) },
) {
    /**
     * Sends Cancel Request (`0x64`) with `0x4001` + [transactionId], little-endian (SIC §5.2.1.2).
     * A negative control result is [RecoveryResult.Unavailable]: Android cannot tell timeout,
     * disconnect and STALL apart, so it is not evidence that cancel is unsupported.
     */
    fun sendCancel(transactionId: Int?): RecoveryResult {
        if (transactionId == null || transactionId == UNKNOWN_TRANSACTION_ID) {
            return RecoveryResult.Unavailable
        }
        val payload = cancelPayload(transactionId)
        val result = try {
            controlTransfer.transfer(
                requestType = REQUEST_TYPE_OUT,
                request = REQUEST_CANCEL,
                buffer = payload,
                length = payload.size,
                timeoutMs = CONTROL_TIMEOUT_MS,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RecoveryResult.Error("CancelRequest failed", e)
        }
        return when {
            result < 0 -> RecoveryResult.Unavailable
            result != payload.size -> RecoveryResult.Error("CancelRequest wrote $result of ${payload.size} bytes")
            else -> RecoveryResult.Ready
        }
    }

    /**
     * Reads Get Device Status (`0x67`) once: `wLength`, code, then optional 32-bit parameters.
     * A declared length outside `4..received` or not 32-bit aligned is [DeviceStatus.Error].
     * A negative control result is [DeviceStatus.Unavailable] for the same ambiguity: timeout,
     * disconnect or STALL, not proof that the request is unsupported.
     */
    fun getDeviceStatus(): DeviceStatus {
        val buffer = ByteArray(STATUS_BYTES)
        val received = try {
            controlTransfer.transfer(
                requestType = REQUEST_TYPE_IN,
                request = REQUEST_GET_DEVICE_STATUS,
                buffer = buffer,
                length = buffer.size,
                timeoutMs = CONTROL_TIMEOUT_MS,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DeviceStatus.Error("GetDeviceStatus failed", e)
        }
        if (received < 0) {
            return DeviceStatus.Unavailable
        }
        if (received < STATUS_HEADER_BYTES || received > buffer.size) {
            return DeviceStatus.Error("bad status read: $received bytes")
        }
        val declaredLength = leU16(buffer, 0)
        val code = leU16(buffer, 2)
        if (declaredLength < STATUS_HEADER_BYTES ||
            declaredLength > received ||
            (declaredLength - STATUS_HEADER_BYTES) % 4 != 0
        ) {
            return DeviceStatus.Error("bad status length: declared=$declaredLength, received=$received")
        }
        return DeviceStatus.Code(code)
    }

    /**
     * Polls until `OK` (0x2001), at most 10 times. `DEVICE_BUSY` (0x2019) continues; every
     * other code, malformed payload, unavailable control call or exception stops without
     * claiming success.
     */
    fun awaitReady(): RecoveryResult {
        for (attempt in 0 until POLL_ATTEMPTS) {
            if (attempt > 0) {
                try {
                    delay(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
            when (val status = getDeviceStatus()) {
                is DeviceStatus.Code -> when (status.code) {
                    PtpStatus.OK -> return RecoveryResult.Ready
                    PtpStatus.DEVICE_BUSY -> Unit
                    else -> return RecoveryResult.Error("device status 0x%04x".format(status.code))
                }
                DeviceStatus.Unavailable -> return RecoveryResult.Unavailable
                is DeviceStatus.Error -> return RecoveryResult.Error(status.detail, status.cause)
            }
        }
        return RecoveryResult.BusyTimeout
    }

    private fun cancelPayload(transactionId: Int): ByteArray = byteArrayOf(
        (CANCELLATION_CODE and 0xff).toByte(),
        ((CANCELLATION_CODE ushr 8) and 0xff).toByte(),
        transactionId.toByte(),
        (transactionId ushr 8).toByte(),
        (transactionId ushr 16).toByte(),
        (transactionId ushr 24).toByte(),
    )

    private fun leU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val REQUEST_TYPE_OUT = 0x21
        const val REQUEST_TYPE_IN = 0xA1
        const val REQUEST_CANCEL = 0x64
        const val REQUEST_GET_DEVICE_STATUS = 0x67
        const val CANCELLATION_CODE = 0x4001
        const val UNKNOWN_TRANSACTION_ID = -1
        const val STATUS_BYTES = 16
        const val STATUS_HEADER_BYTES = 4
        const val CONTROL_TIMEOUT_MS = 500
        const val POLL_ATTEMPTS = 10
        const val POLL_INTERVAL_MS = 50L
    }
}

/** Smallest result set the production path acts on. */
internal sealed interface RecoveryResult {
    /** Cancel accepted, or the device reported OK. */
    data object Ready : RecoveryResult

    data object BusyTimeout : RecoveryResult // still busy after every poll

    /** No usable id, or a negative control result: timeout, disconnect or STALL, not "unsupported". */
    data object Unavailable : RecoveryResult

    data class Error(val detail: String, val cause: Throwable? = null) : RecoveryResult
}

/** One Get Device Status read. */
internal sealed interface DeviceStatus {
    data class Code(val code: Int) : DeviceStatus

    /** The status read failed (timeout, disconnect or STALL), not proof of missing support. */
    data object Unavailable : DeviceStatus

    data class Error(val detail: String, val cause: Throwable? = null) : DeviceStatus
}

/**
 * Injected class-specific transfer. The adapter supplies `wValue = 0`, the PTP interface's
 * `wIndex` and buffer offset `0`, then returns Android's `controlTransfer` result unchanged.
 */
internal fun interface PtpControlTransfer {
    /** @return bytes received for IN, non-negative on OUT, negative on timeout, disconnect or STALL */
    fun transfer(
        requestType: Int,
        request: Int,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
    ): Int
}
