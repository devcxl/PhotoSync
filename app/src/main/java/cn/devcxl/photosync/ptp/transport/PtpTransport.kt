package cn.devcxl.photosync.ptp.transport

import cn.devcxl.photosync.ptp.protocol.PtpContainerHeader

/**
 * Byte-level access to a PTP camera.
 *
 * The protocol layer only needs to move containers in and out; everything above this
 * interface is testable on the JVM with a fake implementation.
 *
 * Implementations are not thread-safe: one session at a time owns the device.
 */
internal interface PtpTransport {
    /**
     * Writes one container, terminating it with a zero-length packet when its length is a
     * multiple of the bulk-OUT endpoint's packet size.
     *
     * The two directions can advertise different packet sizes, and the host is the side that
     * must terminate, so the OUT size is the one that decides.
     */
    fun writeContainer(bytes: ByteArray)

    /** Reads the next container header. */
    fun readHeader(): PtpContainerHeader

    /** Reads exactly [length] bytes of a data container body. */
    fun readBody(length: Int): ByteArray

    /** Opens a data container body for streaming, without buffering it. */
    fun openBody(length: Int): PtpBody

    /**
     * Issues the class-specific device reset (`ResetDevice`, 0x66) and clears any halt left
     * on the bulk endpoints by an earlier session.
     *
     * A halted bulk endpoint fails every transfer, and nothing else in this layer would
     * recover from it.
     */
    fun resetDevice()

    /** Releases the interface and closes the USB connection. */
    fun close()
}

/** A data container body being streamed out of the camera. */
internal interface PtpBody {
    val remaining: Int

    /**
     * Reads up to [count] bytes into [dest].
     *
     * @return the number of bytes read, always positive
     * @throws cn.devcxl.photosync.ptp.protocol.PtpTransportException on a USB failure or
     *   when the camera stops sending before the declared length is reached
     */
    fun read(dest: ByteArray, offset: Int, count: Int): Int
}
