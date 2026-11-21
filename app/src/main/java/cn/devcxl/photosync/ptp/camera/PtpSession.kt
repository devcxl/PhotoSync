package cn.devcxl.photosync.ptp.camera

import cn.devcxl.photosync.ptp.protocol.PtpContainer
import cn.devcxl.photosync.ptp.protocol.PtpContainerHeader
import cn.devcxl.photosync.ptp.protocol.PtpDeviceException
import cn.devcxl.photosync.ptp.protocol.PtpOp
import cn.devcxl.photosync.ptp.protocol.PtpProtocolException
import cn.devcxl.photosync.ptp.protocol.PtpResponsePacket
import cn.devcxl.photosync.ptp.protocol.PtpSessionException
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.transport.PtpBody
import cn.devcxl.photosync.ptp.transport.PtpTransport

/**
 * One PTP session on top of a transport: transaction ids, open/close recovery, and the
 * command → data → response exchange every operation follows.
 *
 * A session is single-threaded by contract; [cn.devcxl.photosync.ptp.camera.PtpCamera]
 * serializes access so the transaction id always matches the container on the wire.
 */
internal class PtpSession(private val transport: PtpTransport) {

    var isOpen: Boolean = false
        private set

    /** Next transaction id, or 0 while the session is closed. */
    var transactionId: Int = 0
        private set

    /**
     * Opens the session, recovering from a camera that still holds one.
     *
     * Android's MTP stack or a previous host can leave a session open, in which case the
     * camera answers `SessionAlreadyOpen`. Closing it first resynchronizes transaction ids;
     * when the camera does not implement `CloseSession`, or still refuses afterwards, the
     * existing session is adopted so the transfer can proceed.
     *
     * @param supportsCloseSession whether the camera advertises `CloseSession`
     * @throws PtpSessionException when the camera rejects both the open and the recovery
     */
    fun open(supportsCloseSession: Boolean) {
        val opened = exchange(PtpOp.OPEN_SESSION, intArrayOf(NEW_SESSION_ID), allowClosed = true)
        when (opened.code) {
            PtpStatus.OK -> {
                activate()
                return
            }
            PtpStatus.SESSION_ALREADY_OPEN -> {
                recoverFromOpenSession(supportsCloseSession)
                return
            }
            else -> throw PtpSessionException(opened.code, "OpenSession rejected")
        }
    }

    /**
     * Closes the camera session.
     *
     * `SessionNotOpen` counts as success: the goal is "no session", not "close reported OK".
     */
    fun closeSession() {
        if (!isOpen) {
            return
        }
        val response = exchange(PtpOp.CLOSE_SESSION, IntArray(0), allowClosed = true)
        isOpen = false
        if (response.code != PtpStatus.OK && response.code != PtpStatus.SESSION_NOT_OPEN) {
            throw PtpDeviceException(response.code, "CloseSession rejected")
        }
    }

    /** Marks the session closed without talking to the camera, for teardown paths. */
    fun abandon() {
        isOpen = false
        transactionId = 0
    }

    /**
     * Runs an operation with no data phase.
     *
     * @throws IllegalStateException when the session is closed, except for the operations
     *   that are legal before `OpenSession`
     */
    fun transact(opcode: Int, vararg params: Int): PtpResponsePacket =
        exchange(opcode, params, allowClosed = opcode == PtpOp.GET_DEVICE_INFO)

    /** Runs an operation whose answer is a dataset, and returns the dataset bytes. */
    fun transactForData(opcode: Int, vararg params: Int): PtpDataExchange {
        checkOpen(opcode, allowClosed = opcode == PtpOp.GET_DEVICE_INFO)
        val tid = nextTransactionId()
        transport.writeContainer(PtpContainer.command(opcode, tid, params))
        val header = transport.readHeader()
        if (header.type == PtpContainer.TYPE_RESPONSE) {
            val response = readResponse(header)
            return PtpDataExchange(response, null)
        }
        requireDataHeader(header, opcode, tid)
        val body = transport.readBody(header.length - PtpContainer.HEADER_BYTES)
        return PtpDataExchange(readResponseFromWire(), body)
    }

    /**
     * Sends an operation whose data phase is streamed by the caller.
     *
     * The caller must finish the exchange with [finishData] once the body is consumed, and
     * must not start another operation before that: the response is still in the pipe.
     */
    fun beginData(opcode: Int, vararg params: Int): PtpDataStart {
        checkOpen(opcode, allowClosed = false)
        val tid = nextTransactionId()
        transport.writeContainer(PtpContainer.command(opcode, tid, params))
        val header = transport.readHeader()
        if (header.type == PtpContainer.TYPE_RESPONSE) {
            return PtpDataStart.Rejected(readResponse(header))
        }
        requireDataHeader(header, opcode, tid)
        return PtpDataStart.Streaming(
            body = transport.openBody(header.length - PtpContainer.HEADER_BYTES),
            declaredLength = header.length - PtpContainer.HEADER_BYTES,
        )
    }

