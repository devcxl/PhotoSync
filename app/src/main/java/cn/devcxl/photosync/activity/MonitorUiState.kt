package cn.devcxl.photosync.activity

import java.util.Locale

import androidx.annotation.StringRes

import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState
import cn.devcxl.photosync.utils.ExtensionUtils

/**
 * Visual emphasis styles for the top-left Camera connection pill.
 */
internal enum class ConnectPillVisualState {
    IDLE,
    READY,
    BUSY,
    CONNECTED,
    ERROR
}

/**
 * Action taken when the user taps the Camera connection pill.
 */
internal enum class ConnectPillAction {
    NONE,
    CONNECT,
    CONFIRM_DISCONNECT
}

/**
 * Presentation model for the Pro Tethered Monitor connection pill.
 */
internal data class ConnectPillUi(
    @StringRes val label: Int,
    val enabled: Boolean,
    val visualState: ConnectPillVisualState
)

/**
 * Presentation model for the center empty-library guidance view.
 */
internal data class EmptyStateUi(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val actionLabel: Int?,
    val actionEnabled: Boolean
)

/**
 * Format badge classification shown in the top bar and filmstrip.
 */
internal enum class PhotoFormatBadge {
    RAW,
    JPEG,
    OTHER
}

/**
 * Maps the current USB PTP connection state and Camera attachment flag to the
 * connection pill's label, enabled state, and visual emphasis.
 *
 * @param state Current USB connection state.
 * @param cameraPresent Whether a supported Camera is attached right now.
 */
internal fun resolveConnectPillUi(
    state: UsbPtpConnectionState,
    cameraPresent: Boolean
): ConnectPillUi {
    return when (state) {
        UsbPtpConnectionState.Idle,
        is UsbPtpConnectionState.Disconnected -> {
            if (cameraPresent) {
                ConnectPillUi(R.string.connect_camera, true, ConnectPillVisualState.READY)
            } else {
                ConnectPillUi(R.string.camera_not_detected, false, ConnectPillVisualState.IDLE)
            }
        }
        is UsbPtpConnectionState.Error -> {
            if (cameraPresent) {
                ConnectPillUi(
                    resolveConnectionErrorLabel(state.reason),
                    true,
                    ConnectPillVisualState.ERROR
                )
            } else {
                ConnectPillUi(R.string.camera_not_detected, false, ConnectPillVisualState.IDLE)
            }
        }
        is UsbPtpConnectionState.PermissionRequested ->
            ConnectPillUi(R.string.camera_waiting_permission, false, ConnectPillVisualState.BUSY)
        is UsbPtpConnectionState.Connecting ->
            ConnectPillUi(R.string.camera_connecting, false, ConnectPillVisualState.BUSY)
        is UsbPtpConnectionState.Connected ->
            ConnectPillUi(R.string.camera_connected_label, true, ConnectPillVisualState.CONNECTED)
        is UsbPtpConnectionState.Disconnecting ->
            ConnectPillUi(R.string.camera_disconnecting, false, ConnectPillVisualState.BUSY)
    }
}

/**
 * Maps a [UsbPtpConnectionState.Error.reason] to a user-facing retry prompt.
 */
@StringRes
internal fun resolveConnectionErrorLabel(reason: String): Int {
    return when (reason) {
        "permission_denied" -> R.string.camera_error_permission_denied
        "permission_missing_device" -> R.string.camera_error_missing_device
        else -> R.string.camera_error_connect_failed
    }
}

/**
 * Determines what happens when the user taps the top-left connection pill.
 *
 * An active Session requires confirmation before disconnecting so accidental taps during
 * photo review do not tear down the Camera link.
 */
internal fun resolveConnectPillAction(
    state: UsbPtpConnectionState,
    cameraPresent: Boolean
): ConnectPillAction {
    return when (state) {
        is UsbPtpConnectionState.Connected -> ConnectPillAction.CONFIRM_DISCONNECT
        UsbPtpConnectionState.Idle,
        is UsbPtpConnectionState.Disconnected,
        is UsbPtpConnectionState.Error -> {
            if (cameraPresent) ConnectPillAction.CONNECT else ConnectPillAction.NONE
        }
        is UsbPtpConnectionState.PermissionRequested,
        is UsbPtpConnectionState.Connecting,
        is UsbPtpConnectionState.Disconnecting -> ConnectPillAction.NONE
    }
}

/**
 * Resolves the center empty-state guidance copy and CTA button when no photos are imported.
 */
internal fun resolveEmptyStateUi(
    state: UsbPtpConnectionState,
    cameraPresent: Boolean
): EmptyStateUi {
    return when (state) {
        is UsbPtpConnectionState.Connected -> EmptyStateUi(
            title = R.string.empty_connected_title,
            description = R.string.empty_connected_desc,
            actionLabel = null,
            actionEnabled = false
        )
        is UsbPtpConnectionState.PermissionRequested,
        is UsbPtpConnectionState.Connecting,
        is UsbPtpConnectionState.Disconnecting -> EmptyStateUi(
            title = R.string.empty_connecting_title,
            description = R.string.empty_connecting_desc,
            actionLabel = null,
            actionEnabled = false
        )
        is UsbPtpConnectionState.Error -> {
            if (!cameraPresent) {
                EmptyStateUi(
                    title = R.string.empty_no_camera_title,
                    description = R.string.empty_no_camera_desc,
                    actionLabel = null,
                    actionEnabled = false
                )
            } else {
                val desc = if (state.reason == "permission_denied") {
                    R.string.empty_error_desc_permission
                } else {
                    R.string.empty_error_desc_generic
                }
                EmptyStateUi(
                    title = R.string.empty_error_title,
                    description = desc,
                    actionLabel = R.string.empty_action_retry,
                    actionEnabled = true
                )
            }
        }
        UsbPtpConnectionState.Idle,
        is UsbPtpConnectionState.Disconnected -> {
            if (cameraPresent) {
                EmptyStateUi(
                    title = R.string.empty_ready_title,
                    description = R.string.empty_ready_desc,
                    actionLabel = R.string.empty_action_connect,
                    actionEnabled = true
                )
            } else {
                EmptyStateUi(
                    title = R.string.empty_no_camera_title,
                    description = R.string.empty_no_camera_desc,
                    actionLabel = null,
                    actionEnabled = false
                )
            }
        }
    }
}

