package cn.devcxl.photosync

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.hardware.usb.UsbManager
import cn.devcxl.photosync.receiver.UsbPermissionReceiver
import cn.devcxl.photosync.receiver.UsbReceiver
import cn.devcxl.photosync.wrapper.RawWrapper
import org.opencv.android.OpenCVLoader
import timber.log.Timber

/**
 * Application entry point for the PhotoSync app.
 *
 * Responsibilities:
 * - Initialize global SDKs (OpenCV, native Raw wrapper) and logging.
 * - Register USB broadcast receivers for device attach/detach and permission results.
 *
 * This class is intentionally lightweight; platform- or DI-based initialization can be
 * introduced later if needed.
 */
@Suppress("DEPRECATION")
class App : Application() {

    private var usbReceiver: UsbReceiver = UsbReceiver()
    private val usbPermissionReceiver: UsbPermissionReceiver = UsbPermissionReceiver()
    private val deviceFilter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    private val permissionFilter = IntentFilter().apply {
        addAction(ACTION_USB_PERMISSION)
    }

    init {
        if (!OpenCVLoader.initDebug()) {
            Timber.e("无法初始化 OpenCV")
        } else {
            Timber.d("OpenCV 初始化成功")
        }
    }

    companion object {
        private var instance: App? = null
        const val ACTION_USB_PERMISSION = "cn.devcxl.photosync.USB_PERMISSION"
        fun get(): App? {
            return instance
        }


    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // initialize Timber for logging — use debuggable flag instead of generated BuildConfig
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) Timber.plant(Timber.DebugTree())

        try {
            val ver = RawWrapper.version()
            Timber.i("Loaded LibRaw version: %s", ver)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to load LibRaw")
        }
        // Provide explicit export policy for dynamically registered receivers per Android 12+ requirements
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, deviceFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, deviceFilter)
            registerReceiver(usbPermissionReceiver, permissionFilter)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(usbPermissionReceiver)
    }

}
