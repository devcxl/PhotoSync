package cn.devcxl.photosync.ptp.transport

import cn.devcxl.photosync.ptp.protocol.PtpContainer
import cn.devcxl.photosync.ptp.protocol.PtpContainerHeader
import cn.devcxl.photosync.ptp.protocol.PtpOp
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.protocol.PtpTransportException

/**
 * Deterministic USB Still Image Capture device that drives the production reconnect path.
 *
 * Facts encoded from the specifications:
 * - Cancel Request (0x64) data carries CancellationCode 0x4001 followed by the PIMA
 *   TransactionID of the transaction being cancelled (USB SIC 1.0 section 5.2.1.2).
 *   PIMA 15740 section 9.3.1 reserves 0xFFFFFFFF; it is not a valid TransactionID.
 * - After the host stops issuing Bulk-IN tokens and sends Cancel, the device reports
 *   Device_Busy (0x2019) on Get_Device_Status (0x67) until cancellation is complete,
 *   then OK; the host may resume commands only after OK (USB SIC 1.0 section 7.2.1.1).
 * - A cancelled transaction sends no response container on Bulk-IN.
 *
 * Reproduction choices, not claims about every camera:
 * - [ReplyPlan.AbandonedTransfer] models the new host's own GetDeviceInfo timing out: the
 *   first Bulk-IN read times out, the device keeps streaming, and retries read a 12-byte tail
 *   whose type field is 25088 (0x6200). This plan does not model the previous process's
 *   transaction; it only proves recovery when the host can name its own command (tid 0).
 *   DeviceReset (0x66) alone does not stop that stream in this model; Cancel with the known
 *   transaction id plus Get_Device_Status OK does.
 * - [ReplyPlan.UnknownOldTransactionResetRecovers] and
 *   [ReplyPlan.UnknownOldTransactionResetNeverWorks] model an old transaction whose id the
 *   new host never learns. Reset either clears it or never does; the host must not invent an
 *   id for it. Both plans assume the old process is dead and no second host is issuing Bulk
 *   traffic (single-host concurrency premise).
 * - The only transaction id the reconnecting host can name is the one it issued itself:
 *   GetDeviceInfo is transaction 0 while the PTP session is closed. The killed process's
 *   transaction id is unknown and is never exposed as a value the host could reuse.
 * - [cancelAndResetDevice] receives the transaction id from the host and feeds it through this
 *   model's own Cancel/status state machine. It does not read production private fields; the
 *   byte-level Cancel codec is covered by the independent PtpRecoveryControl tests.
 * - The stale tail, the "ResetDevice does not abort" behavior, the reset outcomes and the
 *   exact bulk timing are reproduction fixtures. They are not evidence that the camera's old
 *   transaction was 0, that reset always works or fails, or that every Canon R100 behaves so.
 */
