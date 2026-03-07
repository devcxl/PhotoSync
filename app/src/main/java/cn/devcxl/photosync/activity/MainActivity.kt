package cn.devcxl.photosync.activity

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewpager2.widget.ViewPager2
import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.JpegSourceInfo
import cn.devcxl.photosync.adapter.PhotoRenderState
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
import android.app.AlertDialog
import java.util.Collections
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var isOpenConnected: Boolean = false
    private var bi: BaselineInitiator? = null

    companion object {
        private const val REQ_WRITE_STORAGE = 2001
        private const val THUMBNAIL_MAX_EDGE_PX = 512
        private const val FULL_PREVIEW_SCALE_FACTOR = 2
        private const val FULL_PREVIEW_MAX_EDGE_PX = 4096
        // use App.ACTION_USB_PERMISSION instead of duplicating the constant
    }

    private lateinit var adapter: PhotoPagerAdapter
    private var pendingExportIndex: Int? = null

    private lateinit var binding: ActivityMainBinding

    private lateinit var dao: PhotoDao
    private var pendingRevealPath: String? = null

    private val thumbnailCache: LruCache<String, Bitmap> by lazy { createBitmapCache(16) }
    private val fullBitmapCache: LruCache<String, Bitmap> by lazy { createBitmapCache(8) }
    private val inFlightThumbnailPaths = Collections.synchronizedSet(mutableSetOf<String>())
    private val inFlightFullPaths = Collections.synchronizedSet(mutableSetOf<String>())
    private val jpegSourceInfoCache = Collections.synchronizedMap(mutableMapOf<String, JpegSourceInfo>())
    private val previewLongEdgePx: Int by lazy {
        maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .coerceAtLeast(1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // init DB/DAO
        dao = AppDatabase.getInstance(this).photoDao()

        // Initialize adapter backed by two-level cache.
        adapter = PhotoPagerAdapter(
            renderStateProvider = { path ->
                PhotoRenderState(
                    thumbnail = thumbnailCache.get(path),
                    full = fullBitmapCache.get(path)
                )
            },
            onBindRequest = { path, position ->
                ensurePhotoForPosition(path, position)
            },
            jpegSourceInfoProvider = { path -> getJpegSourceInfo(path) },
            isCurrentPageProvider = { position -> position == binding.viewPager.currentItem },
            isJpegProvider = { path -> isJpegPath(path) },
            onPhotoScaleChanged = { _, _, _, _ -> }
        )

        // Setup ViewPager2
        binding.viewPager.adapter = adapter
        (binding.viewPager.getChildAt(0) as? RecyclerView)?.let { recyclerView ->
            (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            recyclerView.itemAnimator = null
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.updatePrimaryPosition(position)
                prefetchAround(position)
            }
        })

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
                    if (list.isNotEmpty()) {
                        val currentIndex = binding.viewPager.currentItem.coerceIn(0, list.size - 1)
                        adapter.updatePrimaryPosition(currentIndex)
                        prefetchAround(currentIndex)
                    }
                }
            }
        }

        // Setup export button click listener
        binding.exportButton.setOnClickListener { exportCurrent() }

        // Setup delete button click listener
        binding.deleteButton.setOnClickListener { showDeleteConfirmDialog() }

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

    private fun createBitmapCache(divisor: Int): LruCache<String, Bitmap> {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemKb / divisor).coerceAtLeast(1024)
        return object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private fun ensurePhotoForPosition(path: String, position: Int) {
        ensureThumbnail(path)
        if (shouldLoadPreview(isCurrentPage = position == binding.viewPager.currentItem)) {
            ensureFullPreview(path)
        }
    }

    private fun prefetchAround(position: Int) {
        val current = adapter.getItem(position) ?: return
        ensureThumbnail(current.path)

        val previous = adapter.getItem(position - 1)
        val next = adapter.getItem(position + 1)
        previous?.let { ensureThumbnail(it.path) }
        next?.let { ensureThumbnail(it.path) }
    }

    private fun getCurrentEntity(): PhotoEntity? {
        if (adapter.itemCount == 0) return null
        val currentIndex = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
        return adapter.getItem(currentIndex)
    }

    private fun getJpegSourceInfo(path: String): JpegSourceInfo? {
        if (!isJpegPath(path)) return null
        jpegSourceInfoCache[path]?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return JpegSourceInfo(options.outWidth, options.outHeight).also {
            jpegSourceInfoCache[path] = it
        }
    }

    private fun isJpegPath(path: String): Boolean {
        return ExtensionUtils.isJpegExtension(path.substringAfterLast('.', "").lowercase(Locale.ROOT))
    }

    private fun ensureThumbnail(path: String) {
        if (thumbnailCache.get(path) != null || fullBitmapCache.get(path) != null) return
        if (!inFlightThumbnailPaths.add(path)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeThumbnailBitmap(path) ?: return@launch
                withContext(Dispatchers.Main) {
                    thumbnailCache.put(path, bitmap)
                    adapter.notifyPathChanged(path)
                }
            } finally {
                inFlightThumbnailPaths.remove(path)
            }
        }
    }

    private fun ensureFullPreview(path: String) {
        if (fullBitmapCache.get(path) != null) return
        if (!inFlightFullPaths.add(path)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeFullPreviewBitmap(path) ?: return@launch
                withContext(Dispatchers.Main) {
                    fullBitmapCache.put(path, bitmap)
                    adapter.notifyPathChanged(path)
                }
            } finally {
                inFlightFullPaths.remove(path)
            }
        }
    }

    private fun decodeThumbnailBitmap(path: String): Bitmap? {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return try {
            when {
                ExtensionUtils.isRawExtension(ext) -> RawWrapper.decodeThumbnailBitmap(path)
                ExtensionUtils.isJpegExtension(ext) -> decodeSampledBitmap(path, THUMBNAIL_MAX_EDGE_PX)
                else -> null
            }
        } catch (t: Throwable) {
            Timber.w(t, "decodeThumbnailBitmap failed for path=%s", path)
            null
        }
    }

    private fun decodeFullPreviewBitmap(path: String): Bitmap? {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return try {
            when {
                ExtensionUtils.isRawExtension(ext) -> RawWrapper.decodeToBitmap(path)
                ExtensionUtils.isJpegExtension(ext) -> {
                    val targetEdge = (previewLongEdgePx * FULL_PREVIEW_SCALE_FACTOR)
                        .coerceAtMost(FULL_PREVIEW_MAX_EDGE_PX)
                    decodeSampledBitmap(path, targetEdge)
                }
                else -> null
            }
        } catch (t: Throwable) {
            Timber.w(t, "decodeFullPreviewBitmap failed for path=%s", path)
            null
        }
    }

    private fun decodeSampledBitmap(path: String, targetLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val srcLongEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val scale = targetLongEdge.coerceAtLeast(1).toFloat() / srcLongEdge.toFloat()
        val reqWidth = (bounds.outWidth * scale).roundToInt().coerceAtLeast(1)
        val reqHeight = (bounds.outHeight * scale).roundToInt().coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (oom: OutOfMemoryError) {
            Timber.w(oom, "decodeSampledBitmap OOM for path=%s", path)
            null
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
                    val bmp = fullBitmapCache.get(path) ?: try {
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

    /**
     * Shows confirmation dialog before deleting current photo.
     */
    private fun showDeleteConfirmDialog() {
        if (adapter.itemCount == 0) {
            Toast.makeText(this, getString(R.string.toast_no_deletable_photos), Toast.LENGTH_SHORT).show()
            return
        }

        val index = binding.viewPager.currentItem.coerceIn(0, adapter.itemCount - 1)
        val entity = adapter.getItem(index) ?: return
        val displayName = entity.name ?: getString(R.string.photo_default_name)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title))
            .setMessage(getString(R.string.dialog_delete_message))
            .setPositiveButton(getString(R.string.dialog_delete_confirm)) { _, _ ->
                deleteCurrent(index)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    /**
     * Deletes photo at given index from database, cache, and filesystem.
     *
     * @param index The index of photo to delete.
     */
    private fun deleteCurrent(index: Int) {
        val entity = adapter.getItem(index) ?: return
        val displayName = entity.name ?: getString(R.string.photo_default_name)

        Toast.makeText(this, getString(R.string.toast_deleting, displayName), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Remove from cache
                thumbnailCache.remove(entity.path)
                fullBitmapCache.remove(entity.path)
                inFlightThumbnailPaths.remove(entity.path)
                inFlightFullPaths.remove(entity.path)

                // Delete physical file
                val file = File(entity.path)
                val fileDeleted = if (file.exists()) file.delete() else true

                // Remove from database
                dao.delete(entity)

                withContext(Dispatchers.Main) {
                    if (fileDeleted) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_deleted, displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_delete_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "deleteCurrent failed")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_delete_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

}

@VisibleForTesting
internal fun shouldLoadPreview(isCurrentPage: Boolean): Boolean {
    return isCurrentPage
}

@VisibleForTesting
internal fun calculateInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int
): Int {
    if (srcWidth <= 0 || srcHeight <= 0) return 1
    if (reqWidth <= 0 || reqHeight <= 0) return 1

    var inSampleSize = 1
    if (srcHeight > reqHeight || srcWidth > reqWidth) {
        val halfHeight = srcHeight / 2
        val halfWidth = srcWidth / 2

        while (halfHeight / inSampleSize >= reqHeight &&
            halfWidth / inSampleSize >= reqWidth
        ) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}
