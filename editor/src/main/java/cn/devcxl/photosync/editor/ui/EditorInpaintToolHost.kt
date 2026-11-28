package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.view.View

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.ui.panels.InpaintBrushController

/**
 * Owns the `AI -> 消除` tool pair so `EditorActivity` keeps only lifecycle plumbing.
 *
 * [EditorInpaintController] runs the model and commits the graph; [InpaintBrushController] owns the
 * painted strokes and the cancellable run. The host also keeps the SAF import route of the erase
 * tool, which must feed the same controller that asked for it.
 */
internal class EditorInpaintToolHost(
    context: Context,
    private val binding: ActivityEditorBinding,
    filesDir: File,
    photoId: Long,
    sourcePath: String,
    isRaw: Boolean,
    undoController: EditorUndoController,
    previewSession: EditorPreviewSession,
    inferenceBar: AiInferenceBarController,
    private val scope: CoroutineScope,
    analysisProvider: () -> PortraitAnalysis?,
    liquifySession: LiquifySession,
    onCanvasTransform: (CanvasTransform) -> Unit,
    onGraphChanged: () -> Unit,
    onRepaintRequested: () -> Unit,
    onSubjectCompositeStale: () -> Unit,
    launchImport: ((InputStream) -> Unit) -> Unit
) {

    private lateinit var inpaint: EditorInpaintController

    /** The composed warp used to project brush coordinates; shared with the brush controller. */
    private val warpFieldProvider = EditorInpaintWarpProvider(
        undoController = undoController,
        liquifySession = liquifySession,
        analysisProvider = analysisProvider
    )

    /** The brush controller the activity cancels and asks to re-run after a model install. */
    lateinit var brush: InpaintBrushController
        private set

    private var sizeProbeJob: Job? = null
    private var sizeProbed = false

    init {
        inpaint = EditorInpaintController(
            context = context,
            filesDir = filesDir,
            photoId = photoId,
            sourcePath = sourcePath,
            isRaw = isRaw,
            undoController = undoController,
            inferenceBar = inferenceBar,
            scope = scope,
            sourceProvider = { previewSession.source },
            warpFieldProvider = { warpFieldProvider() },
            onCanvasTransform = onCanvasTransform,
            onGraphCommitted = { _, _ -> onGraphChanged() },
            onPatchesApplied = onSubjectCompositeStale,
            onImportRequested = { launchImport { stream -> inpaint.importModel(stream) } },
            onConfirmRequested = { brush.confirmInpaint() }
        )
        brush = InpaintBrushController(
            host = inpaint,
            canvas = binding.inpaintBrushOverlayView,
            panel = binding.inpaintPanelView,
            scope = scope,
            executor = inpaint.executor(),
            projectionProvider = {
                InpaintStrokeProjection.of(
                    geometry = undoController.currentGraph.geometry,
                    srcAspect = sourceAspectProvider(),
                    warpField = warpFieldProvider()
                )
            },
            initialBrushDiameterPx = inpaint.initialBrushDiameterPx,
            initialQualityMode = inpaint.initialQualityMode,
            memoryTier = inpaint.memoryTier(),
            measuredTileMsProvider = { inpaint.measuredTileMs() }
        )
        brush.attach(binding.inpaintBrushOverlayView, binding.inpaintPanelView)
        binding.inpaintBrushOverlayView.visibility = View.GONE
    }

    /**
     * Probes the true source size once the erase tool is opened, so the brush diameter is measured
     * in full-resolution pixels and the workload estimate uses the real tile grid. The decode runs
     * off the main thread and never blocks painting.
     */
    fun onToolOpened() {
        if (sizeProbed) return
        sizeProbeJob?.cancel()
        sizeProbeJob = scope.launch {
            val size = withContext(Dispatchers.IO) { inpaint.probeSourceSize() }
            if (size != null) {
                sizeProbed = true
                brush.sourcePixelSize = size
            }
        }
    }

    private fun sourceAspectProvider(): Float {
        val size = brush.sourcePixelSize
        if (size != null && size.second > 0) return size.first.toFloat() / size.second
        val canvas = binding.inpaintBrushOverlayView
        if (canvas.sourceHeight > 0) {
            return canvas.sourceWidth.toFloat() / canvas.sourceHeight
        }
        return 1.0f
    }

    /** Releases the tool-owned resources; the shared ORT session stays with the process. */
    fun release() {
        sizeProbeJob?.cancel()
        brush.cancelInference()
        inpaint.release()
        binding.inpaintBrushOverlayView.listener = null
        binding.inpaintPanelView.listener = null
    }

    /** Drops an in-flight model download without cancelling the brush's own state. */
    fun cancelInstall() {
        inpaint.cancel()
    }
}
