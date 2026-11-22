package cn.devcxl.photosync

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbManager

import timber.log.Timber

import cn.devcxl.photosync.activity.clearPhotoEditCacheDir
import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.RoomEditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.export.MediaStoreGallerySink
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import cn.devcxl.photosync.receiver.UsbPermissionReceiver
import cn.devcxl.photosync.receiver.UsbReceiver
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Application entry point for the PhotoSync app.
 *
 * Responsibilities:
 * - Initialize global SDKs (native Raw wrapper) and logging.
 * - Register USB broadcast receivers for device attach/detach and permission results.
 *
 * This class is intentionally lightweight; platform- or DI-based initialization can be
 * introduced later if needed.
 */
@Suppress("DEPRECATION")
class App : Application() {

    private val usbReceiver: UsbReceiver = UsbReceiver()
    private val usbPermissionReceiver: UsbPermissionReceiver = UsbPermissionReceiver()
    private val usbEventFilter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    private val usbPermissionFilter = IntentFilter().apply {
        addAction(ACTION_USB_PERMISSION)
    }

    val usbPtpConnectionController: UsbPtpConnectionController by lazy {
        UsbPtpConnectionController(this)
    }

    companion object {
        private var instance: App? = null
        const val ACTION_USB_PERMISSION = "cn.devcxl.photosync.USB_PERMISSION"
        fun get(): App {
            return instance!!
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.plant(Timber.DebugTree())

        val db = AppDatabase.getInstance(this)
        EditorHostRegistry.editStore = RoomEditStore(
            photoEditDao = db.photoEditDao(),
            onClearPhotoEditCache = { photoId -> clearPhotoEditCacheDir(cacheDir, photoId) }
        )
        EditorHostRegistry.gallerySink = MediaStoreGallerySink(contentResolver)

        try {
            val ver = RawWrapper.version()
            Timber.i("Loaded LibRaw version: %s", ver)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load LibRaw")
        }
        // Provide explicit export policy for dynamically registered receivers per Android 12+ requirements
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbEventFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbPermissionReceiver, usbPermissionFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, usbEventFilter)
            registerReceiver(usbPermissionReceiver, usbPermissionFilter)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(usbPermissionReceiver)
    }

}
