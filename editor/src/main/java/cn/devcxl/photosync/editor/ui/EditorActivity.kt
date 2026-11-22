package cn.devcxl.photosync.editor.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
import cn.devcxl.photosync.editor.render.CanvasViewportTransform
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.panels.EditorPreviewGeometry
import cn.devcxl.photosync.editor.ui.widgets.CropOverlayView
import cn.devcxl.photosync.editor.ui.widgets.LiquifyBrushOverlayView

/**
 * Full-screen non-destructive photo editor.
 *
 * Combines 16-bit linear RAW decoding + `BaseLook` baseline switching with Pass A geometry /
 * liquify / lateral chromatic-aberration resampling and Pass B linear-light exposure grading.
 */
class EditorActivity : AppCompatActivity(), EditorPanelController.Host {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var previewSession: EditorPreviewSession
    private lateinit var saveCoordinator: EditorSaveCoordinator
    private lateinit var sessionLoader: EditorSessionLoader
    private lateinit var liquifyController: EditorLiquifyController
    private lateinit var panel: EditorPanelController
    private lateinit var liquifySession: LiquifySession
    private lateinit var overlaySync: EditorOverlaySync
    private val renderer = EditorGlRenderer()
    private val undoController = EditorUndoController()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        liquifySession = LiquifySession(photoId, LiquifyMeshStore(filesDir))
        previewSession = EditorPreviewSession(renderer, cacheDir, photoId)
        saveCoordinator = EditorSaveCoordinator(photoId, previewSession, liquifySession)
        sessionLoader = EditorSessionLoader(photoId, previewSession, liquifySession)
        liquifyController = EditorLiquifyController(
            photoId = photoId,
            undoController = undoController,
            session = liquifySession,
            onMeshPublished = ::publishLiquifyMesh,
            onGraphChanged = {
                renderCurrentGraph(useProxy = false)
                updateHistoryButtons()
            }
        )

