package cn.devcxl.photosync.activity

import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState
import cn.devcxl.photosync.ptp.manager.UsbPtpDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectButtonUiTest {

    private val camera = UsbPtpDeviceId(
        deviceId = 11,
        deviceName = "/dev/bus/usb/001/002",
        vendorId = 0x04a9,
        productId = 0x1234
    )

    @Test
    fun shouldDisableConnectButton_whenNoCameraDetected() {
        val ui = connectButtonUi(UsbPtpConnectionState.Idle, cameraPresent = false)

        assertFalse(ui.enabled)
        assertEquals(R.string.camera_not_detected, ui.label)
    }

    @Test
    fun shouldEnableConnectButton_whenCameraDetectedAndIdle() {
        val ui = connectButtonUi(UsbPtpConnectionState.Idle, cameraPresent = true)

        assertTrue(ui.enabled)
        assertEquals(R.string.connect_camera, ui.label)
    }

    @Test
    fun shouldEnableConnectButton_whenDisconnectedWithCameraPresent() {
        val ui = connectButtonUi(
            UsbPtpConnectionState.Disconnected(camera, "usb_detached"),
            cameraPresent = true
        )

        assertTrue(ui.enabled)
        assertEquals(R.string.connect_camera, ui.label)
    }

    @Test
    fun shouldDisableConnectButton_whenWaitingForPermission() {
        val ui = connectButtonUi(
            UsbPtpConnectionState.PermissionRequested(camera),
            cameraPresent = true
        )

        assertFalse(ui.enabled)
        assertEquals(R.string.camera_waiting_permission, ui.label)
    }

    @Test
    fun shouldDisableConnectButton_whenConnecting() {
        val ui = connectButtonUi(UsbPtpConnectionState.Connecting(camera), cameraPresent = true)

        assertFalse(ui.enabled)
        assertEquals(R.string.camera_connecting, ui.label)
    }

    @Test
    fun shouldLabelDisconnect_whenConnected() {
        val ui = connectButtonUi(UsbPtpConnectionState.Connected(camera), cameraPresent = true)

        assertTrue(ui.enabled)
        assertEquals(R.string.disconnect_camera, ui.label)
    }

    @Test
    fun shouldDisableConnectButton_whenDisconnecting() {
        val ui = connectButtonUi(
            UsbPtpConnectionState.Disconnecting(camera, "user_request"),
            cameraPresent = true
        )

        assertFalse(ui.enabled)
        assertEquals(R.string.camera_disconnecting, ui.label)
    }

    @Test
    fun shouldOfferRetry_whenConnectFailed() {
        val ui = connectButtonUi(
            UsbPtpConnectionState.Error(camera, "connect_failed", "boom"),
            cameraPresent = true
        )

        assertTrue(ui.enabled)
        assertEquals(R.string.connect_camera, ui.label)
    }
}
