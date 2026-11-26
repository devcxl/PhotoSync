package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.view.View

import androidx.lifecycle.LifecycleCoroutineScope

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.ui.widgets.SubjectPanelView

/**
 * Owns every AI tool of the editor: the shared inference bar, the subject cut-out and the erase
 * brush pair.
 *
 * Keeping the wiring here lets [EditorActivity] stay a lifecycle shell: the activity only hands in
 * the binding, the session state and the render callbacks, while this host owns cancellation,
 * SAF model import routing and the shared-ORT lifecycle.
 */
internal class EditorAiToolHost(
    context: Context,
    private val binding: ActivityEditorBinding,
    filesDir: File,
    photoId: Long,
    sourcePath: String,
    isRaw: Boolean,
    private val undoController: EditorUndoController,
    previewSession: EditorPreviewSession,
    scope: LifecycleCoroutineScope,
    liquifySession: LiquifySession,
    analysisProvider: () -> PortraitAnalysis?,
    onCanvasTransform: (CanvasTransform) -> Unit,
    onGraphChanged: () -> Unit,
    onRepaintRequested: () -> Unit,
    launchImport: ((InputStream) -> Unit) -> Unit
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
    }

    private fun cancelAiJobs() {
        inpaintTools.brush.cancelInference()
        inpaintTools.cancelInstall()
        subjectController.cancel()
    }

    /** Routes a subject mode chip tap. */
    fun requestSubjectMode(mode: SubjectBackgroundMode) = subjectController.requestMode(mode)

    /** Routes a background swatch tap. */
    fun requestSubjectColor(argb: Int) = subjectController.requestColor(argb)

    /** Restores the cut-out after reopening the editor. */
    fun restoreSubject(graph: EditGraph) = subjectController.restore(graph)

    /** Cancels every AI job and drops the listeners; the shared ORT session stays alive. */
    fun release() {
        binding.subjectPanel.root.gradingListener = null
        inferenceBar.dismiss()
        inpaintTools.release()
        subjectController.release()
    }

    /** Convenience for the activity's tool-change hook. */
    fun onToolChanged(tool: EditorTool) {
        if (tool == EditorTool.AI_ERASE) inpaintTools.onToolOpened()
    }
}
