package cn.devcxl.photosync.ptp.usbcamera

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import cn.devcxl.photosync.ptp.detect.CameraDetector
import cn.devcxl.photosync.ptp.usbcamera.eos.EosInitiator
import cn.devcxl.photosync.ptp.usbcamera.nikon.NikonInitiator
import cn.devcxl.photosync.ptp.usbcamera.sony.SonyInitiator
import timber.log.Timber

class InitiatorFactory {
    companion object {
        val TAG: String = InitiatorFactory::class.java.name

        @JvmStatic
        @Throws(PTPException::class)
        fun produceInitiator(device: UsbDevice, usbManager: UsbManager): BaselineInitiator {
            val connection = usbManager.openDevice(device)
                ?: throw PTPException("Unable to open USB device: ${device.deviceName}")
            val cd = CameraDetector(device)
            return when (cd.getSupportedVendorId()) {
                CameraDetector.VENDOR_ID_CANON -> {
                    Timber.tag(TAG).d("Device is CANON, open EOSInitiator")
                    EosInitiator(device, connection)
                }
                CameraDetector.VENDOR_ID_NIKON -> {
                    Timber.tag(TAG).d("Device is Nikon, open NikonInitiator")
                    NikonInitiator(device, connection)
                }
                CameraDetector.VENDOR_ID_SONY -> {
                    Timber.tag(TAG).d("Device is Sony, open SonyInitiator")
                    SonyInitiator(device, connection)
                }
                else -> {
                    Timber.tag(TAG).d("Unknown device, open BaselineInitiator")
                    BaselineInitiator(device, connection)
                }
            }
        }
    }
}
