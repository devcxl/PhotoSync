package cn.devcxl.photosync.ptp.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import cn.devcxl.photosync.ptp.protocol.PtpContainer
import cn.devcxl.photosync.ptp.protocol.PtpContainerHeader
import cn.devcxl.photosync.ptp.protocol.PtpTransportException
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * PTP-over-USB transport for one device.
 *
 * Owns the claimed interface and the two bulk endpoints. Timing constants follow what
 * cameras tolerate in practice: commands and responses are short and must answer quickly,
 * while an object transfer may legitimately pause for several seconds on a slow card.
 */
internal class UsbPtpTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkOut: UsbEndpoint,
    private val bulkIn: UsbEndpoint,
) : PtpTransport {

    /**
     * Packet size of the OUT endpoint, which decides when a container needs a terminating
     * zero-length packet. The two directions can advertise different sizes, and the host is
     * the side that has to terminate.
     */
    private val outPacketSize: Int = maxOf(1, bulkOut.maxPacketSize)

    private val bulkReader = PtpBulkInReader(
        maxPacketSize = bulkIn.maxPacketSize,
        readChunkBytes = READ_CHUNK_BYTES,
    ) { dest, offset, length, timeoutMs ->
        connection.bulkTransfer(bulkIn, dest, offset, length, timeoutMs)
    }

    private val recoveryControl = PtpRecoveryControl(
        PtpControlTransfer { requestType, request, buffer, length, timeoutMs ->
            connection.controlTransfer(
                requestType, request, 0, usbInterface.id, buffer, length, timeoutMs
            )
        }
    )

    private val recovery = PtpUsbRecovery(
        recoveryControl = recoveryControl,
        drain = { drainPendingInput() },
        reset = ::sendClassDeviceReset,
        clearHalt = {
            clearHalt(bulkOut)
            clearHalt(bulkIn)
        },
    )

    override fun writeContainer(bytes: ByteArray) {
        var written = 0
        while (written < bytes.size) {
            val chunk = connection.bulkTransfer(
                bulkOut,
                bytes,
                written,
                bytes.size - written,
                COMMAND_TIMEOUT_MS,
            )
            if (chunk <= 0) {
                throw PtpTransportException("Bulk-OUT stopped after $written of ${bytes.size} bytes ($chunk)")
            }
            written += chunk
        }
        // A container whose length is a multiple of the packet size needs an explicit
        // terminator, otherwise the camera keeps waiting for the packet to fill up.
        if (bytes.size % outPacketSize == 0) {
            // A non-empty buffer keeps the platform call happy; the length is what makes it
            // a zero-length packet.
            val zlp = connection.bulkTransfer(bulkOut, ZERO_LENGTH_PACKET, 0, COMMAND_TIMEOUT_MS)
            if (zlp < 0) {
                throw PtpTransportException("Bulk-OUT zero-length terminator failed ($zlp)")
            }
        }
    }

    override fun readHeader(): PtpContainerHeader = bulkReader.readHeader(COMMAND_TIMEOUT_MS)

    override fun readBody(length: Int): ByteArray {
        if (length > MAX_DATASET_BYTES) {
            // A dataset is a header or a metadata record; anything this large means the
            // length field is wrong and allocating it would be the real failure.
            throw PtpTransportException("Refusing to allocate a $length byte dataset")
        }
        val body = openBody(length)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            offset += body.read(bytes, offset, length - offset)
        }
        return bytes
    }

    override fun openBody(length: Int): PtpBody = BulkBody(length)

    override fun drainPendingInput(): Int {
        val drained = bulkReader.drain(
            timeoutMs = DRAIN_TIMEOUT_MS,
            maxBytes = MAX_DRAIN_BYTES,
            maxDurationMs = MAX_DRAIN_DURATION_MS,
        )
        if (drained > 0) {
            Timber.w("Drained %d stale bytes from USB Bulk-IN pipe", drained)
        }
        return drained
    }

    override fun resetDevice() = recovery.resetDevice()

    override fun cancelAndResetDevice(transactionId: Int?) =
        recovery.cancelAndResetDevice(transactionId)

    private fun sendClassDeviceReset() {
        val result = connection.controlTransfer(
            // bmRequestType: host-to-device, class, interface recipient (0x21) per USB
            // Still Image Capture Class Spec 1.0 section 5.1.
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or RECIPIENT_INTERFACE,
            CLASS_DEVICE_RESET,
            0,
            usbInterface.id,
            EMPTY,
            0,
            COMMAND_TIMEOUT_MS,
        )
        if (result < 0) {
            Timber.w("PTP DeviceReset (0x66) control transfer returned %d", result)
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    /**
     * Clears `ENDPOINT_HALT` on [endpoint] with the standard `CLEAR_FEATURE` request.
     *
     * Android's [UsbDeviceConnection] has no `clearHalt`, so the request goes out raw: a
     * standard, endpoint-recipient, host-to-device control transfer selecting the feature
     * by value 0.
     */
    private fun clearHalt(endpoint: UsbEndpoint) {
        try {
            val result = connection.controlTransfer(
                // bmRequestType: host-to-device, standard, endpoint recipient. UsbConstants
                // exposes the direction and type bits but no recipient constants.
                RECIPIENT_ENDPOINT,
                CLEAR_FEATURE,
                ENDPOINT_HALT,
                endpoint.address,
                EMPTY,
                0,
                COMMAND_TIMEOUT_MS,
            )
            if (result < 0) Timber.w("PTP clear endpoint halt failed (result=%d)", result)
        } catch (error: RuntimeException) {
            Timber.w(error, "PTP clear endpoint halt failed")
        }
    }

    /** Streams one data container body, one USB request at a time. */
    private inner class BulkBody(private var bytesLeft: Int) : PtpBody {
        override val remaining: Int get() = bytesLeft

        override fun read(dest: ByteArray, offset: Int, count: Int): Int {
            if (bytesLeft <= 0 || count <= 0) {
                return 0
            }
            val read = bulkReader.readBodyBytes(dest, offset, minOf(count, bytesLeft), DATA_TIMEOUT_MS)
            bytesLeft -= read
            return read
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** Buffer for a zero-length packet: the length passed is 0, not the buffer size. */
        private val ZERO_LENGTH_PACKET = ByteArray(1)

        /**
         * Timeout for command/response headers. Large SD cards can take 2-4 seconds to answer
         * `GetObjectHandles` or `DeleteObject`, so 1 second is too tight for real hardware.
         */
        private const val COMMAND_TIMEOUT_MS = 5_000
        private const val DATA_TIMEOUT_MS = 15_000
        private const val DRAIN_TIMEOUT_MS = 50
        private const val MAX_DRAIN_BYTES = 8 * 1024 * 1024
        private const val MAX_DRAIN_DURATION_MS = 1_500L
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val MAX_DATASET_BYTES = 16 * 1024 * 1024

        private const val CLASS_DEVICE_RESET = 0x66
        private const val CLEAR_FEATURE = 0x01
        private const val ENDPOINT_HALT = 0x00
        private const val RECIPIENT_INTERFACE = 0x01
        private const val RECIPIENT_ENDPOINT = 0x02
        private const val INTERFACE_CLASS_STILL_IMAGE = 6

        /**
         * Delays before re-opening a device, in order. A killed process can leave the USB service
         * releasing the old connection for a moment; the whole budget stays below one second so a
         * genuinely missing permission still fails fast.
         */
        private val OPEN_RETRY_DELAYS_MS = longArrayOf(300L, 600L)

        /**
         * Opens [device] and claims its PTP interface.
         *
         * A process that was killed while a session was open can leave the USB service releasing
         * the old file descriptor; opening immediately then fails with a null device or a refused
         * claim. Retrying with a short backoff reuses the same device instead of reporting a
         * permanent connect failure that only a replug would clear.
         *
         * @throws PtpTransportException when the device cannot be opened, exposes no usable
         *   interface, or refuses to be claimed on every attempt
         */
        suspend fun open(usbManager: UsbManager, device: UsbDevice): UsbPtpTransport {
            var lastError: PtpTransportException? = null
            for (attempt in OPEN_RETRY_DELAYS_MS.indices) {
                try {
                    return openOnce(usbManager, device)
                } catch (e: PtpTransportException) {
                    lastError = e
                    if (attempt == OPEN_RETRY_DELAYS_MS.lastIndex) break
                    Timber.w(
                        e,
                        "USB open attempt %d/%d failed; retrying in %dms",
                        attempt + 1,
                        OPEN_RETRY_DELAYS_MS.size,
                        OPEN_RETRY_DELAYS_MS[attempt],
                    )
                    delay(OPEN_RETRY_DELAYS_MS[attempt])
                }
            }
            throw lastError ?: PtpTransportException("Unable to open USB device ${device.deviceName}")
        }

        private fun openOnce(usbManager: UsbManager, device: UsbDevice): UsbPtpTransport {
            val connection = usbManager.openDevice(device)
                ?: throw PtpTransportException("Unable to open USB device ${device.deviceName}")

            val (usbInterface, bulkOut, bulkIn) = try {
                findEndpoints(device)
                    ?: throw PtpTransportException("No PTP bulk interface on ${device.deviceName}")
            } catch (e: PtpTransportException) {
                connection.close()
                throw e
            }

            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw PtpTransportException("Unable to claim interface ${usbInterface.id}")
            }

            return UsbPtpTransport(connection, usbInterface, bulkOut, bulkIn)
        }

        private data class Endpoints(
            val usbInterface: UsbInterface,
            val bulkOut: UsbEndpoint,
            val bulkIn: UsbEndpoint,
        )

        /**
         * Finds the interface that carries PTP traffic.
         *
         * Cameras are inconsistent here: most expose a still-image class interface, some a
         * vendor-specific one. The still-image interface wins when both exist; otherwise the
         * first interface that has both bulk directions is used.
         */
        private fun findEndpoints(device: UsbDevice): Endpoints? {
            var fallback: Endpoints? = null
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                val endpoints = endpointsOf(usbInterface) ?: continue
                if (usbInterface.interfaceClass == INTERFACE_CLASS_STILL_IMAGE) {
                    return endpoints
                }
                if (fallback == null) {
                    fallback = endpoints
                }
            }
            return fallback
        }

        private fun endpointsOf(usbInterface: UsbInterface): Endpoints? {
            var bulkOut: UsbEndpoint? = null
            var bulkIn: UsbEndpoint? = null
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue
                }
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    bulkOut = endpoint
                } else {
                    bulkIn = endpoint
                }
            }
            val out = bulkOut ?: return null
            val input = bulkIn ?: return null
            return Endpoints(usbInterface, out, input)
        }
    }
}

