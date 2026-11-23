package cn.devcxl.photosync.ptp.transport

import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import timber.log.Timber

/**
 * Orders the USB-level recovery steps for an interrupted PTP pipe.
 *
 * The sequence is: drain Bulk-IN, optionally send a `CancelRequest`, issue the class-specific
 * device reset, clear both endpoint halts, wait for the device to report ready, then drain
 * again. The USB primitives are injected, so the orchestration is testable on the JVM.
 *
 * Blocking and not thread-safe, like [PtpTransport].
 */
internal class PtpUsbRecovery(
    private val recoveryControl: PtpRecoveryControl,
    private val drain: () -> Unit,
    private val reset: () -> Unit,
    private val clearHalt: () -> Unit,
) {
    /** Full reset of a wedged pipe, without a transaction id to cancel. */
    fun resetDevice() {
        drain()
        reset()
        clearHalt()
        awaitDeviceReady(operation = "reset", failWhenBusy = true, failOnError = true)
        drain()
    }

    /**
     * Cancels [transactionId] when it is known, then resets anyway.
     *
     * A reserved or unknown id means no cancel request is sent: the transaction is not guessed
     * and the result is [RecoveryResult.Unavailable]. Every cancel outcome except
     * [RecoveryResult.BusyTimeout] still proceeds to the reset, which is what actually clears
     * the pipe; a device-status failure at that point is reported only and does not hide the
     * later reset.
     */
    fun cancelAndResetDevice(transactionId: Int?) {
        drain()
        when (val result = recoveryControl.sendCancel(transactionId)) {
            RecoveryResult.Ready -> {
                // SIC §7.2.1.1 requires polling status while cancellation is in progress.
                awaitDeviceReady(operation = "cancel", failWhenBusy = false, failOnError = false)
                drain()
            }
            RecoveryResult.Unavailable ->
                Timber.w(
                    "PTP CancelRequest unavailable (timeout, disconnect, STALL or unknown " +
                        "transaction id); resetting device",
                )
            is RecoveryResult.Error ->
                Timber.w(result.cause, "PTP CancelRequest failed: %s; resetting device", result.detail)
            RecoveryResult.BusyTimeout -> error("CancelRequest does not poll device status")
        }
        reset()
        clearHalt()
        awaitDeviceReady(operation = "reset", failWhenBusy = true, failOnError = true)
        drain()
    }

    /**
     * Waits for `OK`. After a reset a busy device or an explicit error aborts recovery, because
     * issuing another command into that device would only produce more out-of-sync replies.
     * An unavailable status (timeout, disconnect or STALL) keeps the compatible fallback:
     * log and continue with the bounded retry of the caller.
     */
    private fun awaitDeviceReady(operation: String, failWhenBusy: Boolean, failOnError: Boolean) {
        when (val status = recoveryControl.awaitReady()) {
            RecoveryResult.Ready -> Timber.d("PTP device ready after %s", operation)
            RecoveryResult.BusyTimeout -> {
                Timber.w("PTP device remained busy after %s", operation)
                if (failWhenBusy) throw PtpTransportException("PTP device remained busy after $operation")
            }
            RecoveryResult.Unavailable ->
                Timber.w(
                    "PTP device status unavailable after %s (timeout, disconnect or STALL); " +
                        "proceeding with bounded retry",
                    operation,
                )
            is RecoveryResult.Error -> {
                Timber.w(status.cause, "PTP device status after %s: %s", operation, status.detail)
                if (failOnError) {
                    throw PtpTransportException(
                        "PTP device reported an error after $operation: ${status.detail}",
                        status.cause,
                    )
                }
            }
        }
    }
}
