package cn.devcxl.photosync.ptp.detect

import android.hardware.usb.UsbDevice
import timber.log.Timber

/**
 * @author devcxl
 */
class CameraDetector(private val device: UsbDevice) {
    fun getSupportedVendorId(): Int {
        return if (vendorIds.contains(device.vendorId)) {
            device.vendorId
        } else {
            VENDOR_ID_OTHER
        }
    }

    fun getDeviceUniqName(): String {
        val manufacturer = device.manufacturerName
        val product = device.productName
        val serial = try {
            device.serialNumber
        } catch (se: SecurityException) {
            Timber.w(se, "No permission to read serial number; using fallback")
            "no-permission-${device.vendorId}:${device.productId}"
        }
        return "${manufacturer}_${product}_${serial}"
    }

    companion object {
        const val VENDOR_ID_CANON: Int = 0x04a9
        const val VENDOR_ID_NIKON: Int = 0x04b0
        const val VENDOR_ID_SONY: Int = 0x054c

        const val VENDOR_ID_OTHER: Int = 0xffff

        private val vendorIds = listOf(
            VENDOR_ID_CANON,
            VENDOR_ID_NIKON,
            VENDOR_ID_SONY
        )
    }
}
