package cn.devcxl.photosync.ptp.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {

    @Test
    fun shouldChooseEventChannel_whenTheCameraAdvertisesTheOperation() {
        assertEquals(
            TriggerChannel.EVENT,
            chooseTriggerChannel(0x9116, setOf(0x1001, 0x9116)),
        )
    }

    @Test
    fun shouldChoosePoll_whenTheCameraDoesNotAdvertiseTheOperation() {
        assertEquals(
            TriggerChannel.POLL,
            chooseTriggerChannel(0x9116, setOf(0x1001, 0x1002)),
        )
    }

    @Test
    fun shouldChoosePoll_whenTheVendorHasNoEventChannel() {
        assertEquals(TriggerChannel.POLL, chooseTriggerChannel(null, setOf(0x9116)))
    }

    @Test
    fun shouldResetToFastPoll_whenTheLastPollReturnedEvents() {
        assertEquals(EVENT_POLL_FAST_MS, nextEventPollDelay(EVENT_POLL_IDLE_MS, eventsLastPoll = 1))
    }

    @Test
    fun shouldGrowTheDelay_whenTheCameraWasQuiet() {
        assertEquals(EVENT_POLL_FAST_MS * 2, nextEventPollDelay(EVENT_POLL_FAST_MS, eventsLastPoll = 0))
    }

    @Test
    fun shouldStopGrowing_atTheIdleInterval() {
        assertEquals(EVENT_POLL_IDLE_MS, nextEventPollDelay(EVENT_POLL_IDLE_MS, eventsLastPoll = 0))
    }

    @Test
    fun shouldFallBackOnlyAfterTheThreshold() {
        assertFalse(shouldFallBackToPolling(EVENT_FAILURE_DEGRADE_THRESHOLD - 1))
        assertTrue(shouldFallBackToPolling(EVENT_FAILURE_DEGRADE_THRESHOLD))
    }

    @Test
    fun shouldListOnlyHandlesTheCameraJustAdded() {
        assertEquals(listOf(3, 4), newHandles(handled = listOf(1, 2), onCamera = listOf(1, 2, 3, 4)))
    }

    @Test
    fun shouldKeepCameraOrder_andDropVanishedHandles() {
        val next = nextHandledSet(
            handled = listOf(1, 2),
            onCamera = listOf(4, 2),
            imported = listOf(4),
        )

        assertEquals(listOf(4, 2), next)
    }

    @Test
    fun shouldDropHandlesWhoseImportFailed() {
        // Handle 5 was on the card and never imported: it must not enter the handled set,
        // otherwise the next pass would treat it as already synced.
        val next = nextHandledSet(handled = listOf(1), onCamera = listOf(1, 5), imported = emptyList())

        assertEquals(listOf(1), next)
    }
}

class EventRetryQueueTest {

    @Test
    fun shouldKeepRetrying_upToTheAttemptLimit() {
        val queue = EventRetryQueue(maxAttempts = 3)
        queue.enqueue(7)

        queue.markFailed(7)
        assertTrue(queue.hasPending())
        queue.markFailed(7)
        assertTrue(queue.hasPending())
        queue.markFailed(7)

        assertFalse(queue.hasPending())
    }

    @Test
    fun shouldForgetHandle_whenTheImportSucceeds() {
        val queue = EventRetryQueue()
        queue.enqueue(7)

        queue.markSucceeded(7)

        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun shouldNotChargeAnAttempt_whenAHandleIsOnlyQueued() {
        val queue = EventRetryQueue(maxAttempts = 2)

        repeat(5) { queue.enqueue(7) }

        // Queuing repeatedly must not exhaust a budget that only failed retries spend.
        assertTrue(queue.hasPending())
    }

    @Test
    fun shouldNotResetAttempts_whenTheSameHandleIsEnqueuedAgain() {
        val queue = EventRetryQueue(maxAttempts = 2)
        queue.enqueue(7)
        queue.markFailed(7)

        queue.enqueue(7)
        queue.markFailed(7)

        assertFalse(queue.hasPending())
    }
}
