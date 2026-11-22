package cn.devcxl.photosync.editor.ui

import kotlin.math.abs

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.render.EditorBitmapLoader
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.PreviewCacheWriter
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.panels.EditorPreviewGeometry
import cn.devcxl.photosync.editor.ui.panels.EditorToolCatalog
import cn.devcxl.photosync.editor.ui.widgets.CropOverlayView

/**
 * Full-screen non-destructive photo editor.
 *
 * Delivers the geometry slice (crop, straighten, quarter turn, mirrors, four-point perspective) and
 * the lateral chromatic-aberration sliders on top of the exposure tracer bullet: Pass A resamples
 * geometry and CA, Pass B grades exposure, and "Done" writes an EditGraph plus a preview cache whose
 * frame matches the GL output.
 */
class EditorActivity : AppCompatActivity(), EditorPanelController.Host {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var panel: EditorPanelController
    private val renderer = EditorGlRenderer()
    private val undoController = EditorUndoController()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var photoId: Long = 0L
    private var sourcePath: String = ""
    private var isRaw: Boolean = false
    private var fullPreviewBitmap: Bitmap? = null
    private var proxyPreviewBitmap: Bitmap? = null

    private val settleFullQualityRunnable = Runnable { renderCurrentGraph(useProxy = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoId = intent.getLongExtra(EXTRA_PHOTO_ID, 0L)
        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        isRaw = intent.getBooleanExtra(EXTRA_IS_RAW, false)

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
        renderer.clearBitmaps()
        recyclePreviewBitmaps()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ EditorPanelController.Host

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

    // ---------------------------------------------------------------------------- overlay wiring

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

    // ------------------------------------------------------------------------------ canvas setup

    private fun setupGlSurfaceView() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val supportsEs3 = (activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000
        renderer.preferEs3 = supportsEs3
        binding.glSurfaceView.setEGLContextClientVersion(if (supportsEs3) 3 else 2)
        binding.glSurfaceView.setRenderer(renderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    private fun setupOverlay() {
        binding.cropOverlayView.listener = createOverlayGestures()
        binding.cropOverlayView.tool = EditorTool.NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTopBar() {
        binding.btnUndo.setOnClickListener { applyGraphToRendererAndUi(undoController.undo()) }
        binding.btnRedo.setOnClickListener { applyGraphToRendererAndUi(undoController.redo()) }
        binding.btnCompare.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> setCompareOriginal(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setCompareOriginal(false)
            }
            false
        }
        binding.btnDone.setOnClickListener { saveEditAndFinish() }
        updateHistoryButtons()
    }

    // --------------------------------------------------------------------------------- rendering

    private fun applyGraphToRendererAndUi(graph: EditGraph) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        renderCurrentGraph(useProxy = false)
        updateHistoryButtons()
    }

    private fun renderCurrentGraph(useProxy: Boolean) {
        val graph = undoController.currentGraph
        val preview = EditorPreviewGeometry.previewGraph(graph, panel.currentTool)
        renderer.useFastProxy = useProxy
        renderer.exposureEv = preview.tone.exposure
        renderer.applyGeometry(preview)
        syncOverlay(graph)
        panel.refresh()
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

    private fun updateHistoryButtons() {
        binding.btnUndo.isEnabled = undoController.canUndo
        binding.btnRedo.isEnabled = undoController.canRedo
        binding.btnUndo.alpha = if (undoController.canUndo) 1.0f else 0.4f
        binding.btnRedo.alpha = if (undoController.canRedo) 1.0f else 0.4f
    }

    // ------------------------------------------------------------------------------ persistence

    private fun loadInitialPhotoAndEditState() {
        lifecycleScope.launch {
            val (loadedGraph, bmp, proxy) = withContext(Dispatchers.IO) {
                val existingRecord = EditorHostRegistry.editStore?.getEditForPhoto(photoId)
                val graph = existingRecord?.graphJson?.let { json ->
                    EditGraphJsonCodec.decode(json) ?: run {
                        Timber.w("Corrupt EditGraph JSON for photoId=%d; resetting to neutral", photoId)
                        EditGraph()
                    }
                } ?: EditGraph()
                val fullBmp = EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw)
                val proxyBmp = fullBmp?.let { EditorGlRenderer.createProxyBitmap(it) }
                Triple(graph, fullBmp, proxyBmp)
            }
            if (bmp == null || proxy == null) {
                Toast.makeText(
                    this@EditorActivity,
                    R.string.editor_source_missing,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            fullPreviewBitmap = bmp
            proxyPreviewBitmap = proxy
            undoController.resetTo(loadedGraph)
            renderer.setSourceBitmaps(bmp, proxy)
            renderCurrentGraph(useProxy = false)
            updateHistoryButtons()
        }
    }

    private fun saveEditAndFinish() {
        binding.btnDone.isEnabled = false
        val finalGraph = undoController.currentGraph
        val srcBitmap = fullPreviewBitmap
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val previewPath = if (!finalGraph.isIdentity() && srcBitmap != null) {
                    PreviewCacheWriter.writePreviewCache(
                        source = srcBitmap,
                        graph = finalGraph,
                        cacheDir = cacheDir,
                        photoId = photoId
                    )
                } else {
                    null
                }
                val store = EditorHostRegistry.editStore
                if (store == null) {
                    Timber.e("EditorHostRegistry.editStore is null; edit cannot be persisted")
                } else {
                    store.saveEditForPhoto(
                        photoId = photoId,
                        graph = finalGraph,
                        previewCachePath = previewPath
                    )
                }
            }
            val resultIntent = Intent().apply {
                putExtra(EXTRA_PHOTO_ID, photoId)
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun recyclePreviewBitmaps() {
        val full = fullPreviewBitmap
        val proxy = proxyPreviewBitmap
        fullPreviewBitmap = null
        proxyPreviewBitmap = null
        if (proxy != null && proxy !== full && !proxy.isRecycled) {
            proxy.recycle()
        }
        if (full != null && !full.isRecycled) {
            full.recycle()
        }
    }

    companion object {
        const val EXTRA_PHOTO_ID = "extra_photo_id"
        const val EXTRA_SOURCE_PATH = "extra_source_path"
        private const val EXTRA_IS_RAW = "extra_is_raw"
        private const val SETTLE_DELAY_MS = 150L
        private const val MIN_STRAIGHTEN = -45.0f
        private const val MAX_STRAIGHTEN = 45.0f

        fun createIntent(context: Context, args: EditorLaunchArgs): Intent {
            return Intent(context, EditorActivity::class.java).apply {
                putExtra(EXTRA_PHOTO_ID, args.photoId)
                putExtra(EXTRA_SOURCE_PATH, args.sourcePath)
                putExtra(EXTRA_IS_RAW, args.isRaw)
            }
        }
    }
}
