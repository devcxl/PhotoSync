package cn.devcxl.photosync

import android.app.Application
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.util.Log
import cn.devcxl.photosync.receiver.UsbPermissionReceiver
import cn.devcxl.photosync.receiver.UsbReceiver
import cn.devcxl.photosync.wrapper.RawWrapper


/**
 * @author devcxl
 */
class App : Application() {

    private var usbReceiver: UsbReceiver = UsbReceiver()
    private val usbPermissionReceiver: UsbPermissionReceiver = UsbPermissionReceiver()
    private val filter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    companion object {
        private var instance: App? = null
        fun get(): App? {
            return instance
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val ver = RawWrapper.version()
            Log.i("LibRaw", "Loaded LibRaw version: $ver")
        } catch (t: Throwable) {
            Log.e("LibRaw", "Failed to load LibRaw: ${t.message}", t)
        }
        registerReceiver(usbReceiver, filter)
        registerReceiver(usbPermissionReceiver, filter)
    }

    override fun onTerminate() {
        super.onTerminate()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(usbPermissionReceiver)
    }

}

