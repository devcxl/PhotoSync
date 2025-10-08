package cn.devcxl.photosync.activity

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cn.devcxl.photosync.ptp.params.SyncParams
import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.InitiatorFactory
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.sony.SonyInitiator
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.databinding.ActivityTakingPhotoBinding
import cn.devcxl.photosync.utils.ImageDenoiseUtils
import cn.devcxl.photosync.wrapper.RawWrapper

class TakingPhotoActivity : ComponentActivity() {

    private val TAG = "TakingPhotoActivity"
    private var isOpenConnected: Boolean = false
    private var bi: BaselineInitiator? = null
    private var isOpenCVReady = false

    companion object {
        private const val REQ_WRITE_STORAGE = 2001
        private const val ACTION_USB_PERMISSION = "cn.devcxl.photosync.USB_PERMISSION"
    }

    // Gallery components
    private val items = mutableListOf<PhotoItem>()
    private lateinit var adapter: PhotoPagerAdapter
    private var pendingExportIndex: Int? = null

    private lateinit var binding: ActivityTakingPhotoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTakingPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE

        // Initialize adapter
        adapter = PhotoPagerAdapter(items) { item ->
            // load bitmap thumbnail/full for the page on background
            decodeBestEffortBitmap(item)
        }

        // Setup ViewPager2
        binding.viewPager.adapter = adapter

        // Setup export button click listener
        binding.exportButton.setOnClickListener { exportCurrent() }

        binding.denoise.setOnClickListener {
            denoiseBitmap()
        }

        enableEdgeToEdge()

