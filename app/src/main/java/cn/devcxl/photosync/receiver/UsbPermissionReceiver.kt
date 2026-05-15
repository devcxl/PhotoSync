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
import timber.log.Timber

/**
 * Broadcast receiver that handles USB permission grant/deny results.
 */
class UsbPermissionReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_USB_PERMISSION = "cn.devcxl.photosync.USB_PERMISSION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Timber.d("USB permission result: granted=%s", granted)
                (context.applicationContext as? App)
                    ?.usbPtpConnectionController
                    ?.onUsbPermissionResult(device, granted)
                if (granted) {
                    Toast.makeText(context, context.getString(R.string.usb_permission_granted), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.usb_permission_denied), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}