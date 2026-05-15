package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class NameFactory {

    fun updateFactory(vendorExtensionId: Int): NameFactory = when (vendorExtensionId) {
        0 -> this
        1 -> KodakExtension()
        else -> {
            System.err.println("Don't know extension $vendorExtensionId")
            this
        }
    }

    open fun getOpcodeString(code: Int): String = Command._getOpcodeString(code)

    open fun getResponseString(code: Int): String = Response._getResponseString(code)

    open fun getFormatString(code: Int): String = ObjectInfo._getFormatString(code)

    open fun getEventString(code: Int): String = Event._getEventString(code)

    open fun getPropertyName(code: Int): String = DevicePropDesc._getPropertyName(code)
}
