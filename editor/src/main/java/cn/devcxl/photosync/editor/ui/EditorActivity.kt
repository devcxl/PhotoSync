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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.EditorLaunchArgs
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.panels.EditorPreviewGeometry
import cn.devcxl.photosync.editor.ui.widgets.CropOverlayView

/**
 * Full-screen non-destructive photo editor.
 *
 * Combines 16-bit linear RAW decoding + `BaseLook` baseline switching with Pass A geometry /
 * lateral chromatic-aberration resampling and Pass B linear-light exposure grading.
 */
class EditorActivity : AppCompatActivity(), EditorPanelController.Host {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var previewSession: EditorPreviewSession
    private lateinit var saveCoordinator: EditorSaveCoordinator
    private lateinit var panel: EditorPanelController
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
        previewSession = EditorPreviewSession(renderer, cacheDir, photoId)
        saveCoordinator = EditorSaveCoordinator(photoId, previewSession)

        setupGlSurfaceView()
        setupOverlay()
        setupTopBar()
        panel = EditorPanelController(this, binding, undoController, this)
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
        previewSession.release()
        super.onDestroy()
    }

    override fun onEditorToolChanged(tool: EditorTool) {
        binding.cropOverlayView.tool = tool
        binding.cropOverlayView.group = panel.currentGroup
        renderCurrentGraph(useProxy = false)
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
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTopBar() {
        binding.btnUndo.setOnClickListener { applyHistoryGraph(undoController.undo()) }
        binding.btnRedo.setOnClickListener { applyHistoryGraph(undoController.redo()) }
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
        syncOverlay(graph)
        panel.refresh()
        updateBaseLookPill(graph.baseLook)
        binding.glSurfaceView.requestRender()
    }

    private fun syncOverlay(graph: EditGraph) {
        val overlay = binding.cropOverlayView
        overlay.cropRect = graph.geometry.crop
        overlay.perspectiveQuad = graph.geometry.perspective.toFloatArray()
        overlay.lockedAspect = panel.lockedAspect
        overlay.frameAspect = previewAspect()
        overlay.tool = panel.currentTool
        overlay.group = panel.currentGroup
    }

    private fun onCanvasTransformChanged(transform: CanvasTransform) {
        renderer.canvasScale = transform.scale
        renderer.canvasOffsetX = transform.offsetX
        renderer.canvasOffsetY = transform.offsetY
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
        binding.btnUndo.isEnabled = undoController.canUndo
        binding.btnRedo.isEnabled = undoController.canRedo
        binding.btnUndo.alpha = if (undoController.canUndo) 1.0f else 0.4f
        binding.btnRedo.alpha = if (undoController.canRedo) 1.0f else 0.4f
    }

    private fun loadInitialPhotoAndEditState() {
        lifecycleScope.launch {
            val (loadedGraph, source) = withContext(Dispatchers.IO) {
                readPersistedGraph() to previewSession.loadSource(
                    sourcePath = sourcePath,
                    isRaw = isRaw,
                    preferLinear = preferLinearSource
                )
            }
            if (source == null) {
                Toast.makeText(
                    this@EditorActivity,
                    R.string.editor_source_missing,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            undoController.resetTo(loadedGraph)
            renderCurrentGraph(useProxy = false)
            updateHistoryButtons()
        }
    }

    private suspend fun readPersistedGraph(): EditGraph {
        val existingRecord = EditorHostRegistry.editStore?.getEditForPhoto(photoId)
        return existingRecord?.graphJson?.let { json ->
            EditGraphJsonCodec.decode(json) ?: run {
                Timber.w("Corrupt EditGraph JSON for photoId=%d; resetting to neutral", photoId)
                EditGraph()
            }
        } ?: EditGraph()
    }

    private fun saveEditAndFinish() {
        binding.btnDone.isEnabled = false
        val finalGraph = undoController.currentGraph
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveCoordinator.persist(finalGraph) }
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
