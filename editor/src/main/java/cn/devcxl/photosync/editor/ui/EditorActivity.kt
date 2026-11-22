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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorLaunchArgs
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyBrushSettings
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.render.CanvasViewportTransform
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.panels.EditorPreviewGeometry

/**
 * Full-screen non-destructive photo editor.
 *
 * Combines 16-bit linear RAW decoding + `BaseLook` baseline switching with Pass A geometry /
 * liquify / lateral chromatic-aberration resampling, Pass B D65 Bradford WB + linear/display tone
 * grading and Pass D 3D LUT + film grain + post-crop vignette rendering.
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
    private val renderer = EditorGlRenderer()
    private val undoController = EditorUndoController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lutStore by lazy { CubeLutStore(filesDir) }

    private var photoId: Long = 0L
    private var sourcePath: String = ""
    private var isRaw: Boolean = false
    private var preferLinearSource: Boolean = false

    private val settleFullQualityRunnable = Runnable { renderCurrentGraph(useProxy = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoId = intent.getLongExtra(EXTRA_PHOTO_ID, 0L)
        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        isRaw = intent.getBooleanExtra(EXTRA_IS_RAW, false)
        initCoordinators()
        canvas = EditorCanvasBinder(binding, renderer, undoController, liquifySession, this)
        preferLinearSource = canvas.configureSurfaceView()
        canvas.attachOverlays()
        topBar = EditorTopBarBinder(binding, historyCoordinator, this).also { it.bind() }
        panel = EditorPanelController(this, binding, undoController, this, lutStore)
        overlaySync = EditorOverlaySync(binding, panel, liquifySession, ::previewAspect)
        overlaySync.syncBrush()
        lutImporter = EditorLutImportCoordinator(this, lutStore, undoController, previewSession) {
            renderCurrentGraph(useProxy = false)
            updateHistoryButtons()
        }
        panel.selectGroup(EditorGroup.TONE)
        loadInitialPhotoAndEditState()
    }

    private fun initCoordinators() {
        liquifySession = LiquifySession(photoId, LiquifyMeshStore(filesDir))
        previewSession = EditorPreviewSession(renderer, cacheDir, photoId, lutStore)
        saveCoordinator = EditorSaveCoordinator(photoId, previewSession, liquifySession)
        sessionLoader = EditorSessionLoader(photoId, previewSession, liquifySession)
        historyCoordinator = EditorHistoryCoordinator(
            photoId = photoId,
            undoController = undoController,
            liquifySession = liquifySession,
            onMeshPublished = ::publishLiquifyMesh,
            onStateChanged = ::applyHistoryGraph
        )
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        binding.glSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        canvas.detachOverlays()
        previewSession.release()
        super.onDestroy()
    }

    override fun onEditorToolChanged(tool: EditorTool) {
        val liquifyActive = panel.currentGroup == EditorGroup.GEOMETRY && tool == EditorTool.LIQUIFY
        binding.cropOverlayView.visibility = if (liquifyActive) View.GONE else View.VISIBLE
        binding.liquifyBrushOverlayView.visibility = if (liquifyActive) View.VISIBLE else View.GONE
        overlaySync.sync(undoController.currentGraph)
        renderCurrentGraph(useProxy = false)
        updateHistoryButtons()
    }

    override fun onGraphEdited(graph: EditGraph, parameterKey: EditParameterKey) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCurrentGraph(useProxy = true)
        mainHandler.postDelayed(settleFullQualityRunnable, SETTLE_DELAY_MS)
    }

    override fun onGraphCommitted(graph: EditGraph, parameterKey: EditParameterKey) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCurrentGraph(useProxy = false)
        updateHistoryButtons()
    }

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
        updateHistoryButtons()
    }

    override fun onLutImportRequested() {
        lutImporter.launch()
    }

    override fun onPreviewDragStarted() {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCurrentGraph(useProxy = true)
    }

    override fun onPreviewDragFinished() {
        renderCurrentGraph(useProxy = false)
        updateHistoryButtons()
    }

    override fun onLiquifyMeshChanged() {
        publishLiquifyMesh()
    }

    override fun onLiquifyStrokeFinished() {
        historyCoordinator.onLiquifyStrokeCommitted()
        renderCurrentGraph(useProxy = false)
        updateHistoryButtons()
    }

    override fun onCompareChanged(comparing: Boolean) {
        if (renderer.compareOriginal == comparing) return
        renderer.compareOriginal = comparing
        topBar.setComparePillVisible(comparing)
        binding.glSurfaceView.requestRender()
    }

    override fun onBaseLookSelected(baseLook: BaseLook) {
        val current = undoController.currentGraph
        if (current.baseLook == baseLook) return
        undoController.onParameterDrag(current.copy(baseLook = baseLook), EditParameterKey.BASE_LOOK)
        undoController.onParameterRelease()
        applyHistoryGraph(undoController.currentGraph)
    }

    override fun onDoneRequested() {
        saveEditAndFinish()
    }

    private fun applyHistoryGraph(graph: EditGraph) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCurrentGraph(useProxy = false)
        updateBaseLookPill(graph.baseLook)
        updateHistoryButtons()
    }

    private fun renderCurrentGraph(useProxy: Boolean) {
        val graph = undoController.currentGraph
        val preview = EditorPreviewGeometry.previewGraph(graph, panel.currentTool)
        val lutWarm = previewSession.applyGraph(preview, useProxy = useProxy)
        overlaySync.sync(graph)
        panel.refresh()
        updateBaseLookPill(graph.baseLook)
        binding.glSurfaceView.requestRender()
        if (!lutWarm) preloadLutAsync(graph)
    }

    private fun preloadLutAsync(graph: EditGraph) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { previewSession.preloadLut(graph) }
            if (loaded && undoController.currentGraph.color.lut?.id == graph.color.lut?.id) {
                binding.glSurfaceView.requestRender()
            }
        }
    }

    private fun publishLiquifyMesh() {
        previewSession.applyLiquifyMesh(liquifySession.mesh)
        binding.glSurfaceView.requestRender()
    }

    override fun onCanvasTransformChanged(transform: CanvasTransform) {
        renderer.canvasTransform = CanvasViewportTransform(transform.scale, transform.offsetX, transform.offsetY)
        overlaySync.syncCanvasTransform(transform)
        binding.glSurfaceView.requestRender()
    }

    private fun updateBaseLookPill(baseLook: BaseLook) {
        topBar.updateBaseLookPill(baseLook, isRaw && previewSession.supportsBaseLook)
    }

    private fun updateHistoryButtons() {
        topBar.updateHistoryButtons()
    }

    private fun loadInitialPhotoAndEditState() {
        lifecycleScope.launch {
            val loaded = sessionLoader.load(sourcePath, isRaw, preferLinearSource)
            if (!loaded.hasSource) {
                Toast.makeText(this@EditorActivity, R.string.editor_source_missing, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            historyCoordinator.resetSession(loaded.graph)
            publishLiquifyMesh()
            renderCurrentGraph(useProxy = false)
            updateHistoryButtons()
        }
    }

    private fun saveEditAndFinish() {
        binding.btnDone.isEnabled = false
        val finalGraph = undoController.currentGraph
        lifecycleScope.launch {
            val saved = saveCoordinator.persist(finalGraph)
            if (!saved) {
                binding.btnDone.isEnabled = true
                Toast.makeText(this@EditorActivity, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val resultIntent = Intent().apply {
                putExtra(EXTRA_PHOTO_ID, photoId)
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
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
