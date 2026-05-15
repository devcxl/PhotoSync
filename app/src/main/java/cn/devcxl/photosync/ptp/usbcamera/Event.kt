package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class Event(buf: ByteArray, f: NameFactory) : ParamVector(buf, buf.size, f) {

    override fun getCodeName(code: Int): String = _getEventString(code)

    companion object {
        @JvmField val Undefined: Int = 0x4000
        @JvmField val CancelTransaction: Int = 0x4001
        @JvmField val ObjectAdded: Int = 0x4002
        @JvmField val ObjectRemoved: Int = 0x4003
        @JvmField val StoreAdded: Int = 0x4004
        @JvmField val StoreRemoved: Int = 0x4005
        @JvmField val DevicePropChanged: Int = 0x4006
        @JvmField val ObjectInfoChanged: Int = 0x4007
        @JvmField val DeviceInfoChanged: Int = 0x4008
        @JvmField val RequestObjectTransfer: Int = 0x4009
        @JvmField val StoreFull: Int = 0x400a
        @JvmField val DeviceReset: Int = 0x400b
        @JvmField val StorageInfoChanged: Int = 0x400c
        @JvmField val CaptureComplete: Int = 0x400d
        @JvmField val UnreportedStatus: Int = 0x400e

        @JvmStatic
        fun _getEventString(code: Int): String = when (code) {
            Undefined -> "Undefined"
            CancelTransaction -> "CancelTransaction"
            ObjectAdded -> "ObjectAdded"
            ObjectRemoved -> "ObjectRemoved"
            StoreAdded -> "StoreAdded"
            StoreRemoved -> "StoreRemoved"
            DevicePropChanged -> "DevicePropChanged"
            ObjectInfoChanged -> "ObjectInfoChanged"
            DeviceInfoChanged -> "DeviceInfoChanged"
            RequestObjectTransfer -> "RequestObjectTransfer"
            StoreFull -> "StoreFull"
            DeviceReset -> "DeviceReset"
            StorageInfoChanged -> "StorageInfoChanged"
            CaptureComplete -> "CaptureComplete"
            UnreportedStatus -> "UnreportedStatus"
            else -> Container.getCodeString(code)
        }
    }
}
