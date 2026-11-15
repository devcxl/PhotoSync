package cn.devcxl.photosync.ptp.usbcamera

import org.junit.Assert.assertEquals
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
}
