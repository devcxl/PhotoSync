package cn.devcxl.photosync.ptp.usbcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTriggerPolicyTest {

    @Test
    fun shouldChooseTheEventChannel_whenTheVendorEventOperationIsAdvertised() {
        val decision = syncTriggerDecision(
            vendorEventOpcodes = setOf(0x9116),
            operationsSupported = setOf(0x1001, 0x9115, 0x9116)
        )

        assertEquals(SyncTriggerDecision.EVENT, decision)
    }

    @Test
    fun shouldChoosePolling_whenTheVendorEventOperationIsMissing() {
        val decision = syncTriggerDecision(
            vendorEventOpcodes = setOf(0x9116),
            operationsSupported = setOf(0x1001, 0x9114, 0x9115)
        )

        assertEquals(SyncTriggerDecision.POLL, decision)
    }

    @Test
    fun shouldChoosePolling_whenTheCameraAdvertisesOnlyUnrelatedOperations() {
        val decision = syncTriggerDecision(
            vendorEventOpcodes = setOf(0x90C7),
            operationsSupported = setOf(0x1001, 0x1005, 0x9202)
        )

        assertEquals(SyncTriggerDecision.POLL, decision)
    }

    @Test
    fun shouldChoosePolling_whenTheVendorHasNoEventChannel() {
        // A generic PTP camera: nothing to read events from, whatever the camera can do.
        val decision = syncTriggerDecision(
            vendorEventOpcodes = emptySet(),
            operationsSupported = setOf(0x9116, 0x90C7, 0x1001)
        )

        assertEquals(SyncTriggerDecision.POLL, decision)
    }

    @Test
    fun shouldStayOnTheEventChannel_whenNoEventPollFailed() {
        assertFalse(shouldDegradeToPolling(consecutiveFailures = 0))
    }

    @Test
    fun shouldOnlyCountTheFailure_whenOneEventPollFailed() {
        assertFalse(shouldDegradeToPolling(consecutiveFailures = 1))
    }

    @Test
    fun shouldOnlyCountTheFailures_whenTwoEventPollsFailed() {
        assertFalse(shouldDegradeToPolling(consecutiveFailures = 2))
    }

    @Test
    fun shouldDegradeToPolling_whenThreeEventPollsFailed() {
        assertTrue(shouldDegradeToPolling(consecutiveFailures = 3))
    }

    @Test
    fun shouldKeepPolling_whenMoreEventPollsFailed() {
        assertTrue(shouldDegradeToPolling(consecutiveFailures = 4))
        assertTrue(shouldDegradeToPolling(consecutiveFailures = 12))
    }

    @Test
    fun shouldReconcile_whenTheSessionStarts() {
        assertTrue(shouldReconcile(ReconcileCause.SESSION_START))
    }

    @Test
    fun shouldReconcile_whenTheCameraReportsItMayHaveMissedEvents() {
        assertTrue(shouldReconcile(ReconcileCause.UNREPORTED_EVENTS))
    }

    @Test
    fun shouldReconcile_whenTheCameraIsReconnected() {
        assertTrue(shouldReconcile(ReconcileCause.RECONNECT))
    }

    @Test
    fun shouldNotReconcile_onAPeriodicTick() {
        // The pass shares the session with the 200 ms event poll, so no timer starts one.
        assertFalse(shouldReconcile(ReconcileCause.PERIODIC_TICK))
    }
}