internal class FakeStillImageDevice(
    private val plan: ReplyPlan,
    private val deviceInfoPayload: ByteArray,
) : PtpTransport {

    internal sealed interface ReplyPlan {
        /**
         * The new host's own GetDeviceInfo (tid 0) times out; its own command is the
         * abandoned transfer. No previous-process transaction is modeled.
         */
        data object AbandonedTransfer : ReplyPlan

        /** First GetDeviceInfo answers PTP Device_Busy; the next one succeeds. */
        data object DeviceBusyThenOk : ReplyPlan

        /** Every GetDeviceInfo is rejected with [code]. */
        data class RejectAlways(val code: Int) : ReplyPlan

        /**
         * A previous process left a transaction whose id the host does not know, and
         * ResetDevice happens to clear it. GetDeviceInfo then succeeds without a Cancel.
         */
        data object UnknownOldTransactionResetRecovers : ReplyPlan

        /**
         * A previous process left a transaction whose id the host does not know, and
         * ResetDevice never clears it. Every GetDeviceInfo answers Device_Busy.
         */
        data object UnknownOldTransactionResetNeverWorks : ReplyPlan
    }

    internal data class Command(
        val opcode: Int,
        val transactionId: Int,
        val params: List<Int>,
    )

    internal data class Response(val code: Int, val transactionId: Int)

    internal data class CancelRequest(val cancellationCode: Int, val transactionId: Int)

    val commands = mutableListOf<Command>()
    val responses = mutableListOf<Response>()
    val cancelRequests = mutableListOf<CancelRequest>()
    val staleHeaderTypes = mutableListOf<Int>()

    /** Transaction ids passed to [cancelAndResetDevice], including null for "unknown". */
    val recoveryRequests = mutableListOf<Int?>()

    var deviceResets = 0
        private set
    var drainCalls = 0
        private set
    var deviceStatusPolls = 0
        private set
    var lastDeviceStatus = 0
        private set
    var headerReadTimeouts = 0
        private set
    var closed = false
        private set
    var getDeviceInfoCommands = 0
        private set
    var abandonedStreamActive = false
        private set
    var awaitingDeviceStatus = false
        private set
    var lastCancelPayload: ByteArray? = null
        private set

    /**
     * Device-internal state for a transaction left by a previous process.
     *
     * Its id is never exposed to the host; the tests use [recoveryRequests] and
     * [cancelRequests] to prove no guessed id was sent for it.
     */
    var oldTransactionActive = false
        private set

    private val inPipe = ArrayDeque<Byte>()
    private val staleChunks = ArrayDeque<ByteArray>()
    private var activeTransaction: Int? = null
    private var timeoutNextHeaderRead = false
    private var busyStatusPollsRemaining = 0

    init {
        if (plan == ReplyPlan.UnknownOldTransactionResetRecovers ||
            plan == ReplyPlan.UnknownOldTransactionResetNeverWorks
        ) {
            oldTransactionActive = true
            activeTransaction = OLD_UNKNOWN_TRANSACTION_ID
        }
    }

    override fun writeContainer(bytes: ByteArray) {
        val header = PtpContainerHeader.parse(bytes)
        val params = (PtpContainer.HEADER_BYTES until bytes.size step 4).map { offset ->
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
        }
        commands += Command(header.code, header.transactionId, params)
        onCommand(header.code, header.transactionId)
    }

    private fun onCommand(opcode: Int, transactionId: Int) {
        if (opcode == PtpOp.GET_DEVICE_INFO) getDeviceInfoCommands++
        if (awaitingDeviceStatus) {
            enqueueResponse(PtpStatus.DEVICE_BUSY, transactionId)
            return
        }
        when (val current = plan) {
            ReplyPlan.AbandonedTransfer -> onAbandonedCommand(opcode, transactionId)
            ReplyPlan.DeviceBusyThenOk -> {
                if (opcode == PtpOp.GET_DEVICE_INFO) {
                    if (getDeviceInfoCommands == 1) {
                        enqueueResponse(PtpStatus.DEVICE_BUSY, transactionId)
                    } else {
                        enqueueDeviceInfo(transactionId)
                    }
                }
            }
            is ReplyPlan.RejectAlways -> {
                if (opcode == PtpOp.GET_DEVICE_INFO) enqueueResponse(current.code, transactionId)
            }
            ReplyPlan.UnknownOldTransactionResetRecovers,
            ReplyPlan.UnknownOldTransactionResetNeverWorks -> {
                if (oldTransactionActive) {
                    // The old transaction id is unknown to the host; the new command cannot
                    // proceed until the device is out of that state.
                    enqueueResponse(PtpStatus.DEVICE_BUSY, transactionId)
                } else if (opcode == PtpOp.GET_DEVICE_INFO) {
                    enqueueDeviceInfo(transactionId)
                }
            }
        }
    }

    private fun onAbandonedCommand(opcode: Int, transactionId: Int) {
        if (abandonedStreamActive) {
            ensureStaleChunk()
            return // the device has not finished the previous transfer
        }
        if (opcode != PtpOp.GET_DEVICE_INFO) return
        if (getDeviceInfoCommands == 1) {
            // This is the new host's own transaction id (0), not the previous process's id.
            // Its response is not ready within the header timeout; the abandoned stream drains.
            activeTransaction = transactionId
            abandonedStreamActive = true
            ensureStaleChunk()
            timeoutNextHeaderRead = true
        } else {
            enqueueDeviceInfo(transactionId)
        }
    }

    override fun readHeader(): PtpContainerHeader {
        if (timeoutNextHeaderRead) {
            timeoutNextHeaderRead = false
            headerReadTimeouts++
            throw PtpTransportException("Bulk-IN header read timed out (simulated)")
        }
        val stale = staleChunks.removeFirstOrNull()
        if (stale != null) {
            val header = PtpContainerHeader.parse(stale)
            staleHeaderTypes += header.type
            return header
        }
        if (inPipe.size < PtpContainer.HEADER_BYTES) {
            headerReadTimeouts++
            throw PtpTransportException("Bulk-IN header read returned no header")
        }
        return PtpContainerHeader.parse(take(PtpContainer.HEADER_BYTES))
    }

    override fun readBody(length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        if (inPipe.size < length) {
            throw PtpTransportException("Bulk-IN body short: $length requested, ${inPipe.size} queued")
        }
        return take(length)
    }

    override fun openBody(length: Int): PtpBody = object : PtpBody {
        private var remainingBytes = length

        override val remaining: Int get() = remainingBytes

        override fun read(dest: ByteArray, offset: Int, count: Int): Int {
            if (remainingBytes <= 0) return 0
            val toRead = minOf(count, remainingBytes, inPipe.size)
            if (toRead <= 0) throw PtpTransportException("Bulk-IN body ended early")
            take(toRead).copyInto(dest, offset)
            remainingBytes -= toRead
            return toRead
        }
    }

    override fun drainPendingInput(): Int {
        drainCalls++
        var drained = inPipe.size
        inPipe.clear()
        if (abandonedStreamActive) {
            drained += staleChunks.removeFirstOrNull()?.size ?: 0
            // The abandoned transfer keeps producing data; a drain alone cannot end it.
            ensureStaleChunk()
        }
        return drained
    }

    override fun resetDevice() {
        drainPendingInput()
        deviceResets++
        if (plan == ReplyPlan.UnknownOldTransactionResetRecovers) {
            clearOldTransaction()
        }
        // AbandonedTransfer and UnknownOldTransactionResetNeverWorks: reset does not abort.
        drainPendingInput()
    }

    override fun cancelAndResetDevice(transactionId: Int?) {
        recoveryRequests += transactionId
        drainPendingInput()
        lastCancelPayload = transactionId?.let(::encodeCancelPayload)
        if (transactionId != null) {
            sendCancelRequest(transactionId)
            pollStatusUntilReady()
        }
        drainPendingInput()
        deviceResets++
        if (plan == ReplyPlan.UnknownOldTransactionResetRecovers) {
            clearOldTransaction()
        }
        drainPendingInput()
    }

    /** Models the one reset outcome that clears a transaction left by a dead process. */
    private fun clearOldTransaction() {
        oldTransactionActive = false
        activeTransaction = null
        abandonedStreamActive = false
        staleChunks.clear()
        awaitingDeviceStatus = false
        timeoutNextHeaderRead = false
    }

    /** Host-side SIC Cancel Request (0x64), used by the reference-host conformance check. */
    fun sendCancelRequest(transactionId: Int) {
        recordCancel(CANCELLATION_CODE, transactionId)
    }

    private fun recordCancel(cancellationCode: Int, transactionId: Int) {
        cancelRequests += CancelRequest(cancellationCode, transactionId)
        val active = activeTransaction ?: return
        if (cancellationCode != CANCELLATION_CODE) return
        if (transactionId == UNKNOWN_TRANSACTION_ID) return
        if (transactionId != active) return
        abandonedStreamActive = false
        staleChunks.clear()
        activeTransaction = null
        awaitingDeviceStatus = true
        busyStatusPollsRemaining = 1
    }

    /**
     * Polls SIC Get_Device_Status (0x67) until the device leaves the busy state.
     *
     * The poll is bounded so a device that never reports OK cannot hang the test. The result
     * is visible through [deviceStatusPolls], [lastDeviceStatus] and [awaitingDeviceStatus].
     */
    private fun pollStatusUntilReady() {
        var polls = 0
        while (awaitingDeviceStatus && polls < MAX_STATUS_POLLS) {
            pollDeviceStatus()
            polls++
        }
    }

    /**
     * Host-side SIC Get_Device_Status (0x67).
     *
     * @return the reported PTP response code; [PtpStatus.DEVICE_BUSY] until the cancel has
     *   been processed, then [PtpStatus.OK]
     */
    fun pollDeviceStatus(): Int {
        deviceStatusPolls++
        val code = when {
            !awaitingDeviceStatus -> PtpStatus.OK
            busyStatusPollsRemaining > 0 -> {
                busyStatusPollsRemaining--
                PtpStatus.DEVICE_BUSY
            }
            else -> {
                awaitingDeviceStatus = false
                PtpStatus.OK
            }
        }
        lastDeviceStatus = code
        return code
    }

    override fun close() {
        closed = true
        inPipe.clear()
        staleChunks.clear()
    }

    fun renderTrace(): String = renderDeviceTrace()

    private fun enqueueDeviceInfo(transactionId: Int) {
        enqueueData(PtpOp.GET_DEVICE_INFO, transactionId, deviceInfoPayload)
        enqueueResponse(PtpStatus.OK, transactionId)
    }

    private fun enqueueData(opcode: Int, transactionId: Int, payload: ByteArray) {
        writeAll(PtpContainer.dataHeader(payload.size, opcode, transactionId))
        writeAll(payload)
    }

    private fun enqueueResponse(code: Int, transactionId: Int) {
        responses += Response(code, transactionId)
        val bytes = ByteArray(PtpContainer.HEADER_BYTES)
        putI32(bytes, 0, PtpContainer.HEADER_BYTES)
        putU16(bytes, 4, PtpContainer.TYPE_RESPONSE)
        putU16(bytes, 6, code)
        putI32(bytes, 8, transactionId)
        writeAll(bytes)
    }

    private fun ensureStaleChunk() {
        if (staleChunks.isEmpty()) staleChunks.addLast(STALE_TAIL_CHUNK.copyOf())
    }

    /** Device-side view of the SIC cancel payload built from the supplied transaction id. */
    private fun encodeCancelPayload(transactionId: Int): ByteArray =
        ByteArray(CANCEL_PAYLOAD_BYTES).also { payload ->
            putU16(payload, 0, CANCELLATION_CODE)
            putI32(payload, 2, transactionId)
        }

    private fun writeAll(bytes: ByteArray) {
        for (byte in bytes) inPipe.addLast(byte)
    }

    private fun take(count: Int): ByteArray = ByteArray(count) { inPipe.removeFirst() }

    private companion object {
        const val CANCELLATION_CODE = 0x4001
        const val CANCEL_PAYLOAD_BYTES = 6
        const val MAX_STATUS_POLLS = 16

        /** PIMA 15740 section 9.3.1 reserves this value; it cannot name a transaction. */
        const val UNKNOWN_TRANSACTION_ID = -1

        /**
         * Device-internal id of the previous process's transaction. It is never exposed to
         * the host; a host sending this value would be guessing.
         */
        const val OLD_UNKNOWN_TRANSACTION_ID = 7

        /**
         * Twelve-byte tail whose bytes 4..5 are 0x00 0x62, i.e. container type 25088.
         *
         * This is the value the field log reported after the first Bulk-IN timeout. It is a
         * fixture for that symptom, not a byte-for-byte capture of a camera payload.
         */
        val STALE_TAIL_CHUNK = byteArrayOf(
            0x0C, 0x00, 0x00, 0x00,
            0x00, 0x62,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
    }
}

/** Human-readable command trace used in assertion messages and characterization output. */
private fun FakeStillImageDevice.renderDeviceTrace(): String = buildString {
    appendLine("Fake SIC device trace:")
    appendLine("  PTP commands:")
    commands.forEachIndexed { index, command ->
        appendLine(
            "    ${index + 1}. ${opName(command.opcode)} tid=${command.transactionId} " +
                "params=${command.params}"
        )
    }
    appendLine("  PTP responses:")
    responses.forEachIndexed { index, response ->
        appendLine(
            "    ${index + 1}. ${PtpStatus.describe(response.code)} " +
                "(0x%04x) tid=${response.transactionId}".format(response.code)
        )
    }
    appendLine("  Cancel Request (0x64): ${cancelRequests.size}")
    cancelRequests.forEach { request ->
        appendLine(
            "    cancellationCode=0x%04x transactionId=0x%08x"
                .format(request.cancellationCode, request.transactionId)
        )
    }
    appendLine("  cancelAndResetDevice calls: ${recoveryRequests.size}")
    recoveryRequests.forEach { transactionId ->
        appendLine(
            "    requested transactionId=" +
                (transactionId?.let { "0x%08x".format(it) } ?: "<null>")
        )
    }
    appendLine(
        "  last device-side Cancel payload: " +
            (lastCancelPayload?.joinToString(" ") { "0x%02x".format(it.toInt() and 0xff) } ?: "none")
    )
    appendLine("  DeviceReset (0x66): $deviceResets")
    appendLine(
        "  Get_Device_Status (0x67) polls: $deviceStatusPolls " +
            "(last=${PtpStatus.describe(lastDeviceStatus)})"
    )
    appendLine("  Bulk-IN header timeouts: $headerReadTimeouts")
    appendLine("  stale headers served: ${staleHeaderTypes.size}, types=$staleHeaderTypes")
    appendLine("  drain calls: $drainCalls")
    appendLine("  awaiting Device_Status: $awaitingDeviceStatus")
    appendLine("  old process transaction still active: $oldTransactionActive")
    appendLine("  closed: $closed")
}

private fun opName(opcode: Int): String = when (opcode) {
    PtpOp.GET_DEVICE_INFO -> "GetDeviceInfo(0x1001)"
    PtpOp.OPEN_SESSION -> "OpenSession(0x1002)"
    PtpOp.CLOSE_SESSION -> "CloseSession(0x1003)"
    else -> "0x%04x".format(opcode)
}

private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xff).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
}

private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xff).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
    bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
    bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
}