    /** Reads the response that terminates an exchange started by [beginData]. */
    fun finishData(): PtpResponsePacket = readResponseFromWire()

    private fun recoverFromOpenSession(supportsCloseSession: Boolean) {
        if (!supportsCloseSession) {
            activate()
            return
        }
        val closed = exchange(PtpOp.CLOSE_SESSION, IntArray(0), allowClosed = true)
        if (closed.code != PtpStatus.OK && closed.code != PtpStatus.SESSION_NOT_OPEN) {
            activate()
            return
        }
        transactionId = 0
        val retried = exchange(PtpOp.OPEN_SESSION, intArrayOf(NEW_SESSION_ID), allowClosed = true)
        when (retried.code) {
            PtpStatus.OK, PtpStatus.SESSION_ALREADY_OPEN -> activate()
            else -> throw PtpSessionException(retried.code, "OpenSession retry rejected")
        }
    }

    private fun activate() {
        isOpen = true
        transactionId = FIRST_TRANSACTION_ID
    }

    private fun exchange(opcode: Int, params: IntArray, allowClosed: Boolean): PtpResponsePacket {
        checkOpen(opcode, allowClosed)
        val tid = nextTransactionId()
        transport.writeContainer(PtpContainer.command(opcode, tid, params))
        val header = transport.readHeader()
        if (header.type != PtpContainer.TYPE_RESPONSE) {
            throw PtpProtocolException(
                "Expected a response to 0x%04x, got container type ${header.type}".format(opcode)
            )
        }
        return readResponse(header)
    }

    private fun readResponse(header: PtpContainerHeader): PtpResponsePacket {
        val payloadLength = header.length - PtpContainer.HEADER_BYTES
        val bytes = transport.readBody(payloadLength)
        return PtpResponsePacket(
            code = header.code,
            transactionId = header.transactionId,
            params = IntArray(payloadLength / 4) { index ->
                (bytes[index * 4].toInt() and 0xff) or
                    ((bytes[index * 4 + 1].toInt() and 0xff) shl 8) or
                    ((bytes[index * 4 + 2].toInt() and 0xff) shl 16) or
                    ((bytes[index * 4 + 3].toInt() and 0xff) shl 24)
            },
        )
    }

    private fun readResponseFromWire(): PtpResponsePacket {
        val header = transport.readHeader()
        if (header.type != PtpContainer.TYPE_RESPONSE) {
            throw PtpProtocolException("Expected the closing response, got container type ${header.type}")
        }
        return readResponse(header)
    }

    private fun requireDataHeader(header: PtpContainerHeader, opcode: Int, tid: Int) {
        if (header.type != PtpContainer.TYPE_DATA) {
            throw PtpProtocolException(
                "Expected a data container for 0x%04x, got type ${header.type}".format(opcode)
            )
        }
        if (header.code != opcode || header.transactionId != tid) {
            throw PtpProtocolException(
                "Data container mismatch: code 0x%04x tid %d, expected 0x%04x tid %d"
                    .format(header.code, header.transactionId, opcode, tid)
            )
        }
    }

    private fun checkOpen(opcode: Int, allowClosed: Boolean) {
        if (isOpen || allowClosed) {
            return
        }
        throw IllegalStateException(
            "No PTP session is open; 0x%04x needs one".format(opcode)
        )
    }

    private fun nextTransactionId(): Int {
        if (!isOpen) {
            return CLOSED_TRANSACTION_ID
        }
        return transactionId++
    }

    private companion object {
        const val NEW_SESSION_ID = 1
        const val FIRST_TRANSACTION_ID = 1
        const val CLOSED_TRANSACTION_ID = 0
    }
}

/** Result of an operation that returns a dataset. */
internal data class PtpDataExchange(
    val response: PtpResponsePacket,
    val data: ByteArray?,
)

/** How a streamed data phase started. */
internal sealed interface PtpDataStart {
    /** The camera is streaming the body; [body] reads it. */
    data class Streaming(val body: PtpBody, val declaredLength: Int) : PtpDataStart

    /** The camera answered without sending data, typically with an error code. */
    data class Rejected(val response: PtpResponsePacket) : PtpDataStart
}
