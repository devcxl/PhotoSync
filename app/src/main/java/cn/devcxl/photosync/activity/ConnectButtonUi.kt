package cn.devcxl.photosync.activity

import androidx.annotation.StringRes

import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState

/**
 * What the connect button shows for a given connection state and camera presence.
 */
internal data class ConnectButtonUi(@StringRes val label: Int, val enabled: Boolean)

/**
 * The button only offers an action that is currently possible: connecting needs an
 * attached camera, and every in-flight transition disables it so a second tap cannot
 * race the first.
 *
 * @param state Current USB connection state.
 * @param cameraPresent Whether a supported camera is attached right now.
 */
internal fun connectButtonUi(
    state: UsbPtpConnectionState,
    cameraPresent: Boolean
): ConnectButtonUi {
    return when (state) {
        UsbPtpConnectionState.Idle,
        is UsbPtpConnectionState.Disconnected,
        is UsbPtpConnectionState.Error -> {
            if (cameraPresent) {
                ConnectButtonUi(R.string.connect_camera, true)
            } else {
                ConnectButtonUi(R.string.camera_not_detected, false)
            }
        }
        is UsbPtpConnectionState.PermissionRequested ->
            ConnectButtonUi(R.string.camera_waiting_permission, false)
        is UsbPtpConnectionState.Connecting ->
            ConnectButtonUi(R.string.camera_connecting, false)
        is UsbPtpConnectionState.Connected ->
            ConnectButtonUi(R.string.disconnect_camera, true)
        is UsbPtpConnectionState.Disconnecting ->
            ConnectButtonUi(R.string.camera_disconnecting, false)
    }
}
