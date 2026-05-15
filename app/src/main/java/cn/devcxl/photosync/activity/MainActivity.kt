package cn.devcxl.photosync.activity

import kotlin.math.roundToInt

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.App
import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.JpegSourceInfo
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.adapter.PhotoRenderState
import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.databinding.ActivityMainBinding
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionState
import cn.devcxl.photosync.utils.ExtensionUtils
import cn.devcxl.photosync.wrapper.RawWrapper

class MainActivity : ComponentActivity() {

    private val connectionController by lazy {
        (application as App).usbPtpConnectionController
    }

    companion object {
        private const val REQ_WRITE_STORAGE = 2001
        private const val THUMBNAIL_MAX_EDGE_PX = 512
        private const val RAW_PREVIEW_MAX_EDGE_PX = 1200
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
    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawThumbnailDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val previewLongEdgePx: Int by lazy {
        maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .coerceAtLeast(1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDao()
        setupAdapter()
        setupViewPager()
        observeDatabase()
        observeConnectionState()
        setupButtons()
        enableEdgeToEdge()
        configureConnectionController()
        connectMTPDevice()
    }

    private fun setupDao() {
        dao = AppDatabase.getInstance(this).photoDao()
    }

    private fun setupAdapter() {
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
    }

    private fun setupViewPager() {
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
    }

    private fun observeDatabase() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getAllFlow().collect { list ->
                    adapter.updateItems(list.toList())
                    pendingRevealPath?.let { target ->
                        val idx = list.indexOfFirst { it.path == target }
                        if (idx >= 0) {
                            binding.viewPager.setCurrentItem(idx, false)
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
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastState: UsbPtpConnectionState? = null
                connectionController.connectionState.collect { state ->
                    renderUsbConnectionState(state, lastState)
                    lastState = state
                }
            }
        }
    }

    private fun setupButtons() {
        binding.exportButton.setOnClickListener { exportCurrent() }
        binding.deleteButton.setOnClickListener { showDeleteConfirmDialog() }
    }

    override fun onDestroy() {
        connectionController.setFileTransferListener(null)
        connectionController.setFileDownloadedListener(null)
        connectionController.disconnect("activity_destroyed")
        super.onDestroy()
    }

    fun connectMTPDevice() {
        connectionController.scanAndConnectIfPossible()
    }

    private fun configureConnectionController() {
        connectionController.setFileDownloadPath(externalCacheDir?.absolutePath)
        connectionController.setFileTransferListener { _, _, totalByteLength, transterByteLength ->
            val progressPercent = if (totalByteLength > 0) {
                ((transterByteLength * 100L) / totalByteLength).toInt().coerceIn(0, 100)
            } else 0
            lifecycleScope.launch(Dispatchers.Main) {
                if (binding.progressBar.visibility != View.VISIBLE) {
                    binding.progressBar.visibility = View.VISIBLE
                }
                binding.progressBar.progress = progressPercent
            }
        }
        connectionController.setFileDownloadedListener { _, _, localFile, timeduring ->
            Timber.v("file downloaded at %s, time: %sms", localFile.absolutePath, timeduring)
            lifecycleScope.launch(Dispatchers.Main) { binding.progressBar.visibility = View.GONE }

            val ext = localFile.extension.lowercase(Locale.ROOT)
            val isRaw = ExtensionUtils.isRawExtension(ext)
            val isJpeg = ExtensionUtils.isJpegExtension(ext)
            Timber.d("file downloaded: %s ext=%s raw=%b jpeg=%b",
                localFile.name, ext, isRaw, isJpeg)
            if (isRaw || isJpeg) {
                insertItemAndReveal(localFile)
            }
        }
    }

    private fun renderUsbConnectionState(
        state: UsbPtpConnectionState,
        previousState: UsbPtpConnectionState?
    ) {
        when (state) {
            is UsbPtpConnectionState.Connected -> {
                if (previousState !is UsbPtpConnectionState.Connected) {
                    Toast.makeText(this, getString(R.string.toast_device_connected), Toast.LENGTH_LONG).show()
                }
            }
            is UsbPtpConnectionState.Disconnected -> {
                binding.progressBar.visibility = View.GONE
                if (state.reason == "no_device" && previousState !is UsbPtpConnectionState.Disconnected) {
                    Toast.makeText(this, getString(R.string.toast_no_usb_device), Toast.LENGTH_LONG).show()
                }
            }
            is UsbPtpConnectionState.Error -> {
                binding.progressBar.visibility = View.GONE
                if (state.reason == "permission_denied") {
                    return
                }
                Timber.w("USB connection error: reason=%s detail=%s", state.reason, state.detail)
            }
            is UsbPtpConnectionState.PermissionRequested,
            is UsbPtpConnectionState.Connecting,
            is UsbPtpConnectionState.Disconnecting,
            UsbPtpConnectionState.Idle -> Unit
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
            } catch (e: Exception) {
                Timber.e(e, "DB insert failed")
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
        if (shouldLoadThumbnail(position == binding.viewPager.currentItem, isRawPath(path))) {
            ensureThumbnail(path)
        }
        if (shouldLoadPreview(isCurrentPage = position == binding.viewPager.currentItem)) {
            ensureFullPreview(path)
        }
    }

    private fun prefetchAround(position: Int) {
        val current = adapter.getItem(position) ?: return
        ensureThumbnail(current.path)

        val previous = adapter.getItem(position - 1)
        val next = adapter.getItem(position + 1)
        previous?.takeUnless { isRawPath(it.path) }?.let { ensureThumbnail(it.path) }
        next?.takeUnless { isRawPath(it.path) }?.let { ensureThumbnail(it.path) }
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

    private fun isRawPath(path: String): Boolean {
        return ExtensionUtils.isRawExtension(path.substringAfterLast('.', "").lowercase(Locale.ROOT))
    }

    private fun ensureThumbnail(path: String) {
        if (thumbnailCache.get(path) != null || fullBitmapCache.get(path) != null) return
        if (!inFlightThumbnailPaths.add(path)) return
        val isRaw = isRawPath(path)
        val dispatcher = if (isRaw) rawThumbnailDispatcher else Dispatchers.IO
        lifecycleScope.launch(dispatcher) {
            try {
                if (isRaw) {
                    Timber.d("start RAW thumbnail decode: %s", path)
                }
                val bitmap = decodeThumbnailBitmap(path) ?: return@launch
                if (isRaw) {
                    Timber.d("decoded RAW thumbnail bitmap %dx%d: %s", bitmap.width, bitmap.height, path)
                }
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
        val isRaw = ExtensionUtils.isRawExtension(
            path.substringAfterLast('.', "").lowercase(Locale.ROOT))
        if (isRaw) {
            inFlightFullPaths.remove(path)
            return
        }
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
                ExtensionUtils.isRawExtension(ext) -> {
                    val bmp = RawWrapper.decodeThumbnailBitmap(path, RAW_PREVIEW_MAX_EDGE_PX)
                    if (bmp == null) Timber.w("RAW thumbnail decode returned null: %s", path)
                    bmp
                }
                ExtensionUtils.isJpegExtension(ext) -> decodeSampledBitmap(path, THUMBNAIL_MAX_EDGE_PX)
                else -> {
                    Timber.w("unknown extension for thumbnail: ext=%s path=%s", ext, path)
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "decodeThumbnailBitmap failed for path=%s", path)
            null
        }
    }

    private fun decodeFullPreviewBitmap(path: String): Bitmap? {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return try {
            when {
                ExtensionUtils.isRawExtension(ext) -> RawWrapper.decodeThumbnailBitmap(path)
                ExtensionUtils.isJpegExtension(ext) -> {
                    val targetEdge = (previewLongEdgePx * FULL_PREVIEW_SCALE_FACTOR)
                        .coerceAtMost(FULL_PREVIEW_MAX_EDGE_PX)
                    decodeSampledBitmap(path, targetEdge)
                }
                else -> null
            }
        } catch (e: Exception) {
            Timber.w(e, "decodeFullPreviewBitmap failed for path=%s", path)
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
                    } catch (e: Exception) {
                        Timber.w(e, "RAW decode failed for path=%s, falling back to null", path)
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
internal fun shouldLoadThumbnail(isCurrentPage: Boolean, isRaw: Boolean): Boolean {
    return isCurrentPage || !isRaw
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