/**
 * Packet-aligned reader for the PTP bulk-IN pipe.
 *
 * A USB bulk-IN URB whose buffer is smaller than the endpoint's `wMaxPacketSize` (such as a
 * 12-byte container header read) fails with `EOVERFLOW` on Android/Linux whenever the camera
 * sends the header and payload together in one USB packet. Reading in multiples of
 * [maxPacketSize] and buffering the packet tail fixes that without copying full-speed object
 * transfers through an extra buffer.
 */
internal class PtpBulkInReader(
    maxPacketSize: Int,
    readChunkBytes: Int = DEFAULT_READ_CHUNK_BYTES,
    private val readUsb: (dest: ByteArray, offset: Int, length: Int, timeoutMs: Int) -> Int,
) {
    private val packetSize = maxOf(1, maxPacketSize)
    private val alignedChunkBytes = maxOf(packetSize, readChunkBytes - (readChunkBytes % packetSize))
    private val packetBuffer = ByteArray(packetSize)
    private var bufferStart = 0
    private var bufferEnd = 0

    private val bufferedCount: Int get() = bufferEnd - bufferStart

    fun clear() {
        bufferStart = 0
        bufferEnd = 0
    }

    /**
     * Discards the buffered tail and drains unread packets until several consecutive empty reads
     * or [maxBytes] / [maxDurationMs] is reached. A single USB timeout does not prove the device
     * has finished sending an interrupted transfer; later bytes could otherwise corrupt the next
     * container header.
     *
     * Every read submitted to [readUsb] stays packet-aligned so Android/Linux host controllers
     * never fail the drain itself with `EOVERFLOW`.
     */
    fun drain(
        timeoutMs: Int,
        maxBytes: Int,
        maxDurationMs: Long,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    ): Int {
        var totalDrained = bufferedCount
        clear()
        if (maxBytes <= 0) return totalDrained
        val scratch = ByteArray(alignedChunkBytes)
        val startedAt = clockMs()
        var emptyReads = 0
        while (totalDrained < maxBytes && (clockMs() - startedAt) < maxDurationMs) {
            val got = readUsb(scratch, 0, scratch.size, timeoutMs)
            if (got > 0) {
                totalDrained += got
                emptyReads = 0
            } else {
                emptyReads++
                if (emptyReads >= MAX_DRAIN_EMPTY_READS) break
            }
        }
        return totalDrained
    }

    fun readHeader(timeoutMs: Int): PtpContainerHeader {
        val header = ByteArray(PtpContainer.HEADER_BYTES)
        var filled = drainBuffered(header, 0, header.size)
        var zeroPackets = 0
        while (filled < header.size) {
            val got = readPacketIntoBuffer(timeoutMs)
            if (got > 0) {
                filled += drainBuffered(header, filled, header.size - filled)
                continue
            }
            if (got == 0 && zeroPackets < MAX_ZERO_PACKETS) {
                zeroPackets++
                continue
            }
            throw PtpTransportException("Bulk-IN header read failed after $filled bytes ($got)")
        }
        return PtpContainerHeader.parse(header)
    }

    fun readBodyBytes(dest: ByteArray, offset: Int, count: Int, timeoutMs: Int): Int {
        if (count <= 0) {
            return 0
        }
        if (bufferedCount > 0) {
            return drainBuffered(dest, offset, count)
        }
        var zeroPackets = 0
        while (true) {
            val got = if (count >= packetSize) {
                val alignedRequest = minOf(count - (count % packetSize), alignedChunkBytes)
                readUsb(dest, offset, alignedRequest, timeoutMs)
            } else {
                val read = readPacketIntoBuffer(timeoutMs)
                if (read > 0) {
                    return drainBuffered(dest, offset, count)
                }
                read
            }
            if (got > 0) {
                return got
            }
            if (got == 0 && zeroPackets < MAX_ZERO_PACKETS) {
                zeroPackets++
                continue
            }
            throw PtpTransportException("Bulk-IN body read failed ($got)")
        }
    }

    private fun readPacketIntoBuffer(timeoutMs: Int): Int {
        val got = readUsb(packetBuffer, 0, packetSize, timeoutMs)
        if (got > packetBuffer.size) {
            throw PtpTransportException("Bulk-IN packet ($got bytes) exceeded buffer (${packetBuffer.size})")
        }
        if (got > 0) {
            bufferStart = 0
            bufferEnd = got
        }
        return got
    }

    private fun drainBuffered(dest: ByteArray, offset: Int, maxCount: Int): Int {
        val available = bufferedCount
        if (available <= 0 || maxCount <= 0) {
            return 0
        }
        val toCopy = minOf(available, maxCount)
        System.arraycopy(packetBuffer, bufferStart, dest, offset, toCopy)
        bufferStart += toCopy
        if (bufferStart == bufferEnd) {
            bufferStart = 0
            bufferEnd = 0
        }
        return toCopy
    }

    private companion object {
        const val DEFAULT_READ_CHUNK_BYTES = 16 * 1024
        const val MAX_DRAIN_EMPTY_READS = 3
        const val MAX_ZERO_PACKETS = 2
    }
}
