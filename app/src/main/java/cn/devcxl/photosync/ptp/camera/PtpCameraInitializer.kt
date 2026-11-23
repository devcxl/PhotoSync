package cn.devcxl.photosync.ptp.camera

import cn.devcxl.photosync.ptp.protocol.PtpDeviceException
import cn.devcxl.photosync.ptp.protocol.PtpDeviceInfo
import cn.devcxl.photosync.ptp.protocol.PtpException
import cn.devcxl.photosync.ptp.protocol.PtpProtocolException
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import cn.devcxl.photosync.ptp.transport.PtpTransport
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Brings a freshly opened USB link up to the point where it can carry a PTP session:
 * reset the device, let it settle, and read its device info.
 *
 * Kept out of [PtpCamera] because the handshake is a bounded retry policy of its own. A camera
 * left mid-transfer may return an invalid frame, a transport error, or Device_Busy. When this
 * process has an unfinished transaction, recovery can cancel it with its actual id; the state of
 * a transaction left by a previous process remains unknown.
 *
 * The initializer owns no camera state. The camera hands over the link, the session whose
 * in-flight transaction id the cancel refers to, a reader for the device info, and a sink
 * the result is published to, so the recovery loop can run without reaching into the camera.
 */
internal class PtpCameraInitializer(
    private val transport: PtpTransport,
    private val session: PtpSession,
    private val readDeviceInfo: () -> PtpDeviceInfo,
    private val onDeviceInfo: (PtpDeviceInfo) -> Unit,
) {

    /**
     * Runs the connect handshake, making at most one attempt per entry in [settleDelaysMs].
     *
     * @throws PtpException when every attempt failed
     */
    suspend fun initialise(settleDelaysMs: LongArray = DEFAULT_SETTLE_DELAYS_MS) {
        var lastError: PtpException? = null
        val delays = if (settleDelaysMs.isNotEmpty()) settleDelaysMs else DEFAULT_SETTLE_DELAYS_MS
        for (attempt in delays.indices) {
            try {
                if (attempt == 0) {
                    transport.resetDevice()
                } else if (lastError !is PtpDeviceException) {
                    transport.cancelAndResetDevice(session.inFlightTransactionId)
                }
                // Wait after reset or a completed DeviceBusy response, then flush late packets
                // before sending GetDeviceInfo (0x1001).
                val settleMs = delays[attempt]
                if (settleMs > 0L) {
                    delay(settleMs)
                }
                transport.drainPendingInput()
                val info = readDeviceInfo()
                onDeviceInfo(info)
                Timber.i(
                    "Connected to %s %s (fw %s, vendor extension 0x%04x, %d operations, %d events)",
                    info.manufacturer,
                    info.model,
                    info.deviceVersion,
                    info.vendorExtensionId,
                    info.operationsSupported.size,
                    info.eventsSupported.size,
                )
                return
            } catch (e: PtpProtocolException) {
                lastError = e
                Timber.w(
                    e,
                    "GetDeviceInfo attempt %d/%d got unexpected framing; %s",
                    attempt + 1,
                    delays.size,
                    if (attempt + 1 < delays.size) "retrying after pipe recovery" else "no retries remain",
                )
            } catch (e: PtpTransportException) {
                lastError = e
                Timber.w(
                    e,
                    "GetDeviceInfo attempt %d/%d transport failed; %s",
                    attempt + 1,
                    delays.size,
                    if (attempt + 1 < delays.size) "retrying after pipe recovery" else "no retries remain",
                )
            } catch (e: PtpDeviceException) {
                if (e.code != PtpStatus.DEVICE_BUSY) throw e
                lastError = e
                Timber.w(
                    "GetDeviceInfo attempt %d/%d: camera busy",
                    attempt + 1,
                    delays.size,
                )
            }
        }
        throw lastError ?: PtpProtocolException("GetDeviceInfo failed after ${delays.size} attempts")
    }

    internal companion object {
        /**
         * One attempt right after the reset plus two retries. A fresh array per read, so a
         * caller that passes the default on cannot mutate state shared with other cameras.
         */
        val DEFAULT_SETTLE_DELAYS_MS: LongArray
            get() = longArrayOf(300L, 500L, 800L)
    }
}
