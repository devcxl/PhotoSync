package cn.devcxl.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.widget.Toast
import cn.devcxl.photosync.R
import timber.log.Timber

class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Timber.d("USB device attached: %s", intent)
                Toast.makeText(context, context.getString(R.string.usb_inserted), Toast.LENGTH_SHORT).show()
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Timber.d("USB device detached: %s", intent)
                Toast.makeText(context, context.getString(R.string.usb_removed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}