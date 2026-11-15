package cn.devcxl.photosync.ptp.detect

import android.hardware.usb.UsbConstants

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDetectionTest {

    @Test
    fun shouldReportCamera_whenDeviceClassIsStillImage() {
        val traits = UsbDeviceTraits(
            deviceClass = UsbConstants.USB_CLASS_STILL_IMAGE,
            interfaceClasses = emptyList(),
            vendorId = 0x1234
        )

        assertTrue(isSupportedCamera(traits))
    }

    @Test
    fun shouldReportCamera_whenOnlyInterfaceIsStillImage() {
        val traits = UsbDeviceTraits(
            deviceClass = UsbConstants.USB_CLASS_PER_INTERFACE,
            interfaceClasses = listOf(UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_STILL_IMAGE),
            vendorId = 0x1234
        )

        assertTrue(isSupportedCamera(traits))
    }

    @Test
    fun shouldReportCamera_whenVendorIsSupportedEvenWithVendorSpecificClass() {
        val traits = UsbDeviceTraits(
            deviceClass = UsbConstants.USB_CLASS_VENDOR_SPEC,
            interfaceClasses = listOf(UsbConstants.USB_CLASS_VENDOR_SPEC),
            vendorId = CameraDetector.VENDOR_ID_CANON
        )

        assertTrue(isSupportedCamera(traits))
    }

    @Test
    fun shouldIgnoreDevice_whenClassAndVendorAreUnknown() {
        val traits = UsbDeviceTraits(
            deviceClass = UsbConstants.USB_CLASS_MASS_STORAGE,
            interfaceClasses = listOf(UsbConstants.USB_CLASS_MASS_STORAGE),
            vendorId = 0x1234
        )

        assertFalse(isSupportedCamera(traits))
    }
}
