package cn.devcxl.photosync.ptp.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPtpConnectionPolicyTest {
    private val currentDevice = UsbPtpDeviceId(
        deviceId = 11,
        deviceName = "/dev/bus/usb/001/002",
        vendorId = 0x04a9,
        productId = 0x1234
    )
    private val pendingDevice = UsbPtpDeviceId(
        deviceId = 12,
        deviceName = "/dev/bus/usb/001/003",
        vendorId = 0x054c,
        productId = 0x5678
    )

    @Test
    fun shouldReturnCurrentDevice_whenDetachedMatchesCurrent() {
        val detached = currentDevice.copy()

        val result = resolveDetachedDisconnectTarget(currentDevice, pendingDevice, detached)

        assertEquals(currentDevice, result)
    }

    @Test
    fun shouldReturnPendingDevice_whenDetachedMatchesPending() {
        val detached = pendingDevice.copy()

        val result = resolveDetachedDisconnectTarget(currentDevice, pendingDevice, detached)

        assertEquals(pendingDevice, result)
    }

    @Test
    fun shouldReturnNull_whenDetachedIsUnrelated() {
        val detached = UsbPtpDeviceId(
            deviceId = 99,
            deviceName = "/dev/bus/usb/001/099",
            vendorId = 0x04b0,
            productId = 0x9999
        )

        val result = resolveDetachedDisconnectTarget(currentDevice, pendingDevice, detached)

        assertNull(result)
    }

    @Test
    fun shouldSkip_whenSameDeviceAndConnected() {
        val result = shouldSkipConnect(
            currentState = UsbPtpConnectionState.Connected(currentDevice),
            trackedDevice = currentDevice,
            candidate = currentDevice
        )

        assertTrue(result)
    }

    @Test
    fun shouldSkip_whenSameDeviceAndPermissionPending() {
        val result = shouldSkipConnect(
            currentState = UsbPtpConnectionState.PermissionRequested(currentDevice),
            trackedDevice = currentDevice,
            candidate = currentDevice
        )

        assertTrue(result)
    }

    @Test
    fun shouldNotSkip_whenSameDeviceAfterError() {
        val result = shouldSkipConnect(
            currentState = UsbPtpConnectionState.Error(currentDevice, "connect_failed", "boom"),
            trackedDevice = currentDevice,
            candidate = currentDevice
        )

        assertFalse(result)
    }
}
