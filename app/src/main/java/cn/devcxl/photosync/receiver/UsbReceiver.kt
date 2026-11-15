package cn.devcxl.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.Toast
import cn.devcxl.photosync.App
import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.detect.isSupportedCamera
import cn.devcxl.photosync.ptp.detect.toUsbDeviceTraits
import timber.log.Timber

/**
 * Broadcast receiver that listens for USB device attach/detach events.
 *
 * Attaching a camera only refreshes the attached-camera list: opening a session is the
 * user's call, so this receiver never connects.
 */
class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val controller = (context.applicationContext as? App)?.usbPtpConnectionController
        // Stay quiet for devices this app cannot open: a hub or a card reader is not
        // PhotoSync's business.
        val isCamera = device != null && isSupportedCamera(device.toUsbDeviceTraits())
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Timber.d("USB device attached: %s", intent)
                controller?.refreshAvailableCameras()
                if (isCamera) {
                    Toast.makeText(context, context.getString(R.string.usb_inserted), Toast.LENGTH_SHORT).show()
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Timber.d("USB device detached: %s", intent)
                controller?.onUsbDetached(device)
                if (isCamera) {
                    Toast.makeText(context, context.getString(R.string.usb_removed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
