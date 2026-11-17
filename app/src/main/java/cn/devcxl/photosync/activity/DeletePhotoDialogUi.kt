package cn.devcxl.photosync.activity

import androidx.annotation.StringRes

import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.manager.CameraDeleteBlocker
import cn.devcxl.photosync.ptp.manager.CameraDeleteResult

/**
 * Explains why "delete on camera" cannot be offered for this photo.
 */
@StringRes
internal fun cameraDeleteBlockerMessage(blocker: CameraDeleteBlocker): Int {
    return when (blocker) {
        CameraDeleteBlocker.NotConnected -> R.string.delete_on_camera_unavailable_not_connected
        CameraDeleteBlocker.NotSupported -> R.string.delete_on_camera_unavailable_not_supported
        CameraDeleteBlocker.NoHandle -> R.string.delete_on_camera_unavailable_no_handle
    }
}

/**
 * Resolves the initial checked state of the "delete on camera" checkbox.
 *
 * Only pre-checks when camera deletion is actually available ([canDeleteOnCamera]);
 * otherwise stays unchecked so a disabled checkbox never displays a misleading check.
 */
internal fun initialDeleteOnCameraChecked(
    canDeleteOnCamera: Boolean,
    rememberedDeleteOnCamera: Boolean
): Boolean = canDeleteOnCamera && rememberedDeleteOnCamera

/**
 * Turns a camera delete outcome into the message to show.
 *
 * @return the message to show, or null when the camera really deleted the object.
 */
@StringRes
internal fun cameraDeleteFailureMessage(result: CameraDeleteResult): Int? {
    return when (result) {
        CameraDeleteResult.Deleted -> null
        CameraDeleteResult.NotConnected -> R.string.camera_delete_failed_not_connected
        CameraDeleteResult.NotSupported -> R.string.camera_delete_failed_not_supported
        CameraDeleteResult.NotFound -> R.string.camera_delete_failed_not_found
        CameraDeleteResult.Protected -> R.string.camera_delete_failed_protected
        CameraDeleteResult.ReadOnly -> R.string.camera_delete_failed_read_only
        is CameraDeleteResult.Failed -> R.string.camera_delete_failed
    }
}
