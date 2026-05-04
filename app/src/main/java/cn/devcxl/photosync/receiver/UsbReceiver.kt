package cn.devcxl.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.widget.Toast
import cn.devcxl.photosync.App
import cn.devcxl.photosync.R
import timber.log.Timber

/**
 * Broadcast receiver that listens for USB device attach/detach events.
 */
class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        val controller = (context.applicationContext as? App)?.usbPtpConnectionController
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Timber.d("USB device attached: %s", intent)
                Toast.makeText(context, context.getString(R.string.usb_inserted), Toast.LENGTH_SHORT).show()
                controller?.onUsbAttached(device)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Timber.d("USB device detached: %s", intent)
                Toast.makeText(context, context.getString(R.string.usb_removed), Toast.LENGTH_SHORT).show()
                controller?.onUsbDetached(device)
            }
        }
    }
}