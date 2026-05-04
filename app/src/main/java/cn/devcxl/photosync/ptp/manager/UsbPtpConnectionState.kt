package cn.devcxl.photosync.ptp.manager

import android.hardware.usb.UsbDevice

/**
 * Sealed class representing the current state of a USB PTP connection.
 */
sealed class UsbPtpConnectionState {
    data object Idle : UsbPtpConnectionState()

    data class PermissionRequested(val device: UsbPtpDeviceId) : UsbPtpConnectionState()

    data class Connecting(val device: UsbPtpDeviceId) : UsbPtpConnectionState()

    data class Connected(val device: UsbPtpDeviceId) : UsbPtpConnectionState()

    data class Disconnecting(val device: UsbPtpDeviceId?, val reason: String) : UsbPtpConnectionState()

    data class Disconnected(val device: UsbPtpDeviceId?, val reason: String) : UsbPtpConnectionState()

    data class Error(
        val device: UsbPtpDeviceId?,
        val reason: String,
        val detail: String? = null
    ) : UsbPtpConnectionState()
}

data class UsbPtpDeviceId(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int
)

fun UsbDevice.toUsbPtpDeviceId(): UsbPtpDeviceId {
    return UsbPtpDeviceId(
        deviceId = deviceId,
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId
    )
}

internal fun resolveDetachedDisconnectTarget(
    currentDevice: UsbPtpDeviceId?,
    pendingDevice: UsbPtpDeviceId?,
    detached: UsbPtpDeviceId?
): UsbPtpDeviceId? {
    detached ?: return null
    return when {
        currentDevice == detached -> currentDevice
        pendingDevice == detached -> pendingDevice
        else -> null
    }
}

internal fun shouldSkipConnect(
    currentState: UsbPtpConnectionState,
    trackedDevice: UsbPtpDeviceId?,
    candidate: UsbPtpDeviceId
): Boolean {
    if (trackedDevice != candidate) {
        return false
    }
    return when (currentState) {
        is UsbPtpConnectionState.PermissionRequested,
        is UsbPtpConnectionState.Connecting,
        is UsbPtpConnectionState.Connected,
        is UsbPtpConnectionState.Disconnecting -> true
        is UsbPtpConnectionState.Idle,
        is UsbPtpConnectionState.Disconnected,
        is UsbPtpConnectionState.Error -> false
    }
}
