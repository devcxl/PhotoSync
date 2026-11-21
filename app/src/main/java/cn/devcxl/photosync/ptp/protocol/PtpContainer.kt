package cn.devcxl.photosync.ptp.protocol

/**
 * The 12-byte header every PTP container starts with.
 *
 * @property length total container length in bytes, header included
 * @property type one of [PtpContainer.TYPE_COMMAND] and friends
 * @property code operation, response or event code depending on [type]
 * @property transactionId the transaction this container belongs to; 0 for events
 */
internal data class PtpContainerHeader(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Int,
) {
    companion object {
        fun parse(bytes: ByteArray): PtpContainerHeader {
            if (bytes.size < PtpContainer.HEADER_BYTES) {
                throw PtpProtocolException("Header needs ${PtpContainer.HEADER_BYTES} bytes, got ${bytes.size}")
            }
            val reader = PtpByteReader(bytes, end = PtpContainer.HEADER_BYTES)
            val length = reader.u32()
            if (length < PtpContainer.HEADER_BYTES) {
                throw PtpProtocolException("Container length $length is shorter than a header")
            }
            return PtpContainerHeader(length, reader.u16(), reader.u16(), reader.u32())
        }
    }
}

/** A response container, as returned by the camera at the end of a transaction. */
internal data class PtpResponsePacket(
    val code: Int,
    val transactionId: Int,
    val params: IntArray,
) {
    val isOk: Boolean get() = code == PtpStatus.OK

    companion object {
        /**
         * Parses a complete response container.
         *
         * @param bytes header and parameters; must be exactly [PtpContainerHeader.length]
         *   bytes long, because a partially received response cannot be interpreted
         */
        fun parse(bytes: ByteArray): PtpResponsePacket {
            val header = PtpContainerHeader.parse(bytes)
            if (bytes.size != header.length) {
                throw PtpProtocolException(
                    "Response declares ${header.length} bytes but ${bytes.size} arrived"
                )
            }
            val params = if (header.length > PtpContainer.HEADER_BYTES) {
                val reader = PtpByteReader(bytes, PtpContainer.HEADER_BYTES)
                IntArray((header.length - PtpContainer.HEADER_BYTES) / 4) { reader.u32() }
            } else {
                IntArray(0)
            }
            return PtpResponsePacket(header.code, header.transactionId, params)
        }
    }
}

/** Builders and constants for the container framing defined by PIMA 15740 section 9. */
internal object PtpContainer {
    const val HEADER_BYTES = 12

    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4

    /**
     * Builds a command container.
     *
     * @param opcode operation code
     * @param transactionId transaction this command opens
     * @param params up to five 32-bit parameters, in the order the specification defines
     */
    fun command(opcode: Int, transactionId: Int, params: IntArray = IntArray(0)): ByteArray {
        if (params.size > 5) {
            throw IllegalArgumentException("A command carries at most 5 parameters, got ${params.size}")
        }
        val length = HEADER_BYTES + params.size * 4
        val writer = PtpByteWriter(length)
        writer.u32(length)
        writer.u16(TYPE_COMMAND)
        writer.u16(opcode)
        writer.u32(transactionId)
        for (param in params) {
            writer.u32(param)
        }
        return writer.build()
    }

    /** Builds the header of a data container; the payload follows it unchanged. */
    fun dataHeader(payloadLength: Int, opcode: Int, transactionId: Int): ByteArray {
        val writer = PtpByteWriter(HEADER_BYTES)
        writer.u32(HEADER_BYTES + payloadLength)
        writer.u16(TYPE_DATA)
        writer.u16(opcode)
        writer.u32(transactionId)
        return writer.build()
    }
}
