package cn.devcxl.photosync.editor.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.EditorLaunchArgs
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.ActivePhotoEditRegistry
import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyBrushSettings
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.SkyCustomAsset
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.settings.DisplaySettings
import cn.devcxl.photosync.editor.settings.EditorRenderSettings
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.panels.EditorPreviewGeometry

/**
 * Full-screen non-destructive photo editor: 16-bit linear RAW + `BaseLook`, Pass A geometry /
 * liquify / portrait warps, Pass B tone and colour, Pass C spatial detail + portrait skin,
 * Pass D LUT, grain and vignette, and full-resolution streaming Bake export.
 */
class EditorActivity : AppCompatActivity(),
    EditorPanelController.Host,
    EditorTopBarBinder.Host,
    EditorCanvasBinder.Host {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var previewSession: EditorPreviewSession
    private lateinit var saveCoordinator: EditorSaveCoordinator
    private lateinit var sessionLoader: EditorSessionLoader
    private lateinit var historyCoordinator: EditorHistoryCoordinator
    private lateinit var panel: EditorPanelController
    private lateinit var liquifySession: LiquifySession
    private lateinit var overlaySync: EditorOverlaySync
    private lateinit var topBar: EditorTopBarBinder
    private lateinit var canvas: EditorCanvasBinder
    private lateinit var lutImporter: EditorLutImportCoordinator
    private lateinit var exportCoordinator: EditorExportCoordinator
    private lateinit var portraitHost: EditorPortraitHost
    private lateinit var renderCoordinator: EditorRenderCoordinator
    private lateinit var gestureHost: EditorGestureHost
    private lateinit var aiTools: EditorAiToolHost
    private val modelImport = EditorModelImportLauncher(this)
    private val renderer = EditorGlRenderer()
    private lateinit var renderSettings: EditorRenderSettings
    private lateinit var undoController: EditorUndoController
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lutStore by lazy { CubeLutStore(filesDir) }

    private var photoId: Long = 0L
    private var sourcePath: String = ""
    private var isRaw: Boolean = false
    private var preferLinearSource: Boolean = false

    private val settleFullQualityRunnable = Runnable { renderCoordinator.renderCurrentGraph(useProxy = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenOnPolicy()
        // One coherent settings snapshot per editor session: preview resolution, quality and undo
        // depth all take effect now and stay stable for the lifetime of this activity.
        renderSettings = EditorRenderSettings.from(EditorSettings(this))
        undoController = EditorUndoController(maxDepth = renderSettings.undoDepth)

        photoId = intent.getLongExtra(EXTRA_PHOTO_ID, 0L)
        // Keep the photo's AI assets out of the offline GC sweep for the whole editor session.
        ActivePhotoEditRegistry.acquire(photoId)
        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        isRaw = intent.getBooleanExtra(EXTRA_IS_RAW, false)
        initCoordinators()
        canvas = EditorCanvasBinder(binding, renderer, undoController, liquifySession, this)
        preferLinearSource = canvas.configureSurfaceView()
        canvas.attachOverlays()
        topBar = EditorTopBarBinder(binding, historyCoordinator, this).also { it.bind() }
        panel = EditorPanelController(this, binding, undoController, this, lutStore, this)
        overlaySync = EditorOverlaySync(
            binding = binding,
            panel = panel,
            liquifySession = liquifySession,
            previewAspect = ::previewAspect,
            guides = { portraitHost.guides() },
            sourceSize = { previewSession.source?.let { source -> source.width to source.height } }
        )
        overlaySync.syncBrush()
        initActionHosts()
        panel.selectGroup(EditorGroup.TONE)
        loadInitialPhotoAndEditState()
    }

    private fun initCoordinators() {
        val meshStore = LiquifyMeshStore(filesDir)
        liquifySession = LiquifySession(meshStore)
        previewSession = EditorPreviewSession(
            renderer = renderer,
            cacheDir = cacheDir,
            photoId = photoId,
            lutStore = lutStore,
            filesDir = filesDir,
            previewMaxEdgePx = renderSettings.previewMaxEdgePx,
            skyPreviewMaxEdgePx = renderSettings.skyPreviewMaxEdgePx,
            previewCacheJpegQuality = renderSettings.previewCacheJpegQuality
        )
        sessionLoader = EditorSessionLoader(photoId, previewSession, liquifySession)
        historyCoordinator = EditorHistoryCoordinator(
            photoId = photoId,
            undoController = undoController,
            liquifySession = liquifySession,
            onMeshPublished = { renderCoordinator.publishLiquifyMesh() },
            onStateChanged = { graph ->
                mainHandler.removeCallbacks(settleFullQualityRunnable)
                renderCoordinator.applyHistoryGraph(graph)
            }
        )
        saveCoordinator = EditorSaveCoordinator(
            photoId = photoId,
            meshStore = meshStore,
            previewRenderer = EditorPreviewCacheRenderer { graph, warpField, skinMask ->
                previewSession.writePreviewCache(graph, warpField, skinMask)
            }
        )
    }

    private fun initActionHosts() {
        initPortraitAndRenderHosts()
        initGestureHost()
        initAiHost()
        initMediaHosts()
    }

    private fun initPortraitAndRenderHosts() {
        portraitHost = EditorPortraitHost(
            context = this,
            filesDir = filesDir,
            undoController = undoController,
            renderer = renderer,
            scope = lifecycleScope,
            panelProvider = { panel },
            sourceProvider = { previewSession.source },
            onWarpsPublished = { binding.glSurfaceView.requestRender() },
            onPreviewStatus = { EditorPortraitStatusBinder.render(binding, it) },
            onGraphEdited = { renderCoordinator.renderCurrentGraph(useProxy = true) },
            onGraphCommitted = {
                renderCoordinator.renderCurrentGraph(useProxy = false)
                renderCoordinator.updateHistoryButtons()
            }
        )
        renderCoordinator = EditorRenderCoordinator(
            binding = binding,
            renderer = renderer,
            previewSession = previewSession,
            liquifySession = liquifySession,
            undoController = undoController,
            topBar = topBar,
            portraitHost = portraitHost,
            scope = lifecycleScope,
            isRaw = { isRaw },
            panelProvider = { panel },
            overlaySyncProvider = { overlaySync }
        )
    }

    private fun initGestureHost() {
        gestureHost = EditorGestureHost(
            binding = binding,
            renderer = renderer,
            topBar = topBar,
            historyCoordinator = historyCoordinator,
            portraitHost = portraitHost,
            renderCoordinatorProvider = { renderCoordinator },
            cancelSettle = { mainHandler.removeCallbacks(settleFullQualityRunnable) }
        )
    }

    private fun initAiHost() {
        aiTools = EditorAiToolHost(
            context = this,
            binding = binding,
            filesDir = filesDir,
            photoId = photoId,
            sourcePath = sourcePath,
            isRaw = isRaw,
            undoController = undoController,
            previewSession = previewSession,
            scope = lifecycleScope,
            liquifySession = liquifySession,
            analysisProvider = { portraitHost.analysis() },
            onCanvasTransform = ::onCanvasTransformChanged,
            onGraphChanged = {
                renderCoordinator.renderCurrentGraph(useProxy = false)
                renderCoordinator.updateHistoryButtons()
            },
            onRepaintRequested = { binding.glSurfaceView.requestRender() },
            launchImport = modelImport::launch,
            panelProvider = { panel },
            onPreviewGraph = renderCoordinator::renderTransientGraph
        )
        EditorSyncPhotoPill(
            binding = binding,
            scope = lifecycleScope,
            lifecycle = lifecycle,
            events = EditorHostRegistry.tetheredPhotoEvents
        ).observe()
    }

    private fun initMediaHosts() {
        lutImporter = EditorLutImportCoordinator(this, lutStore, undoController, previewSession) {
            renderCoordinator.renderCurrentGraph(useProxy = false)
            renderCoordinator.updateHistoryButtons()
        }
        exportCoordinator = EditorExportCoordinator(
            context = this,
            scope = lifecycleScope,
            filesDir = filesDir,
            onExportingChanged = topBar::setExporting,
            onFinished = { renderCoordinator.updateHistoryButtons() }
        )
    }

    override fun onResume() {
        super.onResume()
        applyScreenOnPolicy()
        binding.glSurfaceView.onResume()
        // The P4 switch may have changed on the settings screen; re-read it so the cloud chip and
        // the cloud panel follow immediately.
        aiTools.refreshCloudAvailability()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        binding.glSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        if (::aiTools.isInitialized) aiTools.release()
        ActivePhotoEditRegistry.release(photoId)
        canvas.detachOverlays()
        portraitHost.release()
        // A save may still be resampling the staged source on a worker thread; recycling it here
        // would free the pixels under it. The coordinator recycles once the last reader is done.
        saveCoordinator.releasePreviewSource { mainHandler.post(previewSession::release) }
        super.onDestroy()
    }

    /**
     * Applies the shared "keep the screen on" preference to the whole editor window.
     *
     * The attribute is no longer hard-coded in `activity_editor.xml`; reading the `:app`-owned key
     * keeps the gallery and the editor on one switch.
     */
    private fun applyScreenOnPolicy() {
        binding.root.keepScreenOn = DisplaySettings.isKeepScreenOn(this)
    }

    override fun onEditorToolChanged(tool: EditorTool) {
        val liquifyActive = panel.currentGroup == EditorGroup.GEOMETRY && tool == EditorTool.LIQUIFY
        val inpaintActive = panel.currentGroup == EditorGroup.AI && tool == EditorTool.AI_ERASE
        val canvasOwned = liquifyActive || inpaintActive
        binding.cropOverlayView.visibility = if (canvasOwned) View.GONE else View.VISIBLE
        binding.liquifyBrushOverlayView.visibility = if (liquifyActive) View.VISIBLE else View.GONE
        binding.inpaintBrushOverlayView.visibility = if (inpaintActive) View.VISIBLE else View.GONE
        overlaySync.sync(undoController.currentGraph)
        portraitHost.onToolChanged()
        aiTools.onToolChanged(tool)
        renderCoordinator.renderCurrentGraph(useProxy = false)
        renderCoordinator.updateHistoryButtons()
    }

    override fun onGraphEdited(graph: EditGraph, parameterKey: EditParameterKey) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCoordinator.renderCurrentGraph(useProxy = true)
        mainHandler.postDelayed(settleFullQualityRunnable, SETTLE_DELAY_MS)
    }

    override fun onGraphCommitted(graph: EditGraph, parameterKey: EditParameterKey) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCoordinator.renderCurrentGraph(useProxy = false)
        renderCoordinator.updateHistoryButtons()
        if (parameterKey == EditParameterKey.PRESET) {
            aiTools.ensureSubjectMaskForPreset(graph)
            aiTools.ensureSkyMaskForPreset(graph)
        }
    }

    override fun onResetAllRequested(): Boolean = historyCoordinator.requestResetAll()

    override fun previewAspect(): Float = EditorPreviewGeometry.previewAspect(
        undoController.currentGraph,
        panel.currentTool,
        renderer.sourceAspect()
    )

    override fun onLiquifySettingsChanged(settings: LiquifyBrushSettings) {
        liquifySession.updateSettings { settings }
        overlaySync.syncBrush()
    }

    override fun onLiquifyResetRequested() {
        historyCoordinator.requestLiquifyReset()
        renderCoordinator.updateHistoryButtons()
    }

    override fun onLutImportRequested() = lutImporter.launch()

    override fun onSubjectModeRequested(mode: SubjectBackgroundMode) =
        aiTools.requestSubjectMode(mode)

    override fun onSubjectColorRequested(argb: Int) = aiTools.requestSubjectColor(argb)

    override fun onSkySelected(sky: SkyOps) = aiTools.requestSkySelected(sky)

    override fun onSkyAdjusted(sky: SkyOps) = aiTools.requestSkyAdjusted(sky)

    override fun onSkyAdjustmentStopped() = aiTools.endSkyAdjustment()

    override fun onSkyImportRequested() = modelImport.launch(aiTools::importSky)

    override fun onSkyDeleteRequested(sha256: String) = aiTools.requestSkyDelete(sha256)

    override fun skyCustomAssets(): List<SkyCustomAsset> = aiTools.skyCustomAssets()

    override fun skyHasSky(): Boolean = aiTools.skyHasSky()

    override fun isCloudToolAvailable(): Boolean = aiTools.isCloudEnabled()
    override fun onPreviewDragStarted() = gestureHost.onDragStarted()
    override fun onPreviewDragFinished() = gestureHost.onDragFinished()
    override fun onLiquifyMeshChanged() = gestureHost.onLiquifyMeshChanged()
    override fun onLiquifyStrokeFinished() = gestureHost.onLiquifyStrokeFinished()
    override fun onBodyGuidesChanged(waistY: Float, hipY: Float) =
        gestureHost.onBodyGuidesChanged(waistY, hipY)

    override fun onBodyGuidesCommitted() = gestureHost.onBodyGuidesCommitted()
    override fun onCompareChanged(comparing: Boolean) = gestureHost.onCompareChanged(comparing)

    override fun onBaseLookSelected(baseLook: BaseLook) {
        val current = undoController.currentGraph
        if (current.baseLook == baseLook) return
        undoController.onParameterDrag(current.copy(baseLook = baseLook), EditParameterKey.BASE_LOOK)
        undoController.onParameterRelease()
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCoordinator.applyHistoryGraph(undoController.currentGraph)
    }

    override fun onDoneRequested() {
        EditorFinishAction.saveAndFinish(
            scope = lifecycleScope,
            snapshot = {
                saveCoordinator.captureSnapshot(
                    graph = undoController.currentGraph,
                    liquify = liquifySession,
                    portrait = portraitHost.analysis(),
                    hasSource = previewSession.source != null
                )
            },
            save = saveCoordinator::persist,
            onSavingStarted = { topBar.setSaving(true) },
            onSaved = ::finishWithSavedEdit,
            onSaveFailed = {
                topBar.setSaving(false)
                Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun finishWithSavedEdit() {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_PHOTO_ID, photoId)
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
            }
        )
        finish()
    }

    override fun onExportRequested() {
        exportCoordinator.export(sourcePath, photoId, isRaw, undoController.currentGraph)
    }

    override fun onCanvasTransformChanged(transform: CanvasTransform) =
        gestureHost.onCanvasTransformChanged(transform)

    private fun loadInitialPhotoAndEditState() {
        lifecycleScope.launch {
            val loaded = sessionLoader.load(sourcePath, isRaw, preferLinearSource)
            if (!loaded.hasSource) {
                Toast.makeText(this@EditorActivity, R.string.editor_source_missing, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            historyCoordinator.resetSession(loaded.graph)
            portraitHost.onSourceLoaded()
            renderCoordinator.publishLiquifyMesh()
            renderCoordinator.renderCurrentGraph(useProxy = false)
            renderCoordinator.updateHistoryButtons()
            aiTools.restoreSubject(loaded.graph)
            aiTools.restoreSky(loaded.graph)
        }
    }

    companion object {
        const val EXTRA_PHOTO_ID = "extra_photo_id"
        const val EXTRA_SOURCE_PATH = "extra_source_path"
        private const val EXTRA_IS_RAW = "extra_is_raw"
        private const val SETTLE_DELAY_MS = 150L

        fun createIntent(context: Context, args: EditorLaunchArgs): Intent =
            Intent(context, EditorActivity::class.java).apply {
                putExtra(EXTRA_PHOTO_ID, args.photoId)
                putExtra(EXTRA_SOURCE_PATH, args.sourcePath)
                putExtra(EXTRA_IS_RAW, args.isRaw)
            }
    }
}
