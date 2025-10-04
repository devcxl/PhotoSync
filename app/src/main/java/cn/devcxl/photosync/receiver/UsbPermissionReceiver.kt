package cn.devcxl.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import android.widget.Toast

class UsbPermissionReceiver : BroadcastReceiver() {

    private val TAG = "UsbPermissionReceiver"

    companion object {
        private const val ACTION_USB_PERMISSION = "cn.devcxl.photosync.USB_PERMISSION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: granted=$granted")
                if (granted) {
                    Toast.makeText(context, "已授权访问USB设备", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "未授权访问USB设备", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

//    // Broadcast receiver for USB permission result
//    private val usbPermissionReceiver = object : android.content.BroadcastReceiver() {
//        override fun onReceive(context: android.content.Context?, intent: Intent?) {
//            if (intent?.action == TakingPhotoActivity.Companion.ACTION_USB_PERMISSION) {
//                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
//                } else {
//                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
//                }
//                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
//                Log.d(TAG, "USB permission result: granted=$granted, device=${device?.deviceName}")
//                if (granted && device != null) {
//                    performConnect(device)
//                } else {
//                    Toast.makeText(this@TakingPhotoActivity, "未授权访问USB设备", Toast.LENGTH_LONG).show()
//                }
//            }
//        }
//    }
}