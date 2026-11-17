package cn.devcxl.photosync.ptp.usbcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BaselineInitiatorOpenSessionTest {

    @Test
    fun shouldOpenSessionAndStartPolling_whenOpenSessionReturnsOk() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.OK))
            )
        )

        initiator.openSession()

        assertTrue(initiator.isSessionActive())
        assertEquals(1, initiator.pollingStartedCount)
        assertEquals(listOf(Command.OpenSession), initiator.recordedCommands.map { it.opcode })
        assertEquals(listOf(1), initiator.recordedCommands.single().params)
    }

    @Test
    fun shouldCloseAndReopenSession_whenOpenSessionReturnsSessionAlreadyOpenAndCloseSucceeds() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen)),
                Step.Reply(response(Response.OK)),
                Step.Reply(response(Response.OK))
            )
        )

        initiator.openSession()

        assertTrue(initiator.isSessionActive())
        assertEquals(1, initiator.pollingStartedCount)
        assertEquals(
            listOf(Command.OpenSession, Command.CloseSession, Command.OpenSession),
            initiator.recordedCommands.map { it.opcode }
        )
        assertEquals(listOf(1), initiator.recordedCommands[0].params)
        assertEquals(emptyList<Int>(), initiator.recordedCommands[1].params)
        assertEquals(listOf(1), initiator.recordedCommands[2].params)
        assertEquals(0, initiator.recordedCommands[2].xid)
    }

    @Test
    fun shouldAllowSubsequentSessionTransactionsWithFreshXid_whenRecoveredFromSessionAlreadyOpen() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen)),
                Step.Reply(response(Response.OK)),
                Step.Reply(response(Response.OK)),
                Step.Reply(response(Response.OK))
            )
        )

        initiator.openSession()
        val postOpenResponse = initiator.sendInSession(Command.GetStorageIDs)

        assertEquals(Response.OK, postOpenResponse.getCode())
        assertEquals(1, initiator.recordedCommands.last().xid)
    }

    @Test
    fun shouldAdoptExistingSession_whenRetryOpenSessionStillReturnsSessionAlreadyOpen() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen)),
                Step.Reply(response(Response.OK)),
                Step.Reply(response(Response.SessionAlreadyOpen))
            )
        )

        initiator.openSession()

        assertTrue(initiator.isSessionActive())
        assertEquals(1, initiator.pollingStartedCount)
        assertEquals(
            listOf(Command.OpenSession, Command.CloseSession, Command.OpenSession),
            initiator.recordedCommands.map { it.opcode }
        )
    }

    @Test
    fun shouldReopenAndActivate_whenCloseSessionReturnsSessionNotOpen() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen)),
                Step.Reply(response(Response.SessionNotOpen)),
                Step.Reply(response(Response.OK))
            )
        )

        initiator.openSession()

        assertTrue(initiator.isSessionActive())
        assertEquals(1, initiator.pollingStartedCount)
        assertEquals(
            listOf(Command.OpenSession, Command.CloseSession, Command.OpenSession),
            initiator.recordedCommands.map { it.opcode }
        )
    }

    @Test
    fun shouldAdoptExistingSession_whenCloseSessionIsRejectedByCamera() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen)),
                Step.Reply(response(Response.InvalidTransactionID))
            )
        )

        initiator.openSession()

        assertTrue(initiator.isSessionActive())
        assertEquals(1, initiator.pollingStartedCount)
        assertEquals(
            listOf(Command.OpenSession, Command.CloseSession),
            initiator.recordedCommands.map { it.opcode }
        )
    }

    @Test
    fun shouldAdoptExistingSession_whenCameraDoesNotSupportCloseSession() {
        val initiator = FakeBaselineInitiator(
            supportedOperations = intArrayOf(
                Command.GetDeviceInfo,
                Command.OpenSession,
                Command.GetStorageIDs
            ),
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen))
            )
        )

        initiator.openSession()

        assertTrue(initiator.isSessionActive())
        assertEquals(1, initiator.pollingStartedCount)
        assertEquals(listOf(Command.OpenSession), initiator.recordedCommands.map { it.opcode })
    }

    @Test
    fun shouldThrowPtpOpenSessionException_whenRetryOpenSessionFailsWithDeviceBusy() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen)),
                Step.Reply(response(Response.OK)),
                Step.Reply(response(Response.DeviceBusy))
            )
        )

        try {
            initiator.openSession()
            fail("Expected PTPOpenSessionException")
        } catch (e: PTPOpenSessionException) {
            assertEquals(Response.DeviceBusy, e.errorCode)
        }

        assertFalse(initiator.isSessionActive())
        assertEquals(0, initiator.pollingStartedCount)
    }

    @Test
    fun shouldPropagatePtpException_whenCloseSessionTransferFails() {
        val initiator = FakeBaselineInitiator(
            scriptedSteps = mutableListOf(
                Step.Reply(response(Response.SessionAlreadyOpen)),
                Step.Throw(PTPException("bulkTransfer failed"))
            )
        )

        try {
            initiator.openSession()
            fail("Expected PTPException")
        } catch (e: PTPException) {
            assertEquals("bulkTransfer failed", e.message)
        }

        assertFalse(initiator.isSessionActive())
        assertEquals(0, initiator.pollingStartedCount)
    }

    @Test
    fun shouldStillRejectCloseSessionViaTransactUnsync_whenSessionIsInactive() {
        val initiator = FakeBaselineInitiator(scriptedSteps = mutableListOf())

        try {
            initiator.sendCloseSessionDirectly()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("no session", e.message)
        }
    }

    private data class RecordedCommand(
        val opcode: Int,
        val xid: Int,
        val params: List<Int>
    )

    private sealed class Step {
        data class Reply(val response: Response) : Step()
        data class Throw(val exception: PTPException) : Step()
    }

    private class FakeBaselineInitiator(
        supportedOperations: IntArray? = null,
        private val scriptedSteps: MutableList<Step>
    ) : BaselineInitiator() {
        val recordedCommands = mutableListOf<RecordedCommand>()
        var pollingStartedCount = 0

        init {
            if (supportedOperations != null) {
                info = DeviceInfo(this).apply {
                    operationsSupported = supportedOperations
                }
            }
        }

        override fun startPolling() {
            pollingStartedCount++
        }

        override fun executeBulkTransaction(command: Command, data: Data?): Response {
            val params = (0 until command.numParams).map { command.getParam(it) }
            recordedCommands.add(
                RecordedCommand(
                    opcode = command.getCode(),
                    xid = command.getXID(),
                    params = params
                )
            )
            val next = scriptedSteps.removeAt(0)
            return when (next) {
                is Step.Reply -> next.response
                is Step.Throw -> throw next.exception
            }
        }

        fun sendInSession(opcode: Int): Response = transact0(opcode, null)

        fun sendCloseSessionDirectly(): Response =
            transactUnsync(Command(Command.CloseSession, session), null)
    }

    companion object {
        private fun response(code: Int, xid: Int = 0): Response {
            val buf = ByteArray(Container.HDR_LEN)
            val r = Response(buf, Container.HDR_LEN, NameFactory())
            r.offset = 0
            r.putHeader(Container.HDR_LEN, Container.BLOCK_TYPE_RESPONSE, code, xid)
            return r
        }
    }
}
