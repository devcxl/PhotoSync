package cn.devcxl.photosync.ptp.protocol

/**
 * Operation codes defined by PIMA 15740 (PTP 1.0), restricted to the operations this app
 * actually sends.
 */
object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_STORAGE_INFO = 0x1005
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val DELETE_OBJECT = 0x100b
}

/**
 * Response codes from PIMA 15740 section 13, limited to the codes the sync path acts on.
 *
 * The names follow the standard's wording so a log line can be matched against the
 * specification without a lookup table.
 */
object PtpStatus {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val INVALID_TRANSACTION_ID = 0x2004
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val OBJECT_WRITE_PROTECTED = 0x200d
    const val STORE_READ_ONLY = 0x200e
    const val ACCESS_DENIED = 0x200f
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201e

    /** Human-readable name, for logs only. */
    fun describe(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "GeneralError"
        SESSION_NOT_OPEN -> "SessionNotOpen"
        INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        OBJECT_WRITE_PROTECTED -> "ObjectWriteProtected"
        STORE_READ_ONLY -> "StoreReadOnly"
        ACCESS_DENIED -> "AccessDenied"
        DEVICE_BUSY -> "DeviceBusy"
        SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        else -> "0x%04x".format(code)
    }
}

/**
 * Event codes. Vendors extend this range; only the standard codes the sync path reads are
 * listed here, vendor codes live with their owner.
 */
object PtpEvent {
    /** A new object exists on the card. Parameter 1 is its handle. */
    const val OBJECT_ADDED = 0x4002

    /** The camera may have dropped events; the host should re-read the object list. */
    const val UNREPORTED_STATUS = 0x400e
}

/** Object format codes. */
object PtpFormat {
    /** A folder, not a file: `ObjectInfo.associationType` describes what it holds. */
    const val ASSOCIATION = 0x3001
}
