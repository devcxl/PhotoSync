package cn.devcxl.photosync.activity

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.devcxl.photosync.R
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
import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.databinding.ActivityMainBinding
import cn.devcxl.photosync.utils.ExtensionUtils
import cn.devcxl.photosync.wrapper.RawWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private var isOpenConnected: Boolean = false
    private var bi: BaselineInitiator? = null

    companion object {
        private const val REQ_WRITE_STORAGE = 2001
        // use App.ACTION_USB_PERMISSION instead of duplicating the constant
    }

    private lateinit var adapter: PhotoPagerAdapter
    private var pendingExportIndex: Int? = null

    private lateinit var binding: ActivityMainBinding

    private lateinit var dao: PhotoDao
    private var pendingRevealPath: String? = null

    private val bitmapCache: LruCache<String, Bitmap> by lazy {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemKb / 8 // Use 1/8 of available memory
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // init DB/DAO
        dao = AppDatabase.getInstance(this).photoDao()

        // Initialize adapter backed by DB + bitmap cache
        adapter = PhotoPagerAdapter(
            bitmapProvider = { path -> bitmapCache.get(path) },
            onBindRequest = { path ->
                // load bitmap thumbnail/full for the page on background (RAW only)
                decodeBestEffortBitmap(path)
            }
        )

        // Setup ViewPager2
        binding.viewPager.adapter = adapter

        // Observe DB
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getAllFlow().collect { list ->
                    adapter.updateItems(list.toList())
                    // Scroll to newly inserted item if pending
                    pendingRevealPath?.let { target ->
                        val idx = list.indexOfFirst { it.path == target }
                        if (idx >= 0) {
                            binding.viewPager.setCurrentItem(idx, true)
                            pendingRevealPath = null
                        }
                    }
                }
            }
        }

        // Setup export button click listener
        binding.exportButton.setOnClickListener { exportCurrent() }


        enableEdgeToEdge()

        connectMTPDevice()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (isOpenConnected) {
            bi!!.closeSession()
            isOpenConnected = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isOpenConnected) {
            bi!!.closeSession()
            isOpenConnected = false
        }
    }

    fun connectMTPDevice() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val map: MutableMap<String?, UsbDevice?> = usbManager.getDeviceList()
        val set = map.keys

        if (set.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_usb_device), Toast.LENGTH_LONG).show()
            return
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
        val usbManager: UsbManager = getSystemService(USB_SERVICE) as UsbManager
        val piFlags = PendingIntent.FLAG_IMMUTABLE
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(cn.devcxl.photosync.App.ACTION_USB_PERMISSION), piFlags
        )
        usbManager.requestPermission(device, permissionIntent)
        Timber.d("请求设备权限 %s", device.deviceName)
    }

    fun performConnect(device: UsbDevice?) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager?

        if (!isOpenConnected) {
            try {
                bi = InitiatorFactory.produceInitiator(device, usbManager)
                bi!!.getClearStatus() // ????
                bi!!.setSyncTriggerMode(SyncParams.SYNC_TRIGGER_MODE_POLL_LIST)
                if (bi is SonyInitiator) {
                    // 索尼只能支持event 模式
                    bi!!.setSyncTriggerMode(SyncParams.SYNC_TRIGGER_MODE_EVENT)
                }
                bi!!.openSession()

                isOpenConnected = true

                bi!!.setFileDownloadPath(externalCacheDir!!.absolutePath)
                bi!!.setFileTransferListener { _, _, totalByteLength, transterByteLength ->
                    val progressPercent = if (totalByteLength > 0) {
                        ((transterByteLength * 100L) / totalByteLength).toInt().coerceIn(0, 100)
                    } else 0
                    // update UI on Main via lifecycleScope
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (binding.progressBar.visibility != View.VISIBLE) {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        binding.progressBar.progress = progressPercent
                    }
                }

                bi!!.setFileDownloadedListener { _, fileHandle, localFile, timeduring ->
                    Timber.v("file (%s) downloaded at %s, time: %sms", fileHandle, localFile.absolutePath, timeduring)
                    lifecycleScope.launch(Dispatchers.Main) { binding.progressBar.visibility = View.GONE }

                    // 根据扩展名判断是否为RAW或JPEG，满足则添加到数据库
                    val ext = localFile.extension.lowercase(Locale.ROOT)
                    if (ExtensionUtils.isRawExtension(ext) || ExtensionUtils.isJpegExtension(ext)) {
                        insertItemAndReveal(localFile)
                    }
                }

                Toast.makeText(this, getString(R.string.toast_device_connected), Toast.LENGTH_LONG).show()
            } catch (e: PTPException) {
                // TODO Auto-generated catch block
                Timber.e(e, "performConnect failed")
            }
        } else {
            Timber.i("设备已经连接，无需重复连接")
        }
    }

    private fun insertItemAndReveal(localFile: File) {
        val entity = PhotoEntity(
            path = localFile.absolutePath,
            name = localFile.name,
            uriString = null
        )
        pendingRevealPath = entity.path
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                dao.insert(entity)
            } catch (t: Throwable) {
                Timber.e(t, "DB insert failed")
            }
        }
    }

    private fun decodeBestEffortBitmap(path: String) {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // 仅对RAW进行解码，JPEG不在此处解码
        if (!ExtensionUtils.isRawExtension(ext)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp: Bitmap? = try {
                RawWrapper.decodeToBitmap(path)
            } catch (t: Throwable) {
                Timber.w(t, "decodeToBitmap failed for path=%s", path)
                null
            }
            if (bmp != null) {
                withContext(Dispatchers.Main) {
                    bitmapCache.put(path, bmp)
                    adapter.notifyPathChanged(path)
                }
            }
        }
    }

    private fun exportCurrent() {
        if (adapter.itemCount == 0) {
            Toast.makeText(this, getString(R.string.toast_no_exportable_photos), Toast.LENGTH_SHORT).show()
            return
        }
        val index = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
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


    private fun exportIndex(index: Int) {
        val entity = adapter.getItem(index) ?: return
        val displayEntityName = entity.name ?: getString(R.string.photo_default_name)
        Toast.makeText(this, getString(R.string.toast_exporting, displayEntityName), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val path = entity.path
            if (path.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_export_failed_invalid_path), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

            val displayName = makeExportName(entity.name)
            val saved: Uri? = try {
                if (ExtensionUtils.isJpegExtension(ext)) {
                    // JPEG：直接复制原文件到相册，避免解码与重压缩
                    saveFileToGalleryByCopying(File(path), displayName)
                } else if (ExtensionUtils.isRawExtension(ext)) {
                    // RAW：优先使用缓存Bitmap，否则解码
                    val bmp = bitmapCache.get(path) ?: try {
                        RawWrapper.decodeToBitmap(path)
                    } catch (_: Throwable) {
                        null
                    }
                    if (bmp != null) saveBitmapToGallery(bmp, displayName) else null
                } else {
                    null
                }
            } catch (e: Exception) {
                Timber.e(e, "exportIndex: save failed")
                null
            }

            withContext(Dispatchers.Main) {
                if (saved != null) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_saved_to_gallery, displayName), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_save_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val idx = pendingExportIndex
            pendingExportIndex = null
            if (granted && idx != null) {
                exportIndex(idx)
            } else if (!granted) {
                Toast.makeText(this, getString(R.string.toast_storage_permission_denied), Toast.LENGTH_LONG).show()
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
            Timber.e(e, "saveBitmapToGallery failed")
            null
        }
    }

    // 直接复制文件至相册（用于JPEG等已经是目标格式的文件）
    private fun saveFileToGalleryByCopying(srcFile: File, displayName: String, mime: String = "image/jpeg"): Uri? {
        return try {
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
                        srcFile.inputStream().use { input ->
                            input.copyTo(out)
                            out.flush()
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } else {
                val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val dir = File(pictures, "PhotoSync").apply { if (!exists()) mkdirs() }
                val dst = File(dir, displayName)
                srcFile.inputStream().use { input ->
                    dst.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, dst.absolutePath)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Timber.e(e, "saveFileToGalleryByCopying failed")
            null
        }
    }

}
