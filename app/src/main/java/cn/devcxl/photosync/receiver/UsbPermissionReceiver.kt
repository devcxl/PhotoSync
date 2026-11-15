package cn.devcxl.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.devcxl.photosync.App
import timber.log.Timber

/**
 * Broadcast receiver that wakes the connection controller after a USB permission dialog.
 */
class UsbPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != App.ACTION_USB_PERMISSION) return
        // The extras are dropped by the immutable permission PendingIntent, so this
        // broadcast only wakes the controller, which re-checks UsbManager.hasPermission().
        Timber.d("USB permission result received, re-checking device permission")
        (context.applicationContext as? App)
            ?.usbPtpConnectionController
            ?.onUsbPermissionResult()
    }

}
