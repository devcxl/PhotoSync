package cn.devcxl.photosync.editor.ui

import java.util.Locale
import kotlin.math.abs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
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

/**
 * Full-screen non-destructive photo editor (Tracer-Bullet Slice: Exposure + RAW BaseLook +
 * Undo/Redo + Before/After Compare + GLES 3.0 1024px Proxy Throttling).
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var previewSession: EditorPreviewSession
    private lateinit var saveCoordinator: EditorSaveCoordinator
    private val renderer = EditorGlRenderer()
    private val undoController = EditorUndoController()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var photoId: Long = 0L
    private var sourcePath: String = ""
    private var isRaw: Boolean = false
    private var preferLinearSource: Boolean = false

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
        previewSession = EditorPreviewSession(renderer, cacheDir, photoId)
        saveCoordinator = EditorSaveCoordinator(photoId, previewSession)

        setupGlSurfaceView()
        setupExposureSlider()
        setupActionButtons()
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
        previewSession.release()
        super.onDestroy()
    }

    private fun setupGlSurfaceView() {
        preferLinearSource = EditorCanvasSetup.supportsEs3(this)
        EditorCanvasSetup.configure(
            context = this,
            surfaceView = binding.glSurfaceView,
            renderer = renderer,
            supportsEs3 = preferLinearSource
        ) { comparing -> setCompareOriginal(comparing) }
    }

    private fun setupExposureSlider() {
        ExposureSliderBinder.bind(
            seekBar = binding.dialSeekBar,
            onExposureChanged = ::onExposureChanged,
            onExposureReleased = ::onExposureReleased
        )
    }

    private fun onExposureChanged(value: Float) {
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        val current = undoController.currentGraph
        val updated = current.copy(tone = current.tone.copy(exposure = value))
        val sanitized = undoController.onParameterDrag(updated, EditParameterKey.TONE_EXPOSURE)
        renderer.useFastProxy = true
        renderer.exposureEv = sanitized.tone.exposure
        binding.glSurfaceView.requestRender()
        updateControlsUi()
    }

    private fun onExposureReleased() {
        undoController.onParameterRelease()
        mainHandler.removeCallbacks(settleFullQualityRunnable)
        mainHandler.postDelayed(settleFullQualityRunnable, SETTLE_DELAY_MS)
        updateControlsUi()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupActionButtons() {
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
            applyGraphToRendererAndUi(undoController.undo())
        }
        binding.btnRedo.setOnClickListener {
            applyGraphToRendererAndUi(undoController.redo())
        }
        binding.btnCompare.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> setCompareOriginal(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setCompareOriginal(false)
            }
            false
        }
        binding.baseLookToggle.onLookSelected = ::selectBaseLook
        binding.btnDone.setOnClickListener { saveEditAndFinish() }
        EditorCategoryTabs.bindComingSoon(
            this,
            listOf(
                binding.tabPreset,
                binding.tabGeometry,
                binding.tabColor,
                binding.tabDetail,
                binding.tabPortrait
            )
        )
        updateControlsUi()
    }

    private fun loadInitialPhotoAndEditState() {
        lifecycleScope.launch {
            val loadedGraph = withContext(Dispatchers.IO) {
                readPersistedGraph() to previewSession.loadSource(
                    sourcePath = sourcePath,
                    isRaw = isRaw,
                    preferLinear = preferLinearSource
                )
            }
            if (loadedGraph.second == null) {
                Toast.makeText(
                    this@EditorActivity,
                    R.string.editor_source_missing,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            undoController.resetTo(loadedGraph.first)
            applyGraphToRendererAndUi(undoController.currentGraph)
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

    private fun selectBaseLook(baseLook: BaseLook) {
        val current = undoController.currentGraph
        if (current.baseLook == baseLook) return
        undoController.onParameterDrag(
            current.copy(baseLook = baseLook),
            EditParameterKey.BASE_LOOK
        )
        undoController.onParameterRelease()
        applyGraphToRendererAndUi(undoController.currentGraph)
    }

    private fun resetExposureToZero() {
        val current = undoController.currentGraph
        if (abs(current.tone.exposure) < 1e-4f) return
        val resetGraph = current.copy(tone = current.tone.copy(exposure = 0.0f))
        undoController.onParameterDrag(resetGraph, EditParameterKey.TONE_EXPOSURE)
        undoController.onParameterRelease()
        applyGraphToRendererAndUi(undoController.currentGraph)
    }

    private fun applyGraphToRendererAndUi(graph: EditGraph) {
        previewSession.applyGraph(graph)
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
        val graph = undoController.currentGraph
        val exposure = graph.tone.exposure
        binding.activeValueText.text = String.format(
            Locale.US,
            getString(R.string.editor_exposure_value_fmt),
            exposure
        )
        val isModified = abs(exposure) >= 1e-4f
        binding.chipExposure.setText(
            if (isModified) R.string.editor_tool_exposure_modified else R.string.editor_tool_exposure
        )
        binding.btnUndo.isEnabled = undoController.canUndo
        binding.btnRedo.isEnabled = undoController.canRedo
        binding.btnUndo.alpha = if (undoController.canUndo) 1.0f else 0.4f
        binding.btnRedo.alpha = if (undoController.canRedo) 1.0f else 0.4f
        updateBaseLookPill(graph.baseLook)
    }

    /**
     * The pill is only actionable when the staged source is 16-bit linear: an 8-bit RAW fallback
     * already has the browse look baked into its texels, so switching the baseline would lie.
     */
    private fun updateBaseLookPill(baseLook: BaseLook) {
        binding.baseLookToggle.visibility =
            if (isRaw && previewSession.supportsBaseLook) View.VISIBLE else View.GONE
        binding.baseLookToggle.setLook(baseLook)
    }

    private fun saveEditAndFinish() {
        binding.btnDone.isEnabled = false
        val finalGraph = undoController.currentGraph
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveCoordinator.persist(finalGraph) }
            setResult(Activity.RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent(): Intent = Intent().apply {
        putExtra(EXTRA_PHOTO_ID, photoId)
        putExtra(EXTRA_SOURCE_PATH, sourcePath)
    }

    companion object {
        const val EXTRA_PHOTO_ID = "extra_photo_id"
        const val EXTRA_SOURCE_PATH = "extra_source_path"
        private const val EXTRA_IS_RAW = "extra_is_raw"
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
