package cn.devcxl.photosync.ptp.detect

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * The USB descriptors that decide whether a device looks like a camera.
 *
 * Held separately from [UsbDevice] so the decision can be unit tested on the JVM.
 */
internal data class UsbDeviceTraits(
    val deviceClass: Int,
    val interfaceClasses: List<Int>,
    val vendorId: Int
)

internal fun UsbDevice.toUsbDeviceTraits(): UsbDeviceTraits {
    return UsbDeviceTraits(
        deviceClass = deviceClass,
        interfaceClasses = (0 until interfaceCount).map { getInterface(it).interfaceClass },
        vendorId = vendorId
    )
}

/**
 * Whether the device is a camera we may speak PTP to.
 *
 * The signals are unioned because PTP cameras advertise themselves in all three shapes:
 * device class 6, device class 0 with interface class 6, or a vendor-specific class from
 * a vendor whose protocol we already implement.
 */
internal fun isSupportedCamera(traits: UsbDeviceTraits): Boolean {
    if (traits.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
        return true
    }
    if (traits.interfaceClasses.any { it == UsbConstants.USB_CLASS_STILL_IMAGE }) {
        return true
    }
    return traits.vendorId in CameraDetector.supportedVendorIds
}
