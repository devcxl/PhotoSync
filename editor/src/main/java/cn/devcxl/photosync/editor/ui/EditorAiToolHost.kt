package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.graphics.Bitmap
import android.view.View

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.domain.PresetSubjectGate
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.widgets.SubjectPanelView

/**
 * Owns every AI tool of the editor: the shared inference bar, the subject cut-out, the erase brush
 * pair and the P4 cloud workflow.
 *
 * Keeping the wiring here lets [EditorActivity] stay a lifecycle shell: the activity only hands in
 * the binding, the session state and the render callbacks, while this host owns cancellation,
 * SAF model import routing, the shared-ORT lifecycle and the cloud controller.
 */
internal class EditorAiToolHost(
    context: Context,
    private val binding: ActivityEditorBinding,
    filesDir: File,
    photoId: Long,
    sourcePath: String,
    isRaw: Boolean,
    private val undoController: EditorUndoController,
    private val previewSession: EditorPreviewSession,
    private val scope: CoroutineScope,
    private val liquifySession: LiquifySession,
    private val analysisProvider: () -> PortraitAnalysis?,
    onCanvasTransform: (CanvasTransform) -> Unit,
    private val onGraphChanged: () -> Unit,
    onRepaintRequested: () -> Unit,
    launchImport: ((InputStream) -> Unit) -> Unit,
    private val panelProvider: () -> EditorPanelController,
    private val onPreviewGraph: (EditGraph) -> Unit
) {

    private val inferenceBar = AiInferenceBarController(
        host = object : AiInferenceBarController.Host {
            override fun setVisible(visible: Boolean) {
                binding.aiInferenceBar.visibility = if (visible) View.VISIBLE else View.GONE
            }

            override fun setLabel(label: CharSequence) {
                binding.aiInferenceLabel.text = label
            }

            override fun setProgress(percent: Int) {
                binding.aiInferenceProgress.progress = percent
            }
        },
        onCancelRequested = ::cancelAiJobs
    )

    private lateinit var subjectController: EditorSubjectController
    private lateinit var inpaintTools: EditorInpaintToolHost
    private lateinit var cloud: EditorCloudController
    private val warpProvider = EditorInpaintWarpProvider(
        undoController = undoController,
        liquifySession = liquifySession,
        analysisProvider = analysisProvider
    )
    private var cloudEnabled = false

    init {
        subjectController = EditorSubjectController(
            context = context,
            filesDir = filesDir,
            photoId = photoId,
            undoController = undoController,
            previewSession = previewSession,
            inferenceBar = inferenceBar,
            scope = scope,
            sourceProvider = { previewSession.source },
            onGraphCommitted = { _, _ -> onGraphChanged() },
            onImportRequested = { launchImport(subjectController::importModel) },
            onRepaintRequested = onRepaintRequested
        )
        inpaintTools = EditorInpaintToolHost(
            context = context,
            binding = binding,
            filesDir = filesDir,
            photoId = photoId,
            sourcePath = sourcePath,
            isRaw = isRaw,
            undoController = undoController,
            previewSession = previewSession,
            inferenceBar = inferenceBar,
            scope = scope,
            analysisProvider = analysisProvider,
            liquifySession = liquifySession,
            onCanvasTransform = onCanvasTransform,
            onGraphChanged = onGraphChanged,
            onRepaintRequested = onRepaintRequested,
            onSubjectCompositeStale = {
                subjectController.refreshSourceComposite(undoController.currentGraph)
            },
            launchImport = launchImport
        )
        binding.subjectPanel.root.gradingListener = object : SubjectPanelView.GradingListener {
            override fun onSubjectAdjusted(subject: SubjectOps) =
                subjectController.requestAdjustment(subject)

            override fun onSubjectAdjustmentStopped() = subjectController.endAdjustment()
        }
        cloud = EditorCloudBindings.create(
            context = context,
            photoId = photoId,
            panelProvider = panelProvider,
            binding = binding,
            inferenceBar = inferenceBar,
            undoController = undoController,
            scope = scope,
            postPassAProvider = { postPassABitmap() },
            paintedMaskProvider = { paintedMaskBitmap() },
            subjectMaskProvider = { subjectController.currentMask() },
            onGraphCommitted = { onGraphChanged() },
            onCloudLayerPreview = { layer ->
                onPreviewGraph(undoController.currentGraph.copy(cloudLayer = layer))
            }
        )
        cloud.installGeometryGuard(undoController)
    }

    /** Routes a subject mode chip tap. */
    fun requestSubjectMode(mode: SubjectBackgroundMode) = subjectController.requestMode(mode)

    /** Routes a background swatch tap. */
    fun requestSubjectColor(argb: Int) = subjectController.requestColor(argb)

    /** Restores the cut-out after reopening the editor. */
    fun restoreSubject(graph: EditGraph) = subjectController.restore(graph)

    /**
     * Computes the photo's own `SubjectMask` when an applied preset carries subject parameters.
     *
     * Presets never copy the source photo's mask, so the first application on a mask-less photo
     * triggers the standard-tier segmentation here; a neutral preset or an existing mask is a no-op.
     */
    fun ensureSubjectMaskForPreset(graph: EditGraph) {
        if (!PresetSubjectGate.needsMask(graph)) return
        subjectController.ensureMaskForPreset()
    }

    /** True when the P4 cloud switch is on; read off the main thread during [refreshCloudAvailability]. */
    fun isCloudEnabled(): Boolean = cloudEnabled

    /**
     * Re-reads the P4 switch and repaints the drawer.
     *
     * Called from `onResume`, so returning from the settings screen takes effect without reopening
     * the editor: the cloud chip appears or disappears and the panel follows the active tool.
     */
    fun refreshCloudAvailability() {
        scope.launch {
            val enabled = withContext(Dispatchers.IO) { cloud.isEnabled() }
            cloudEnabled = enabled
            cloud.refresh(enabled)
            panelProvider().refreshCloudAvailability()
        }
    }

    /** Cancels every AI job and drops the listeners; the shared ORT session stays alive. */
    fun release() {
        binding.subjectPanel.root.gradingListener = null
        cloud.release()
        inferenceBar.dismiss()
        inpaintTools.release()
        subjectController.release()
    }

    /** Convenience for the activity's tool-change hook. */
    fun onToolChanged(tool: EditorTool) {
        if (tool == EditorTool.AI_ERASE) inpaintTools.onToolOpened()
        if (tool == EditorTool.AI_CLOUD) cloud.refresh()
    }

    private fun cancelAiJobs() {
        inpaintTools.brush.cancelInference()
        inpaintTools.cancelInstall()
        subjectController.cancel()
        cloud.cancel()
    }

    private fun postPassABitmap() =
        previewSession.renderPostPassABitmap(undoController.currentGraph, warpProvider())

    private fun paintedMaskBitmap(): Bitmap? {
        val strokes = binding.inpaintBrushOverlayView.strokeLayer
        if (strokes.isEmpty) return null
        val graph = undoController.currentGraph
        val sourceAspect = previewSession.source?.let { it.width.toFloat() / it.height } ?: 1.0f
        val (width, height) = CloudPaintedMask.sizeFor(
            GeometryMath.outputAspect(graph.geometry, sourceAspect)
        )
        return CloudPaintedMask.rasterize(strokes, width, height)
    }
}
