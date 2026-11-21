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
            connection.bulkTransfer(bulkOut, ZERO_LENGTH_PACKET, 0, COMMAND_TIMEOUT_MS)
        }
    }

    override fun readHeader(): PtpContainerHeader {
        val header = ByteArray(PtpContainer.HEADER_BYTES)
        var filled = 0
        var zeroPackets = 0
        while (filled < header.size) {
            val read = connection.bulkTransfer(
                bulkIn,
                header,
                filled,
                header.size - filled,
                COMMAND_TIMEOUT_MS,
            )
            if (read > 0) {
                filled += read
                continue
            }
            if (read == 0 && zeroPackets < MAX_ZERO_PACKETS) {
                // A stray terminator from the previous transfer; the header follows it.
                zeroPackets++
                continue
            }
            throw PtpTransportException("Bulk-IN header read failed after $filled bytes ($read)")
        }
        return PtpContainerHeader.parse(header)
    }

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

    override fun resetDevice() {
        connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS,
            CLASS_DEVICE_RESET,
            0,
            0,
            EMPTY,
            0,
            COMMAND_TIMEOUT_MS,
        )
        // The reset should have cleared these already, but a halt that survives it would
        // break every later transfer, and clearing one costs a single control request.
        clearHalt(bulkOut)
        clearHalt(bulkIn)
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
        runCatching {
            connection.controlTransfer(
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
        }
    }

    /** Streams one data container body, one USB request at a time. */
    private inner class BulkBody(private var bytesLeft: Int) : PtpBody {
        override val remaining: Int get() = bytesLeft

        override fun read(dest: ByteArray, offset: Int, count: Int): Int {
            if (bytesLeft <= 0) {
                return 0
            }
            val request = minOf(count, bytesLeft, READ_CHUNK_BYTES)
            var zeroPackets = 0
            while (true) {
                val read = connection.bulkTransfer(bulkIn, dest, offset, request, DATA_TIMEOUT_MS)
                if (read > 0) {
                    bytesLeft -= read
                    return read
                }
                if (read == 0 && zeroPackets < MAX_ZERO_PACKETS) {
                    zeroPackets++
                    continue
                }
                throw PtpTransportException("Bulk-IN body read failed with $bytesLeft bytes pending ($read)")
            }
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** Buffer for a zero-length packet: the length passed is 0, not the buffer size. */
        private val ZERO_LENGTH_PACKET = ByteArray(1)

        private const val COMMAND_TIMEOUT_MS = 1_000
        private const val DATA_TIMEOUT_MS = 15_000
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val MAX_ZERO_PACKETS = 2
        private const val MAX_DATASET_BYTES = 16 * 1024 * 1024

        private const val CLASS_DEVICE_RESET = 0x66
        private const val CLEAR_FEATURE = 0x01
        private const val ENDPOINT_HALT = 0x00
        private const val RECIPIENT_ENDPOINT = 0x02
        private const val INTERFACE_CLASS_STILL_IMAGE = 6

        /**
         * Opens [device] and claims its PTP interface.
         *
         * @throws PtpTransportException when the device cannot be opened, exposes no usable
         *   interface, or refuses to be claimed
         */
        fun open(usbManager: UsbManager, device: UsbDevice): UsbPtpTransport {
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
