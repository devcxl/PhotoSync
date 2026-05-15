package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
internal class KodakExtension : NameFactory() {

    override fun getOpcodeString(code: Int): String = when (code) {
        SendFileObjectInfo -> "Kodak_SendFileObjectInfo"
        SendFileObject -> "Kodak_SendFileObject"
        else -> Command._getOpcodeString(code)
    }

    override fun getResponseString(code: Int): String = when (code) {
        FilenameRequired -> "Kodak_FilenameRequired"
        FilenameConflicts -> "Kodak_FilenameConflicts"
        FilenameInvalid -> "Kodak_FilenameInvalid"
        else -> Response._getResponseString(code)
    }

    override fun getFormatString(code: Int): String = when (code) {
        Firmware -> "Kodak_Firmware"
        M3U -> "Kodak_M3U"
        else -> ObjectInfo._getFormatString(code)
    }

    override fun getPropertyName(code: Int): String = when (code) {
        prop1 -> "Kodak_prop1"
        prop2 -> "Kodak_prop2"
        prop3 -> "Kodak_prop3"
        prop4 -> "Kodak_prop4"
        prop5 -> "Kodak_prop5"
        prop6 -> "Kodak_prop6"
        else -> DevicePropDesc._getPropertyName(code)
    }

    companion object {
        @JvmField val SendFileObjectInfo: Int = 0x9005
        @JvmField val SendFileObject: Int = 0x9006
        @JvmField val FilenameRequired: Int = 0xa001
        @JvmField val FilenameConflicts: Int = 0xa002
        @JvmField val FilenameInvalid: Int = 0xa003
        @JvmField val Firmware: Int = 0xb001
        @JvmField val M3U: Int = 0xb002
        @JvmField val prop1: Int = 0xd001
        @JvmField val prop2: Int = 0xd002
        @JvmField val prop3: Int = 0xd003
        @JvmField val prop4: Int = 0xd004
        @JvmField val prop5: Int = 0xd005
        @JvmField val prop6: Int = 0xd006
    }
}
