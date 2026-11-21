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
    /** Largest packet the bulk-IN endpoint accepts, used to size transfers. */
    val maxPacketSize: Int

    /**
     * Writes one container, terminating it with a zero-length packet when its length is a
     * multiple of [maxPacketSize].
     */
    fun writeContainer(bytes: ByteArray)

    /** Reads the next container header. */
    fun readHeader(): PtpContainerHeader

    /** Reads exactly [length] bytes of a data container body. */
    fun readBody(length: Int): ByteArray

    /** Opens a data container body for streaming, without buffering it. */
    fun openBody(length: Int): PtpBody

    /** Issues the class-specific device reset request (`ResetDevice`, 0x66). */
    fun resetDevice()

    /** Reads the class-specific device status (`GetDeviceStatus`, 0x67). */
    fun readDeviceStatus(): Int

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
