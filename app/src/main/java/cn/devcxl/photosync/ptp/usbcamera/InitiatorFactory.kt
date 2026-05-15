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
            val bi: BaselineInitiator
            val cd = CameraDetector(device)
            when (cd.getSupportedVendorId()) {
                CameraDetector.VENDOR_ID_CANON -> {
                    Timber.tag(TAG).d("Device is CANON, open EOSInitiator")
                    bi = EosInitiator(device, usbManager.openDevice(device))
                }
                CameraDetector.VENDOR_ID_NIKON -> {
                    Timber.tag(TAG).d("Device is Nikon, open NikonInitiator")
                    bi = NikonInitiator(device, usbManager.openDevice(device))
                }
                CameraDetector.VENDOR_ID_SONY -> {
                    Timber.tag(TAG).d("Device is Sony, open SonyInitiator")
                    bi = SonyInitiator(device, usbManager.openDevice(device))
                }
                else -> {
                    Timber.tag(TAG).d("Unknown device, open BaselineInitiator")
                    bi = BaselineInitiator(device, usbManager.openDevice(device))
                }
            }
            return bi
        }
    }
}
