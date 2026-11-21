package cn.devcxl.photosync.ptp.protocol

/** Base type for every failure raised while talking PTP. */
open class PtpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The bytes on the wire do not match the container or dataset layout. */
class PtpProtocolException(message: String) : PtpException(message)

/** The camera answered, but with a code other than OK. */
class PtpDeviceException(
    val code: Int,
    message: String,
) : PtpException("$message (${PtpStatus.describe(code)})")

/**
 * `OpenSession` could not be established.
 *
 * Carries the response code so the caller can tell "busy, try later" from
 * "this camera refuses sessions".
 */
class PtpSessionException(
    val code: Int,
    message: String,
) : PtpException("$message (${PtpStatus.describe(code)})")

/** A transfer failed below the protocol: USB error, timeout, or a short read. */
class PtpTransportException(message: String, cause: Throwable? = null) : PtpException(message, cause)