/**
 * Formats a 0-based photo index and total count as `"1 / 12"`, or empty when there are no items.
 */
internal fun formatPhotoCounter(index: Int, total: Int): String {
    if (total <= 0) return ""
    val safeIndex = index.coerceIn(0, total - 1)
    return "${safeIndex + 1} / $total"
}

/**
 * Classifies a photo path into a visible format badge category.
 */
internal fun resolvePhotoFormatBadge(path: String): PhotoFormatBadge {
    val ext = ExtensionUtils.fileExtension(path)
    return when {
        ExtensionUtils.isRawExtension(ext) -> PhotoFormatBadge.RAW
        ExtensionUtils.isJpegExtension(ext) -> PhotoFormatBadge.JPEG
        else -> PhotoFormatBadge.OTHER
    }
}

/**
 * Computes normalized zoom ratio (`>= 1.0f`) relative to a viewer's fit-screen minimum scale.
 */
internal fun resolveZoomRatio(scale: Float, minScale: Float): Float {
    if (minScale <= 0f) return 1f
    return (scale / minScale).coerceAtLeast(1f)
}

/**
 * Returns true when the current scale exceeds the resting fit-screen scale beyond tolerance.
 */
internal fun isZoomedScale(scale: Float, minScale: Float): Boolean {
    return minScale > 0f && scale > minScale * 1.02f
}

/**
 * Formats the zoom multiplier badge (e.g. `"2.1x"`).
 */
internal fun formatZoomBadge(zoomRatio: Float): String {
    return String.format(Locale.US, "%.1fx", zoomRatio.coerceAtLeast(1f))
}

/**
 * Overlays remain visible when the library is empty; otherwise they hide while zoomed or
 * while the user has toggled immersive mode.
 */
internal fun shouldShowOverlays(
    hasPhotos: Boolean,
    userImmersive: Boolean,
    isZoomed: Boolean
): Boolean {
    if (!hasPhotos) return true
    return !userImmersive && !isZoomed
}

/**
 * Computes clamped `0..100` transfer percentage.
 */
internal fun resolveTransferPercent(transferredBytes: Int, totalBytes: Int): Int {
    if (totalBytes <= 0) return 0
    return ((transferredBytes.toLong() * 100L) / totalBytes.toLong()).toInt().coerceIn(0, 100)
}

/**
 * Returns true when an exported gallery URI has been persisted for the photo.
 */
internal fun isPhotoExported(uriString: String?): Boolean = !uriString.isNullOrBlank()

/**
 * Formats byte count into human-readable file size (`B`, `KB`, `MB`).
 */
internal fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/**
 * Formats pixel dimensions as `"6000 × 4000"` or `"—"` when unavailable.
 */
internal fun formatPhotoDimensions(width: Int?, height: Int?): String {
    if (width == null || height == null || width <= 0 || height <= 0) return "—"
    return "$width × $height"
}

/**
 * Formats format + size string for the info drawer (e.g. `"RAW · 48.6 MB"`).
 */
internal fun formatPhotoFormatAndSize(formatBadge: PhotoFormatBadge, sizeBytes: Long): String {
    val sizeText = formatFileSize(sizeBytes)
    val formatText = when (formatBadge) {
        PhotoFormatBadge.RAW -> "RAW"
        PhotoFormatBadge.JPEG -> "JPEG"
        PhotoFormatBadge.OTHER -> "FILE"
    }
    return if (sizeText == "—") formatText else "$formatText · $sizeText"
}

/**
 * Classification for the camera object handle row in the info drawer.
 */
internal enum class CameraHandleAvailability {
    AVAILABLE,
    NOT_CONNECTED,
    NOT_SUPPORTED,
    MISSING
}

internal fun resolveCameraHandleAvailability(
    objectHandle: Int?,
    isConnected: Boolean,
    cameraSupportsDelete: Boolean
): CameraHandleAvailability {
    if (objectHandle == null) return CameraHandleAvailability.MISSING
    if (!isConnected) return CameraHandleAvailability.NOT_CONNECTED
    if (!cameraSupportsDelete) return CameraHandleAvailability.NOT_SUPPORTED
    return CameraHandleAvailability.AVAILABLE
}

/**
 * Encodes the prototype's non-interruptive reveal rule for newly synced photos:
 * advances automatically only when AutoFollow is enabled, the user is not zoomed in,
 * and the user was already viewing the latest frame.
 */
internal fun shouldAutoAdvanceOnNewPhoto(
    autoFollowEnabled: Boolean,
    isZoomed: Boolean,
    wasViewingLatestBeforeInsert: Boolean
): Boolean = autoFollowEnabled && !isZoomed && wasViewingLatestBeforeInsert

/**
 * Returns true when the library was empty or the user was on the final frame.
 */
internal fun isViewingLatestPhoto(currentIndex: Int, totalCount: Int): Boolean {
    return totalCount <= 0 || currentIndex >= totalCount - 1
}

/**
 * Filmstrip container is shown only when photos exist and overlays are not hidden by zoom or
 * immersive mode.
 */
internal fun shouldShowFilmstrip(hasPhotos: Boolean, overlaysVisible: Boolean): Boolean {
    return hasPhotos && overlaysVisible
}