        setupGlSurfaceView()
        setupOverlay()
        setupTopBar()
        panel = EditorPanelController(this, binding, undoController, this)
        overlaySync = EditorOverlaySync(binding, panel, liquifySession, ::previewAspect)
        panel.selectGroup(EditorGroup.TONE)
        loadInitialPhotoAndEditState()
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
        binding.cropOverlayView.listener = null
        binding.liquifyBrushOverlayView.listener = null
        previewSession.release()
        super.onDestroy()
    }

    override fun onEditorToolChanged(tool: EditorTool) {
        val liquifyActive = isLiquifyToolActive()
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

    override fun previewAspect(): Float =
        EditorPreviewGeometry.previewAspect(
            undoController.currentGraph,
            panel.currentTool,
            renderer.sourceAspect()
        )

    override fun onLiquifySettingsChanged(settings: LiquifyBrushSettings) {
        liquifySession.updateSettings { settings }
        overlaySync.syncBrush()
    }

    override fun onLiquifyResetRequested() {
        liquifyController.requestReset()
        updateHistoryButtons()
    }

    private fun createOverlayGestures(): CropOverlayView.Listener = EditorGeometryGestures(
        undoController = undoController,
        onDragged = {
            mainHandler.removeCallbacks(settleFullQualityRunnable)
            renderCurrentGraph(useProxy = true)
        },
        onFinished = {
            renderCurrentGraph(useProxy = false)
            updateHistoryButtons()
        },
        onCanvasTransformChanged = ::onCanvasTransformChanged,
        onCompareHold = ::setCompareOriginal
    )

    private fun createLiquifyGestures(): LiquifyBrushOverlayView.Listener = LiquifyGestures(
        session = liquifySession,
        projection = ::liquifyProjection,
        onMeshChanged = ::publishLiquifyMesh,
        onStrokeFinished = {
            liquifyController.onStrokeCommitted()
            renderCurrentGraph(useProxy = false)
            updateHistoryButtons()
        },
        onCanvasTransformChanged = ::onCanvasTransformChanged,
        onResetRequested = ::onLiquifyResetRequested
    )

    /** Rebuilds the pre-crop projection per stroke so a live crop or straighten edit is reflected. */
    private fun liquifyProjection(): LiquifyStrokeProjection =
        LiquifyStrokeProjection.of(undoController.currentGraph.geometry, renderer.sourceAspect())

    private fun setupGlSurfaceView() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        preferLinearSource =
            (activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= MIN_GLES_VERSION_ES3
        renderer.preferEs3 = preferLinearSource
        binding.glSurfaceView.setEGLContextClientVersion(if (preferLinearSource) 3 else 2)
        binding.glSurfaceView.setRenderer(renderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    private fun setupOverlay() {
        binding.cropOverlayView.listener = createOverlayGestures()
        binding.cropOverlayView.tool = EditorTool.NONE
        binding.liquifyBrushOverlayView.listener = createLiquifyGestures()
        binding.liquifyBrushOverlayView.visibility = View.GONE
        overlaySync.syncBrush()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTopBar() {
        binding.btnUndo.setOnClickListener { undoStep() }
        binding.btnRedo.setOnClickListener { redoStep() }
        binding.btnCompare.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> setCompareOriginal(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setCompareOriginal(false)
            }
            false
        }
        binding.baseLookToggle.onLookSelected = ::selectBaseLook
        binding.btnDone.setOnClickListener { saveEditAndFinish() }
        updateHistoryButtons()
    }

    /**
     * Undo prefers the liquify stroke history while the brush tool is active, then falls back to the
     * parametric `EditGraph` history.
     */
    private fun undoStep() {
        if (isLiquifyToolActive() && liquifyController.undo()) return
        applyHistoryGraph(undoController.undo())
    }

    private fun redoStep() {
        if (isLiquifyToolActive() && liquifyController.redo()) return
        applyHistoryGraph(undoController.redo())
    }

    private fun isLiquifyToolActive(): Boolean =
        panel.currentGroup == EditorGroup.GEOMETRY && panel.currentTool == EditorTool.LIQUIFY

    private fun applyHistoryGraph(graph: EditGraph) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCurrentGraph(useProxy = false)
        updateBaseLookPill(graph.baseLook)
        updateHistoryButtons()
    }

    private fun renderCurrentGraph(useProxy: Boolean) {
        val graph = undoController.currentGraph
        val preview = EditorPreviewGeometry.previewGraph(graph, panel.currentTool)
        previewSession.applyGraph(preview, useProxy = useProxy)
        overlaySync.sync(graph)
        panel.refresh()
        updateBaseLookPill(graph.baseLook)
        binding.glSurfaceView.requestRender()
    }

    private fun publishLiquifyMesh() {
        previewSession.applyLiquifyMesh(liquifySession.mesh)
        binding.glSurfaceView.requestRender()
    }

    private fun onCanvasTransformChanged(transform: CanvasTransform) {
        renderer.canvasTransform = CanvasViewportTransform(
            scale = transform.scale,
            offsetX = transform.offsetX,
            offsetY = transform.offsetY
        )
        overlaySync.syncCanvasTransform(transform)
        binding.glSurfaceView.requestRender()
    }

    private fun setCompareOriginal(comparing: Boolean) {
        if (renderer.compareOriginal == comparing) return
        renderer.compareOriginal = comparing
        binding.compareStatusPill.visibility = if (comparing) View.VISIBLE else View.GONE
        binding.glSurfaceView.requestRender()
    }

    private fun selectBaseLook(baseLook: BaseLook) {
        val current = undoController.currentGraph
        if (current.baseLook == baseLook) return
        undoController.onParameterDrag(
            current.copy(baseLook = baseLook),
            EditParameterKey.BASE_LOOK
        )
        undoController.onParameterRelease()
        applyHistoryGraph(undoController.currentGraph)
    }

    private fun updateBaseLookPill(baseLook: BaseLook) {
        binding.baseLookToggle.visibility =
            if (isRaw && previewSession.supportsBaseLook) View.VISIBLE else View.GONE
        binding.baseLookToggle.setLook(baseLook)
    }

    private fun updateHistoryButtons() {
        val canUndo = undoController.canUndo || (isLiquifyToolActive() && liquifyController.canUndo)
        val canRedo = undoController.canRedo || (isLiquifyToolActive() && liquifyController.canRedo)
        binding.btnUndo.isEnabled = canUndo
        binding.btnRedo.isEnabled = canRedo
        binding.btnUndo.alpha = if (canUndo) 1.0f else 0.4f
        binding.btnRedo.alpha = if (canRedo) 1.0f else 0.4f
    }

    private fun loadInitialPhotoAndEditState() {
        lifecycleScope.launch {
            val loaded = sessionLoader.load(
                sourcePath = sourcePath,
                isRaw = isRaw,
                preferLinear = preferLinearSource
            )
            if (!loaded.hasSource) {
                Toast.makeText(
                    this@EditorActivity,
                    R.string.editor_source_missing,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            undoController.resetTo(loaded.graph)
            publishLiquifyMesh()
            renderCurrentGraph(useProxy = false)
            updateHistoryButtons()
        }
    }

    private fun saveEditAndFinish() {
        binding.btnDone.isEnabled = false
        val finalGraph = undoController.currentGraph
        lifecycleScope.launch {
            saveCoordinator.persist(finalGraph)
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
        private const val MIN_GLES_VERSION_ES3 = 0x30000
        private const val SETTLE_DELAY_MS = 150L

        fun createIntent(context: Context, args: EditorLaunchArgs): Intent {
            return Intent(context, EditorActivity::class.java).apply {
                putExtra(EXTRA_PHOTO_ID, args.photoId)
                putExtra(EXTRA_SOURCE_PATH, args.sourcePath)
                putExtra(EXTRA_IS_RAW, args.isRaw)
            }
        }
    }
}
