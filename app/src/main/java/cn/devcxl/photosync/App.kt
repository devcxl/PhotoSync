package cn.devcxl.photosync

import java.io.File

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbManager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.activity.clearPhotoEditCacheDir
import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.RoomEditStore
import cn.devcxl.photosync.data.RoomTetheredPhotoCommitter
import cn.devcxl.photosync.data.TetheredIngestResult
import cn.devcxl.photosync.data.TetheredPhotoIngestor
import cn.devcxl.photosync.editor.R as EditorR
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.ActivePhotoEditRegistry
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.BuiltinPresets
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.render.BackgroundPreviewRenderer
import cn.devcxl.photosync.editor.render.RecentEditorSourceCache
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.OrtSessionManager
import cn.devcxl.photosync.export.MediaStoreGallerySink
import cn.devcxl.photosync.ptp.manager.UsbPtpConnectionController
import cn.devcxl.photosync.receiver.UsbPermissionReceiver
import cn.devcxl.photosync.receiver.UsbReceiver
import cn.devcxl.photosync.utils.ExtensionUtils
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Application entry point for the PhotoSync app.
 *
 * Responsibilities:
 * - Initialize global SDKs (native Raw wrapper) and logging.
 * - Register USB broadcast receivers for device attach/detach and permission results.
 * - Wire the `:editor` host contracts and the tethered darkroom ingest pipeline.
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

    /** Long-lived scope for work that must survive an activity recreate. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Photo ids committed by the tethered ingestor, replayed to `:editor` and `MainActivity`.
     *
     * `DROP_OLDEST` with a small buffer keeps a burst of syncs from suspending the USB reader when
     * nobody is collecting (app in background).
     */
    private val tetheredPhotoEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = TETHERED_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** The `LiveTetherPreset` ingest path, or `null` when the database is unavailable. */
    @Volatile
    private var tetheredIngestor: TetheredPhotoIngestor? = null

    private val _transferActive = MutableStateFlow(false)

    /** True while the USB reader is between file-transfer progress ticks and completion. */
    val transferActive: StateFlow<Boolean> = _transferActive.asStateFlow()

    val usbPtpConnectionController: UsbPtpConnectionController by lazy {
        UsbPtpConnectionController(this)
    }

    /**
     * Process-wide ORT session owner.
     *
     * Lazy on purpose: nothing on the cold-start path, the gallery or the preset strip may load
     * ONNX Runtime or a model. The first AI tool that needs inference touches this property; the
     * manager itself only creates a session when `withSession` is called.
     */
    val ortSessionManager: OrtSessionManager by lazy {
        OrtSessionManager(tier = DeviceMemoryTier.detect(this))
    }

    /** Photo ids that arrived from the camera while the app was already running. */
    val syncedPhotoEvents: SharedFlow<Long> = tetheredPhotoEvents.asSharedFlow()

    companion object {
        private var instance: App? = null
        const val ACTION_USB_PERMISSION = "cn.devcxl.photosync.USB_PERMISSION"
        private const val TETHERED_EVENT_BUFFER = 16

        /** Preferences file the editor's `LiveTetherPreset` lives in. */
        const val EDITOR_PREFS_NAME = "photosync_editor_prefs"

        fun get(): App {
            return instance!!
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.plant(Timber.DebugTree())

        val db = AppDatabase.getInstance(this)
        val editStore = RoomEditStore(
            photoEditDao = db.photoEditDao(),
            userPresetDao = db.userPresetDao(),
            liveTetherPrefs = getSharedPreferences(EDITOR_PREFS_NAME, MODE_PRIVATE),
            onClearPhotoEditCache = { photoId -> clearPhotoEditCacheDir(cacheDir, photoId) },
            builtinNameResolver = ::resolveBuiltinPresetName,
            onClearPhotoExternalAssets = { photoId ->
                AiAssetStore(filesDir).deletePhotoAssets(photoId)
            }
        )
        EditorHostRegistry.editStore = editStore
        EditorHostRegistry.gallerySink = MediaStoreGallerySink(contentResolver)
        EditorHostRegistry.tetheredPhotoEvents = syncedPhotoEvents
        applicationScope.launch {
            File(cacheDir, TetheredPhotoIngestor.STAGING_DIR_NAME).deleteRecursively()
            seedPresets(editStore)
            collectOrphanAiAssets(db)
        }
        tetheredIngestor = createIngestor(editStore)

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
        configureTetheredSync()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            RecentEditorSourceCache.shared.onMemoryPressure()
            BackgroundPreviewRenderer.onMemoryPressure()
            ortSessionManager.onMemoryPressure()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        RecentEditorSourceCache.shared.onMemoryPressure()
        BackgroundPreviewRenderer.onMemoryPressure()
        ortSessionManager.onMemoryPressure()
    }

    /**
     * Wires the process-wide download sink.
     *
     * The path and the listener live here, not in `MainActivity`: a sync that finishes while the
     * editor is in the foreground must still be committed, so the pipeline cannot depend on the
     * gallery activity being alive.
     */
    private fun configureTetheredSync() {
        val downloadDir = externalCacheDir ?: cacheDir.also {
            Timber.w("externalCacheDir unavailable; falling back to internal cacheDir for sync")
        }
        usbPtpConnectionController.setFileDownloadPath(downloadDir.absolutePath)
        usbPtpConnectionController.setFileDownloadedListener { fileHandle, file, _ ->
            _transferActive.value = false
            val ingestor = tetheredIngestor
            if (ingestor == null) {
                Timber.w("Tethered ingest pipeline is unavailable; the photo was not imported")
            } else {
                ingestInBackground(ingestor, file, fileHandle)
            }
        }
    }

    /** Marks that a USB file transfer is currently active so UI cards can reflect live state. */
    fun notifyTransferStarted() {
        _transferActive.value = true
    }

    /** Clears the active USB transfer flag when a session disconnects or errors out. */
    fun clearTransferActive() {
        _transferActive.value = false
    }

    private fun resolveBuiltinPresetName(id: String): String {
        val resId = when (id) {
            BuiltinPresets.CLEAN_PORTRAIT_ID -> EditorR.string.editor_preset_builtin_clean_portrait
            BuiltinPresets.FILM_CHROME_ID -> EditorR.string.editor_preset_builtin_film_chrome
            BuiltinPresets.WARM_SUNSET_ID -> EditorR.string.editor_preset_builtin_warm_sunset
            BuiltinPresets.COOL_NORDIC_ID -> EditorR.string.editor_preset_builtin_cool_nordic
            BuiltinPresets.MONO_SILVER_ID -> EditorR.string.editor_preset_builtin_mono_silver
            else -> return id
        }
        return getString(resId)
    }

    private fun ingestInBackground(ingestor: TetheredPhotoIngestor, file: File, objectHandle: Int) {
        val ext = ExtensionUtils.fileExtension(file.name)
        val isRaw = ExtensionUtils.isRawExtension(ext)
        val isJpeg = ExtensionUtils.isJpegExtension(ext)
        applicationScope.launch {
            val result = ingestor.onFileDownloaded(
                file = file,
                objectHandle = objectHandle,
                isRaw = isRaw,
                isJpeg = isJpeg
            )
            if (result is TetheredIngestResult.Ingested) {
                tetheredPhotoEvents.tryEmit(result.photoId)
            }
        }
    }

    private fun createIngestor(editStore: RoomEditStore): TetheredPhotoIngestor =
        TetheredPhotoIngestor(
            editStore = editStore,
            committer = RoomTetheredPhotoCommitter(AppDatabase.getInstance(this), cacheDir),
            stagingDir = cacheDir,
            renderPreview = { sourcePath, isRaw, graph, outDir ->
                BackgroundPreviewRenderer.renderToDir(
                    context = this,
                    filesDir = filesDir,
                    outDir = outDir,
                    sourcePath = sourcePath,
                    isRaw = isRaw,
                    graph = graph
                )
            }
        )

    private suspend fun seedPresets(editStore: RoomEditStore) {
        try {
            editStore.seedBuiltinPresets()
        } catch (e: RuntimeException) {
            Timber.e(e, "Failed to seed the built-in preset library")
        }
    }

    /**
     * Sweeps AI payloads that no persisted edit row references any more.
     *
     * Cold start is the only moment when no editor session is expected to exist yet, but the sweep
     * still runs asynchronously and an editor or bake can start while it is in flight. The
     * [ActivePhotoEditRegistry] snapshot therefore exempts every photo that is actively held at the
     * moment the sweep starts. A row that fails to decode aborts the sweep: an unreadable graph may
     * still own its bytes, and a failed read must never be mistaken for "no references".
     */
    private suspend fun collectOrphanAiAssets(db: AppDatabase) {
        try {
            val rows = db.photoEditDao().getAllEdits()
            val graphs = rows.mapNotNull { row ->
                EditGraphJsonCodec.decode(row.graphJson).also { graph ->
                    if (graph == null) {
                        Timber.w("AI asset GC skipped: a persisted edit graph could not be decoded")
                    }
                }
            }
            if (graphs.size != rows.size) return
            val store = AiAssetStore(filesDir)
            val referenced = graphs.flatMapTo(HashSet()) { graph ->
                store.referencedRelativePaths(graph)
            }
            val report = store.collectOrphanAssets(
                referencedRelativePathsProvider = { referenced },
                activePhotoIds = ActivePhotoEditRegistry.snapshot()
            )
            if (report.deletedFiles > 0) {
                Timber.i(
                    "AI asset GC removed %d orphan files (%d bytes) from %d photo directories",
                    report.deletedFiles,
                    report.reclaimedBytes,
                    report.scannedPhotoDirectories
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Timber.e(e, "AI asset GC failed")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        BackgroundPreviewRenderer.shutdown()
        unregisterReceiver(usbReceiver)
        unregisterReceiver(usbPermissionReceiver)
    }
}
