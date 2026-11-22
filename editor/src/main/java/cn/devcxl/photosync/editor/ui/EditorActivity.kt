package cn.devcxl.photosync.editor.ui

import java.util.Locale
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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.EditorLaunchArgs
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.render.EditorBitmapLoader
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.ui.widgets.DialSeekBar

/**
 * Full-screen non-destructive photo editor activity (Tracer-Bullet Slice: Exposure + Undo/Redo +
 * Before/After Compare + GLES 3.0 1024px Proxy Throttling).
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private val renderer = EditorGlRenderer()
    private val undoController = EditorUndoController()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var photoId: Long = 0L
    private var sourcePath: String = ""
    private var isRaw: Boolean = false
    private var fullPreviewBitmap: Bitmap? = null
    private var proxyPreviewBitmap: Bitmap? = null

    private val settleFullQualityRunnable = Runnable {
        renderer.useFastProxy = false
        binding.glSurfaceView.requestRender()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoId = intent.getLongExtra(EXTRA_PHOTO_ID, 0L)
        sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        isRaw = intent.getBooleanExtra(EXTRA_IS_RAW, false)

        setupGlSurfaceView()
        setupControls()
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
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGlSurfaceView() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val supportsEs3 = (am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000
        binding.glSurfaceView.setEGLContextClientVersion(if (supportsEs3) 3 else 2)
        binding.glSurfaceView.setRenderer(renderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        val canvasGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onLongPress(e: MotionEvent) {
                    setCompareOriginal(true)
                }
            }
        )
        binding.glSurfaceView.setOnTouchListener { _, event ->
            canvasGestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (renderer.compareOriginal) {
                    setCompareOriginal(false)
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.dialSeekBar.minValue = -5.0f
        binding.dialSeekBar.maxValue = 5.0f
        binding.dialSeekBar.stepSize = 0.05f
        binding.dialSeekBar.zeroSnapThreshold = 0.10f
        binding.dialSeekBar.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (!fromUser) return
                mainHandler.removeCallbacks(settleFullQualityRunnable)
                val current = undoController.currentGraph
                val updated = current.copy(tone = current.tone.copy(exposure = value))
                val sanitized = undoController.onParameterDrag(updated, KEY_TONE_EXPOSURE)
                renderer.useFastProxy = true
                renderer.exposureEv = sanitized.tone.exposure
                binding.glSurfaceView.requestRender()
                updateControlsUi()
            }

            override fun onStopTrackingTouch() {
                undoController.onParameterRelease()
                mainHandler.removeCallbacks(settleFullQualityRunnable)
                mainHandler.postDelayed(settleFullQualityRunnable, SETTLE_DELAY_MS)
                updateControlsUi()
            }
        }

        val chipGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    resetExposureToZero()
                    return true
                }
            }
        )
        binding.chipExposure.setOnTouchListener { _, event ->
            chipGestureDetector.onTouchEvent(event)
            true
        }

        binding.btnUndo.setOnClickListener {
            val graph = undoController.undo()
            applyGraphToRendererAndUi(graph)
        }
        binding.btnRedo.setOnClickListener {
            val graph = undoController.redo()
            applyGraphToRendererAndUi(graph)
        }
        binding.btnCompare.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> setCompareOriginal(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setCompareOriginal(false)
            }
            false
        }
        binding.btnDone.setOnClickListener {
            saveEditAndFinish()
        }
        updateControlsUi()
    }

    private fun loadInitialPhotoAndEditState() {
        lifecycleScope.launch {
            val (loadedGraph, bmp, proxy) = withContext(Dispatchers.IO) {
                val existingRecord = EditorHostRegistry.editStore?.getEditForPhoto(photoId)
                val graph = existingRecord?.graphJson?.let { EditGraphJsonCodec.decode(it) }
                    ?: EditGraph()
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
            applyGraphToRendererAndUi(undoController.currentGraph)
        }
    }

    private fun resetExposureToZero() {
        val current = undoController.currentGraph
        if (abs(current.tone.exposure) < 1e-4f) return
        val resetGraph = current.copy(tone = current.tone.copy(exposure = 0.0f))
        undoController.onParameterDrag(resetGraph, KEY_TONE_EXPOSURE)
        undoController.onParameterRelease()
        applyGraphToRendererAndUi(undoController.currentGraph)
    }

    private fun applyGraphToRendererAndUi(graph: EditGraph) {
        renderer.useFastProxy = false
        renderer.exposureEv = graph.tone.exposure
        binding.dialSeekBar.setValue(graph.tone.exposure, notify = false)
        binding.glSurfaceView.requestRender()
        updateControlsUi()
    }

    private fun setCompareOriginal(comparing: Boolean) {
        renderer.compareOriginal = comparing
        binding.compareStatusPill.visibility = if (comparing) View.VISIBLE else View.GONE
        binding.glSurfaceView.requestRender()
    }

    private fun updateControlsUi() {
        val exposure = undoController.currentGraph.tone.exposure
        binding.activeValueText.text = String.format(
            Locale.US,
            getString(R.string.editor_exposure_value_fmt),
            exposure
        )
        val isModified = abs(exposure) >= 1e-4f
        binding.chipExposure.setText(
            if (isModified) R.string.editor_tool_Exposure_modified else R.string.editor_tool_exposure
        )
        binding.btnUndo.isEnabled = undoController.canUndo
        binding.btnRedo.isEnabled = undoController.canRedo
        binding.btnUndo.alpha = if (undoController.canUndo) 1.0f else 0.4f
        binding.btnRedo.alpha = if (undoController.canRedo) 1.0f else 0.4f
    }

    private fun saveEditAndFinish() {
        binding.btnDone.isEnabled = false
        val finalGraph = undoController.currentGraph
        val srcBitmap = fullPreviewBitmap
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val previewPath = if (!finalGraph.isIdentity() && srcBitmap != null) {
                    EditorGlRenderer.renderPreviewCacheToFile(
                        source = srcBitmap,
                        exposureEv = finalGraph.tone.exposure,
                        cacheDir = cacheDir,
                        photoId = photoId
                    )
                } else {
                    null
                }
                EditorHostRegistry.editStore?.saveEditForPhoto(
                    photoId = photoId,
                    graph = finalGraph,
                    previewCachePath = previewPath
                )
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
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_IS_RAW = "extra_is_raw"
        private const val KEY_TONE_EXPOSURE = "tone.exposure"
        private const val SETTLE_DELAY_MS = 150L

        fun createIntent(context: Context, args: EditorLaunchArgs): Intent {
            return Intent(context, EditorActivity::class.java).apply {
                putExtra(EXTRA_PHOTO_ID, args.photoId)
                putExtra(EXTRA_SOURCE_PATH, args.sourcePath)
                putExtra(EXTRA_DISPLAY_NAME, args.displayName)
                putExtra(EXTRA_IS_RAW, args.isRaw)
            }
        }
    }
}
