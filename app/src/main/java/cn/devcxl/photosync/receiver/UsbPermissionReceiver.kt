package cn.devcxl.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.widget.Toast
import cn.devcxl.photosync.R
import timber.log.Timber

class UsbPermissionReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_USB_PERMISSION = "cn.devcxl.photosync.USB_PERMISSION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Timber.d("USB permission result: granted=%s", granted)
                if (granted) {
                    Toast.makeText(context, context.getString(R.string.usb_permission_granted), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.usb_permission_denied), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}