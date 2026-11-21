package cn.devcxl.photosync.ptp.manager

import cn.devcxl.photosync.ptp.protocol.PtpStatus

/**
 * Outcome of asking the camera to delete one object.
 */
sealed interface CameraDeleteResult {
    /** The camera confirmed the object is gone. */
    data object Deleted : CameraDeleteResult

    /** There is no session, so nothing was asked. */
    data object NotConnected : CameraDeleteResult

    /** The camera does not implement `DeleteObject`. */
    data object NotSupported : CameraDeleteResult

    /** The handle is unknown in this session: already deleted, or a different card. */
    data object NotFound : CameraDeleteResult

    /** The camera marked the object write-protected. */
    data object Protected : CameraDeleteResult

    /** The card is read-only, or the camera denied access to it. */
    data object ReadOnly : CameraDeleteResult

    /**
     * Anything else.
     *
     * @param detail for the log; the UI shows one generic message because the user cannot
     *   act on a PTP response code.
     */
    data class Failed(val detail: String?) : CameraDeleteResult
}

/**
 * Why "delete on camera" is unavailable for a photo, or null when it is available.
 */
enum class CameraDeleteBlocker {
    NotConnected,
    NotSupported,
    NoHandle
}

/**
 * Decides whether the delete dialog may offer to delete the camera's copy.
 *
 * The checkbox is only offered when an action is possible: no session, a camera without
 * `DeleteObject`, or a row imported before handles were recorded all make the option a
 * dead end.
 *
 * @param connected Whether a session is open.
 * @param cameraSupportsDelete Whether the connected camera advertises `DeleteObject`.
 * @param hasObjectHandle Whether this photo recorded a handle when it was imported.
 */
internal fun cameraDeleteBlockedBy(
    connected: Boolean,
    cameraSupportsDelete: Boolean,
    hasObjectHandle: Boolean
): CameraDeleteBlocker? {
    return when {
        !connected -> CameraDeleteBlocker.NotConnected
        !cameraSupportsDelete -> CameraDeleteBlocker.NotSupported
        !hasObjectHandle -> CameraDeleteBlocker.NoHandle
        else -> null
    }
}

/**
 * Maps the PTP response code of `DeleteObject` onto a user-visible outcome.
 */
internal fun cameraDeleteResultOf(responseCode: Int): CameraDeleteResult {
    return when (responseCode) {
        PtpStatus.OK -> CameraDeleteResult.Deleted
        PtpStatus.OPERATION_NOT_SUPPORTED -> CameraDeleteResult.NotSupported
        PtpStatus.INVALID_OBJECT_HANDLE -> CameraDeleteResult.NotFound
        PtpStatus.OBJECT_WRITE_PROTECTED -> CameraDeleteResult.Protected
        PtpStatus.STORE_READ_ONLY,
        PtpStatus.ACCESS_DENIED -> CameraDeleteResult.ReadOnly
        PtpStatus.SESSION_NOT_OPEN -> CameraDeleteResult.NotConnected
        else -> CameraDeleteResult.Failed(PtpStatus.describe(responseCode))
    }
}