        getAllUsbDevices()
        connectMTPDevice()
    }

    override fun onResume() {
        super.onResume()
        // 简化的OpenCV初始化
        try {
            // 尝试直接初始化OpenCV
            if (org.opencv.android.OpenCVLoader.initDebug()) {
                Log.d(TAG, "OpenCV库初始化成功")
                isOpenCVReady = true
            } else {
                Log.w(TAG, "OpenCV库初始化失败")
                isOpenCVReady = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV初始化异常", e)
            isOpenCVReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }


    fun getAllUsbDevices() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val deviceList = manager.getDeviceList()
        val deviceIterator: MutableIterator<UsbDevice> = deviceList.values.iterator()
        var i = 0
        while (deviceIterator.hasNext()) {
            i++
            val device = deviceIterator.next()
            Log.i(TAG, "--------")
            Log.i(TAG, "设备 ： $i")
            Log.i(TAG, "device id : " + device.deviceId)
            Log.i(TAG, "name : " + device.deviceName)
            Log.i(TAG, "class : " + device.deviceClass)
            Log.i(TAG, "subclass : " + device.deviceSubclass)
            Log.i(TAG, "vendorId : " + device.vendorId)
            // Log.i(TAG,"version : " + device.getVersion() );
            // Accessing serial requires permission; guard to avoid SecurityException
            if (manager.hasPermission(device)) {
                try {
                    Log.i(TAG, "serial number : " + device.serialNumber)
                } catch (se: SecurityException) {
                    Log.w(TAG, "No permission to read serial number", se)
                }
            } else {
                Log.i(TAG, "serial number : <no permission>")
            }
            Log.i(TAG, "interface count : " + device.interfaceCount)
            Log.i(TAG, "device protocol : " + device.deviceProtocol)
            Log.i(TAG, "--------")
        }
    }

    fun connectMTPDevice() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        Log.v(TAG, "connectMTPDevice: $usbManager")
        val map: MutableMap<String?, UsbDevice?> = usbManager.getDeviceList()
        val set = map.keys

        if (set.isEmpty()) {
            Log.v(TAG, "无法获取设备信息，请确保相机已经连接或者处于激活状态")
        }

        for (s in set) {
            val device = map[s]
            if (device == null) continue
            if (!usbManager.hasPermission(device)) {
                requestUsbPermission(device)
                continue
            } else {
                performConnect(device)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val piFlags = PendingIntent.FLAG_IMMUTABLE
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), piFlags
        )
        usbManager.requestPermission(device, permissionIntent)
        Log.d(TAG, "请求设备权限 ${device.deviceName}")
    }

    fun performConnect(device: UsbDevice?) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager?

        if (!isOpenConnected) {
            try {
                bi = InitiatorFactory.produceInitiator(device, usbManager)
                bi!!.getClearStatus() // ?????
                bi!!.setSyncTriggerMode(SyncParams.SYNC_TRIGGER_MODE_POLL_LIST)
                if (bi is SonyInitiator) {
                    // 索尼只能支持event 模式
                    bi!!.setSyncTriggerMode(SyncParams.SYNC_TRIGGER_MODE_EVENT)
                }
                bi!!.openSession()

                isOpenConnected = true

                bi!!.setFileDownloadPath(externalCacheDir!!.absolutePath)
                bi!!.setFileTransferListener { _, fileHandle, totalByteLength, transterByteLength ->
                    val progressPercent = if (totalByteLength > 0) {
                        ((transterByteLength * 100L) / totalByteLength).toInt().coerceIn(0, 100)
                    } else 0
                    runOnUiThread {
                        if (binding.progressBar.visibility != View.VISIBLE) {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        binding.progressBar.progress = progressPercent
                    }
                }

                bi!!.setFileDownloadedListener { _, fileHandle, localFile, timeduring ->
                    Log.v(
                        TAG,
                        "file (" + fileHandle + ") downloaded at " + localFile.absolutePath + ",time: " + timeduring + "ms"
                    )
                    runOnUiThread { binding.progressBar.visibility = View.GONE }

                    // 仅处理 CR3 文件，添加到画廊并尝试解码
                    if (localFile.extension.equals("cr3", ignoreCase = true)) {
                        addItemAndReveal(localFile)
                    }
                }
            } catch (e: PTPException) {
                // TODO Auto-generated catch block
                e.printStackTrace()
                Log.e(TAG, e.toString())
            }
        } else {
            Log.i(TAG, "设备已经连接，无需重复连接")
        }
    }

    private fun addItemAndReveal(localFile: File) {
        val item = PhotoItem(path = localFile.absolutePath, name = localFile.name)
        runOnUiThread {
            items.add(item)
            adapter.notifyItemInserted(items.size - 1)
            binding.viewPager.setCurrentItem(items.size - 1, true)
        }
    }

    private fun decodeBestEffortBitmap(item: PhotoItem) {
        val path = item.path ?: return
        Thread {
            val bmpThumb = try {
                RawWrapper.decodeToBitmap(path)
            } catch (t: Throwable) {
                null
            }
            val bmp = bmpThumb ?: try {
                RawWrapper.decodeToBitmap(path)
            } catch (t: Throwable) {
                null
            }
            if (bmp != null) {
                runOnUiThread {
                    val index = items.indexOfFirst { it.path == item.path }
                    if (index >= 0) {
                        items[index] = item.copy(bitmap = bmp)
                        adapter.notifyItemChanged(index)
                    }
                }
            }
        }.start()
    }

    private fun exportCurrent() {
        if (items.isEmpty()) {
            Toast.makeText(this, "没有可导出的照片", Toast.LENGTH_SHORT).show()
            return
        }
        val index = binding.viewPager.currentItem.coerceIn(0, items.lastIndex)
        if (Build.VERSION.SDK_INT <= 28) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportIndex = index
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_WRITE_STORAGE
                )
                return
            }
        }
        exportIndex(index)
    }


    private fun denoiseBitmap() {

        Toast.makeText(this, "开始降噪！！！", Toast.LENGTH_SHORT).show()
        if (items.isEmpty()) {
            Toast.makeText(this, "没有可导出的照片", Toast.LENGTH_SHORT).show()
            return
        }
        val index = binding.viewPager.currentItem.coerceIn(0, items.lastIndex)

        val item = items[index]

        Thread {
            val denoise = ImageDenoiseUtils.multiScaleDenoise(item.bitmap, 5, 10)

            runOnUiThread {
                val index = items.indexOfFirst { it.path == item.path }
                if (index >= 0) {
                    items[index] = item.copy(bitmap = denoise)
                    adapter.notifyItemChanged(index)

                    Toast.makeText(this, "降噪成功！！！", Toast.LENGTH_SHORT).show()
                }
            }

        }.start()
    }


    private fun exportIndex(index: Int) {
        val item = items[index]
        Toast.makeText(this, "正在导出 ${item.name ?: "照片"}", Toast.LENGTH_SHORT).show()
        Thread {
            val path = item.path
            val originalBmp = if (path != null) {
                try {
                    RawWrapper.decodeToBitmap(path)
                } catch (t: Throwable) {
                    null
                }
            } else null

            if (originalBmp == null) {
                runOnUiThread {
                    Toast.makeText(this, "导出失败：解码失败", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            val displayName = makeExportName(item.name)
            val saved = saveBitmapToGallery(originalBmp, displayName)
            runOnUiThread {
                if (saved != null) {
                    Toast.makeText(this, "已保存到相册: ${displayName}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val idx = pendingExportIndex
            pendingExportIndex = null
            if (granted && idx != null) {
                exportIndex(idx)
            } else if (!granted) {
                Toast.makeText(this, "未授予存储权限，无法导出", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun makeExportName(name: String?): String {
        return if (!name.isNullOrBlank()) {
            name.substringBeforeLast('.') + "_export.jpg"
        } else {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "IMG_${ts}.jpg"
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, displayName: String): Uri? {
        return try {
            val mime = "image/jpeg"
            val quality = 100
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoSync")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        out.flush()
                        if (!ok) throw RuntimeException("JPEG compress failed")
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } else {
                val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val dir = File(pictures, "PhotoSync").apply { if (!exists()) mkdirs() }
                val file = File(dir, displayName)
                FileOutputStream(file).use { out ->
                    val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.flush()
                    if (!ok) throw RuntimeException("JPEG compress failed")
                }
                // Insert into MediaStore on older devices so it shows up in gallery
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, file.absolutePath)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToGallery failed", e)
            null
        }
    }
}

data class PhotoItem(
    val path: String? = null,
    val uri: Uri? = null,
    val name: String? = null,
    val bitmap: Bitmap? = null
)
