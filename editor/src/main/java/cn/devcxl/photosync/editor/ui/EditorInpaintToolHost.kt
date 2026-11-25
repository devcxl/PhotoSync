package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.view.View

import kotlinx.coroutines.CoroutineScope

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditorUndoController
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
    undoController: EditorUndoController,
    previewSession: EditorPreviewSession,
    inferenceBar: AiInferenceBarController,
    scope: CoroutineScope,
    onCanvasTransform: (CanvasTransform) -> Unit,
    onGraphChanged: () -> Unit,
    onRepaintRequested: () -> Unit,
    onSubjectCompositeStale: () -> Unit,
    launchImport: ((InputStream) -> Unit) -> Unit
) {

    private lateinit var inpaint: EditorInpaintController

    /** The brush controller the activity cancels and asks to re-run after a model install. */
    lateinit var brush: InpaintBrushController
        private set

    init {
        inpaint = EditorInpaintController(
            context = context,
            filesDir = filesDir,
            photoId = photoId,
            undoController = undoController,
            inferenceBar = inferenceBar,
            scope = scope,
            sourceProvider = { previewSession.source },
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
            executor = inpaint.executor()
        )
        brush.attach(binding.inpaintBrushOverlayView, binding.inpaintPanelView)
        binding.inpaintBrushOverlayView.visibility = View.GONE
    }

    /** Releases the ORT session, cancels the run and drops both listeners. */
    fun release() {
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
