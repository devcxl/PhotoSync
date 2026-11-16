package cn.devcxl.photosync.activity

import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState
import cn.devcxl.photosync.ptp.manager.UsbPtpDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorUiStateTest {

    private val camera = UsbPtpDeviceId(
        deviceId = 1,
        deviceName = "/dev/bus/usb/001/002",
        vendorId = 0x054c,
        productId = 0x0d01
    )

    @Test
    fun shouldShowIdleDisabledPill_whenNoCameraDetected() {
        val ui = resolveConnectPillUi(UsbPtpConnectionState.Idle, cameraPresent = false)

        assertFalse(ui.enabled)
        assertEquals(R.string.camera_not_detected, ui.label)
        assertEquals(ConnectPillVisualState.IDLE, ui.visualState)
    }

    @Test
    fun shouldHighlightReadyPill_whenCameraDetectedAndIdle() {
        val ui = resolveConnectPillUi(UsbPtpConnectionState.Idle, cameraPresent = true)

        assertTrue(ui.enabled)
        assertEquals(R.string.connect_camera, ui.label)
        assertEquals(ConnectPillVisualState.READY, ui.visualState)
    }

    @Test
    fun shouldShowBusyState_whenWaitingForPermissionOrConnectingOrDisconnecting() {
        val perm = resolveConnectPillUi(
            UsbPtpConnectionState.PermissionRequested(camera),
            cameraPresent = true
        )
        val conn = resolveConnectPillUi(
            UsbPtpConnectionState.Connecting(camera),
            cameraPresent = true
        )
        val disc = resolveConnectPillUi(
            UsbPtpConnectionState.Disconnecting(camera, "user_request"),
            cameraPresent = true
        )

        assertFalse(perm.enabled)
        assertEquals(ConnectPillVisualState.BUSY, perm.visualState)
        assertFalse(conn.enabled)
        assertEquals(ConnectPillVisualState.BUSY, conn.visualState)
        assertFalse(disc.enabled)
        assertEquals(ConnectPillVisualState.BUSY, disc.visualState)
    }

    @Test
    fun shouldRequireConfirmation_whenTappingConnectedPill() {
        val ui = resolveConnectPillUi(UsbPtpConnectionState.Connected(camera), cameraPresent = true)
        val action = resolveConnectPillAction(
            UsbPtpConnectionState.Connected(camera),
            cameraPresent = true
        )

        assertTrue(ui.enabled)
        assertEquals(R.string.camera_connected_label, ui.label)
        assertEquals(ConnectPillVisualState.CONNECTED, ui.visualState)
        assertEquals(ConnectPillAction.CONFIRM_DISCONNECT, action)
    }

    @Test
    fun shouldOfferRetryWithSpecificReason_whenConnectionErrorWithCameraPresent() {
        val permDenied = resolveConnectPillUi(
            UsbPtpConnectionState.Error(camera, "permission_denied"),
            cameraPresent = true
        )
        val connectFailed = resolveConnectPillUi(
            UsbPtpConnectionState.Error(camera, "connect_failed"),
            cameraPresent = true
        )
        val action = resolveConnectPillAction(
            UsbPtpConnectionState.Error(camera, "connect_failed"),
            cameraPresent = true
        )

        assertTrue(permDenied.enabled)
        assertEquals(R.string.camera_error_permission_denied, permDenied.label)
        assertEquals(ConnectPillVisualState.ERROR, permDenied.visualState)
        assertEquals(R.string.camera_error_connect_failed, connectFailed.label)
        assertEquals(ConnectPillAction.CONNECT, action)
    }

    @Test
    fun shouldShowConnectActionInEmptyState_whenCameraDetectedAndDisconnected() {
        val empty = resolveEmptyStateUi(
            UsbPtpConnectionState.Disconnected(camera, "user_request"),
            cameraPresent = true
        )

        assertEquals(R.string.empty_ready_title, empty.title)
        assertEquals(R.string.empty_action_connect, empty.actionLabel)
        assertTrue(empty.actionEnabled)
    }

    @Test
    fun shouldShowWaitingForShutterInEmptyState_whenConnected() {
        val empty = resolveEmptyStateUi(
            UsbPtpConnectionState.Connected(camera),
            cameraPresent = true
        )

        assertEquals(R.string.empty_connected_title, empty.title)
        assertNull(empty.actionLabel)
        assertFalse(empty.actionEnabled)
    }

    @Test
    fun shouldShowRetryActionInEmptyState_whenConnectionFailed() {
        val empty = resolveEmptyStateUi(
            UsbPtpConnectionState.Error(camera, "permission_denied"),
            cameraPresent = true
        )

        assertEquals(R.string.empty_error_title, empty.title)
        assertEquals(R.string.empty_error_desc_permission, empty.description)
        assertEquals(R.string.empty_action_retry, empty.actionLabel)
        assertTrue(empty.actionEnabled)
    }

    @Test
    fun shouldFormatCounterClamped_whenIndexAndTotalProvided() {
        assertEquals("", formatPhotoCounter(0, 0))
        assertEquals("1 / 12", formatPhotoCounter(0, 12))
        assertEquals("12 / 12", formatPhotoCounter(99, 12))
    }

    @Test
    fun shouldClassifyFormatBadge_whenPathHasRawOrJpegExtension() {
        assertEquals(PhotoFormatBadge.RAW, resolvePhotoFormatBadge("/cache/DSC04821.ARW"))
        assertEquals(PhotoFormatBadge.RAW, resolvePhotoFormatBadge("/cache/IMG_001.cr3"))
        assertEquals(PhotoFormatBadge.JPEG, resolvePhotoFormatBadge("/cache/DSC04821.JPG"))
        assertEquals(PhotoFormatBadge.JPEG, resolvePhotoFormatBadge("/cache/photo.jpeg"))
        assertEquals(PhotoFormatBadge.OTHER, resolvePhotoFormatBadge("/cache/readme.txt"))
    }
}
