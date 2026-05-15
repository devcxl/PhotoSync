package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class Response(buf: ByteArray, f: NameFactory) : ParamVector(buf, buf.size, f) {

    constructor(buf: ByteArray, len: Int, f: NameFactory) : this(buf, f) {
        length = len
    }

    override fun getCodeName(code: Int): String = factory.getResponseString(code)

    companion object {
        const val Undefined: Int = 0x2000
        const val OK: Int = 0x2001
        const val GeneralError: Int = 0x2002
        const val SessionNotOpen: Int = 0x2003
        const val InvalidTransactionID: Int = 0x2004
        const val OperationNotSupported: Int = 0x2005
        const val ParameterNotSupported: Int = 0x2006
        const val IncompleteTransfer: Int = 0x2007
        const val InvalidStorageID: Int = 0x2008
        const val InvalidObjectHandle: Int = 0x2009
        const val DevicePropNotSupported: Int = 0x200a
        const val InvalidObjectFormatCode: Int = 0x200b
        const val StoreFull: Int = 0x200c
        const val ObjectWriteProtected: Int = 0x200d
        const val StoreReadOnly: Int = 0x200e
        const val AccessDenied: Int = 0x200f
        const val NoThumbnailPresent: Int = 0x2010
        const val SelfTestFailed: Int = 0x2011
        const val PartialDeletion: Int = 0x2012
        const val StoreNotAvailable: Int = 0x2013
        const val SpecificationByFormatUnsupported: Int = 0x2014
        const val NoValidObjectInfo: Int = 0x2015
        const val InvalidCodeFormat: Int = 0x2016
        const val UnknownVendorCode: Int = 0x2017
        const val CaptureAlreadyTerminated: Int = 0x2018
        const val DeviceBusy: Int = 0x2019
        const val InvalidParentObject: Int = 0x201a
        const val InvalidDevicePropFormat: Int = 0x201b
        const val InvalidDevicePropValue: Int = 0x201c
        const val InvalidParameter: Int = 0x201d
        const val SessionAlreadyOpen: Int = 0x201e
        const val TransactionCanceled: Int = 0x201f
        const val SpecificationOfDestinationUnsupported: Int = 0x2020

        @JvmStatic
        fun _getResponseString(code: Int): String = when (code) {
            Undefined -> "Undefined"
            OK -> "OK"
            GeneralError -> "GeneralError"
            SessionNotOpen -> "SessionNotOpen"
            InvalidTransactionID -> "InvalidTransactionID"
            OperationNotSupported -> "OperationNotSupported"
            ParameterNotSupported -> "ParameterNotSupported"
            IncompleteTransfer -> "IncompleteTransfer"
            InvalidStorageID -> "InvalidStorageID"
            InvalidObjectHandle -> "InvalidObjectHandle"
            DevicePropNotSupported -> "DevicePropNotSupported"
            InvalidObjectFormatCode -> "InvalidObjectFormatCode"
            StoreFull -> "StoreFull"
            ObjectWriteProtected -> "ObjectWriteProtected"
            StoreReadOnly -> "StoreReadOnly"
            AccessDenied -> "AccessDenied"
            NoThumbnailPresent -> "NoThumbnailPresent"
            SelfTestFailed -> "SelfTestFailed"
            PartialDeletion -> "PartialDeletion"
            StoreNotAvailable -> "StoreNotAvailable"
            SpecificationByFormatUnsupported -> "SpecificationByFormatUnsupported"
            NoValidObjectInfo -> "NoValidObjectInfo"
            InvalidCodeFormat -> "InvalidCodeFormat"
            UnknownVendorCode -> "UnknownVendorCode"
            CaptureAlreadyTerminated -> "CaptureAlreadyTerminated"
            DeviceBusy -> "DeviceBusy"
            InvalidParentObject -> "InvalidParentObject"
            InvalidDevicePropFormat -> "InvalidDevicePropFormat"
            InvalidDevicePropValue -> "InvalidDevicePropValue"
            InvalidParameter -> "InvalidParameter"
            SessionAlreadyOpen -> "SessionAlreadyOpen"
            TransactionCanceled -> "TransactionCanceled"
            SpecificationOfDestinationUnsupported -> "SpecificationOfDestinationUnsupported"
            else -> ("0x" + Integer.toHexString(code)).intern()
        }
    }
}
