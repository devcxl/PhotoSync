package cn.devcxl.photosync.ptp.camera

import cn.devcxl.photosync.ptp.protocol.PtpContainer
import cn.devcxl.photosync.ptp.protocol.PtpContainerHeader
import cn.devcxl.photosync.ptp.protocol.PtpOp
import cn.devcxl.photosync.ptp.protocol.PtpProtocolException
import cn.devcxl.photosync.ptp.protocol.PtpSessionException
import cn.devcxl.photosync.ptp.protocol.PtpStatus
import cn.devcxl.photosync.ptp.transport.PtpBody
import cn.devcxl.photosync.ptp.transport.PtpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PtpSessionTest {

    @Test
    fun shouldOpenSession_andStartTransactionIdsAtOne() {
        val transport = ScriptedTransport(reply(PtpStatus.OK))

        val session = PtpSession(transport)
        session.open(supportsCloseSession = true)

        assertTrue(session.isOpen)
        assertEquals(1, session.transactionId)
        assertEquals(listOf(PtpOp.OPEN_SESSION), transport.commandCodes())
    }

    @Test
    fun shouldCloseSessionAndReopen_whenTheCameraAlreadyHoldsOne() {
        val transport = ScriptedTransport(
            reply(PtpStatus.SESSION_ALREADY_OPEN),
            reply(PtpStatus.OK),
            reply(PtpStatus.OK),
        )

        val session = PtpSession(transport)
        session.open(supportsCloseSession = true)

        assertTrue(session.isOpen)
        assertEquals(
            listOf(PtpOp.OPEN_SESSION, PtpOp.CLOSE_SESSION, PtpOp.OPEN_SESSION),
            transport.commandCodes(),
        )
        // The retry starts a fresh transaction sequence.
        assertEquals(1, session.transactionId)
    }

    @Test
    fun shouldAdoptTheExistingSession_whenTheCameraDoesNotSupportCloseSession() {
        val transport = ScriptedTransport(reply(PtpStatus.SESSION_ALREADY_OPEN))

        val session = PtpSession(transport)
        session.open(supportsCloseSession = false)

        assertTrue(session.isOpen)
        assertEquals(listOf(PtpOp.OPEN_SESSION), transport.commandCodes())
    }

    @Test
    fun shouldAdoptTheExistingSession_whenReopeningStillReportsItOpen() {
        val transport = ScriptedTransport(
            reply(PtpStatus.SESSION_ALREADY_OPEN),
            reply(PtpStatus.OK),
            reply(PtpStatus.SESSION_ALREADY_OPEN),
        )

        val session = PtpSession(transport)
        session.open(supportsCloseSession = true)

        assertTrue(session.isOpen)
    }

    @Test
    fun shouldAdoptTheExistingSession_whenCloseSessionIsRejected() {
        val transport = ScriptedTransport(
            reply(PtpStatus.SESSION_ALREADY_OPEN),
            reply(PtpStatus.INVALID_TRANSACTION_ID),
        )

        val session = PtpSession(transport)
        session.open(supportsCloseSession = true)

        assertTrue(session.isOpen)
        assertEquals(listOf(PtpOp.OPEN_SESSION, PtpOp.CLOSE_SESSION), transport.commandCodes())
    }

    @Test
    fun shouldAdoptTheExistingSession_whenCloseReportsNotOpen() {
        val transport = ScriptedTransport(
            reply(PtpStatus.SESSION_ALREADY_OPEN),
            reply(PtpStatus.SESSION_NOT_OPEN),
            reply(PtpStatus.OK),
        )

        val session = PtpSession(transport)
        session.open(supportsCloseSession = true)

        assertTrue(session.isOpen)
    }

    @Test
    fun shouldThrowSessionException_whenTheRetryIsRejected() {
        val transport = ScriptedTransport(
            reply(PtpStatus.SESSION_ALREADY_OPEN),
            reply(PtpStatus.OK),
            reply(PtpStatus.DEVICE_BUSY),
        )

        val session = PtpSession(transport)

        val error = assertThrows(PtpSessionException::class.java) {
            session.open(supportsCloseSession = true)
        }

        assertEquals(PtpStatus.DEVICE_BUSY, error.code)
        assertFalse(session.isOpen)
    }

    @Test
    fun shouldThrowSessionException_whenTheFirstOpenIsRejected() {
        val transport = ScriptedTransport(reply(PtpStatus.DEVICE_BUSY))

        assertThrows(PtpSessionException::class.java) {
            PtpSession(transport).open(supportsCloseSession = true)
        }
    }

    @Test
    fun shouldRefuseAnOperationThatNeedsASession_whileClosed() {
        val session = PtpSession(ScriptedTransport(reply(PtpStatus.OK)))

        assertThrows(IllegalStateException::class.java) {
            session.transact(PtpOp.GET_STORAGE_IDS)
        }
    }

    @Test
    fun shouldRejectACommandContainer_whenTheCameraSendsDataFirst() {
        val transport = ScriptedTransport(dataHeader(PtpOp.GET_DEVICE_INFO, 1, 4))

        assertThrows(PtpProtocolException::class.java) {
            PtpSession(transport).transactForData(PtpOp.GET_DEVICE_INFO)
        }
    }

    @Test
    fun shouldRejectDataPhase_whenOpcodeOrTransactionDoesNotMatch() {
        // A data container for a different transaction must not be accepted as the answer.
        val transport = ScriptedTransport(dataHeader(PtpOp.GET_DEVICE_INFO, 99, 4))

        assertThrows(PtpProtocolException::class.java) {
            PtpSession(transport).transactForData(PtpOp.GET_DEVICE_INFO)
        }
    }

    @Test
    fun shouldRejectStaleResponse_whenTransactionIdDoesNotMatch() {
        val session = PtpSession(ScriptedTransport(reply(PtpStatus.OK, transactionId = 99)))

        assertThrows(PtpProtocolException::class.java) {
            session.transactForData(PtpOp.GET_DEVICE_INFO)
        }
    }

    @Test
    fun shouldRejectStaleClosingResponse_whenDatasetWasReceived() {
        val transport = ScriptedTransport(
            dataHeader(PtpOp.GET_DEVICE_INFO, 0, 4),
            reply(PtpStatus.OK, transactionId = 99),
        )

        assertThrows(PtpProtocolException::class.java) {
            PtpSession(transport).transactForData(PtpOp.GET_DEVICE_INFO)
        }
    }

    @Test
    fun shouldRejectStaleClosingResponse_whenStreamFinishes() {
        val transport = ScriptedTransport(
            reply(PtpStatus.OK),
            dataHeader(PtpOp.GET_OBJECT, 1, 4),
            reply(PtpStatus.OK, transactionId = 99),
        )
        val session = PtpSession(transport)
        session.open(supportsCloseSession = true)
        session.beginData(PtpOp.GET_OBJECT, 7)

        assertThrows(PtpProtocolException::class.java) {
            session.finishData()
        }
    }

    @Test
    fun shouldClearTransactionId_whenResponseCompletes() {
        val session = PtpSession(
            ScriptedTransport(reply(PtpStatus.OK), reply(PtpStatus.OK, transactionId = 1))
        )
        assertNull(session.inFlightTransactionId)
        session.open(supportsCloseSession = true)
        assertNull(session.inFlightTransactionId)

        session.transact(PtpOp.GET_STORAGE_IDS)
        assertNull(session.inFlightTransactionId)
    }

    @Test
    fun shouldKeepTransactionIdForRecovery_whenResponseIsIncomplete() {
        val session = PtpSession(ScriptedTransport(dataHeader(PtpOp.GET_DEVICE_INFO, 99, 4)))
        assertThrows(PtpProtocolException::class.java) {
            session.transactForData(PtpOp.GET_DEVICE_INFO)
        }
        assertEquals(0, session.inFlightTransactionId)
        session.abandon()
        assertNull(session.inFlightTransactionId)
    }

    private fun reply(code: Int, params: IntArray = IntArray(0), transactionId: Int = 0): ByteArray {
        val length = PtpContainer.HEADER_BYTES + params.size * 4
        val out = ByteArrayOutputStream()
        out.write(u32(length))
        out.write(u16(PtpContainer.TYPE_RESPONSE))
        out.write(u16(code))
        out.write(u32(transactionId))
        params.forEach { out.write(u32(it)) }
        return out.toByteArray()
    }

    private fun dataHeader(opcode: Int, transactionId: Int, payloadLength: Int): ByteArray {
        val length = PtpContainer.HEADER_BYTES + payloadLength
        val out = ByteArrayOutputStream()
        out.write(u32(length))
        out.write(u16(PtpContainer.TYPE_DATA))
        out.write(u16(opcode))
        out.write(u32(transactionId))
        return out.toByteArray()
    }

    private fun u16(value: Int) = byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())

    private fun u32(value: Int) = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    /** Feeds pre-scripted responses, recording the commands it was sent. */
    private class ScriptedTransport(vararg responses: ByteArray) : PtpTransport {
        private val pending = responses.toMutableList()
        private val commands = mutableListOf<ByteArray>()

        override fun writeContainer(bytes: ByteArray) {
            commands += bytes
        }

        override fun readHeader(): PtpContainerHeader = PtpContainerHeader.parse(pending.removeAt(0))

        override fun readBody(length: Int): ByteArray = ByteArray(length)

        override fun openBody(length: Int): PtpBody = object : PtpBody {
            override val remaining: Int = length
            override fun read(dest: ByteArray, offset: Int, count: Int): Int = count
        }

        override fun resetDevice() = Unit

        override fun close() = Unit

        fun commandCodes(): List<Int> =
            commands.map { PtpContainerHeader.parse(it).code }
    }
}
