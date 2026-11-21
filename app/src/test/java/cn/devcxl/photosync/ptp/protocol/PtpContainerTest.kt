package cn.devcxl.photosync.ptp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpContainerTest {

    @Test
    fun shouldFrameCommandHeaderWithLengthAndParameters() {
        val bytes = PtpContainer.command(PtpOp.GET_OBJECT_HANDLES, transactionId = 7, params = intArrayOf(1, 2, 3))

        val header = PtpContainerHeader.parse(bytes)

        assertEquals(12 + 12, header.length)
        assertEquals(PtpContainer.TYPE_COMMAND, header.type)
        assertEquals(PtpOp.GET_OBJECT_HANDLES, header.code)
        assertEquals(7, header.transactionId)
    }

    @Test
    fun shouldDeclarePayloadInDataHeader() {
        val header = PtpContainerHeader.parse(PtpContainer.dataHeader(1024, PtpOp.GET_OBJECT, 3))

        assertEquals(1024 + 12, header.length)
        assertEquals(PtpContainer.TYPE_DATA, header.type)
        assertEquals(3, header.transactionId)
    }

    @Test
    fun shouldRejectMoreThanFiveParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            PtpContainer.command(PtpOp.GET_OBJECT_HANDLES, 1, IntArray(6))
        }
    }

    @Test
    fun shouldRejectHeader_whenLengthIsShorterThanTheHeaderItself() {
        val bogus = PtpContainer.command(PtpOp.OPEN_SESSION, 1).copyOf()
        bogus[0] = 0x04

        assertThrows(PtpProtocolException::class.java) { PtpContainerHeader.parse(bogus) }
    }

    @Test
    fun shouldParseResponseParameters() {
        val bytes = PtpContainer.command(PtpOp.DELETE_OBJECT, 9, intArrayOf(0x1234)).also {
            it[5] = PtpContainer.TYPE_RESPONSE.toByte()
            it[4] = 0
            it[6] = PtpStatus.OK.toByte()
            it[7] = (PtpStatus.OK shr 8).toByte()
        }

        val response = PtpResponsePacket.parse(bytes)

        assertEquals(PtpStatus.OK, response.code)
        assertTrue(response.isOk)
        assertArrayEquals(intArrayOf(0x1234), response.params)
    }

    @Test
    fun shouldRejectResponse_whenFewerBytesArrivedThanDeclared() {
        val bytes = PtpContainer.command(PtpOp.OPEN_SESSION, 1).copyOf(8)

        assertThrows(PtpProtocolException::class.java) { PtpResponsePacket.parse(bytes) }
    }

    @Test
    fun shouldNotBeOk_forAnyNonSuccessCode() {
        val bytes = PtpContainer.command(PtpOp.OPEN_SESSION, 1)
        bytes[6] = PtpStatus.SESSION_ALREADY_OPEN.toByte()
        bytes[7] = (PtpStatus.SESSION_ALREADY_OPEN shr 8).toByte()

        assertFalse(PtpResponsePacket.parse(bytes).isOk)
    }
}
