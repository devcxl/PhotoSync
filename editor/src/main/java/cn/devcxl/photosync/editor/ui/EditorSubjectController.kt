package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.widget.Toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.ModnetSubjectSegmenter
import cn.devcxl.photosync.editor.vision.OrtSessionManager

/**
 * Owns the `AI -> 主体` workflow from the panel tap to the committed `EditGraph`.
 *
 * ## Flow
 * 1. `ORIGINAL` is a plain graph write and never touches the model.
 * 2. A cut-out mode first reuses `subject.maskRef` when its content-addressed PNG still verifies.
 * 3. Without a mask the controller asks for the MODNet artifact: download with the shared
 *    multi-mirror manager, or the caller's SAF import. Only a verified model is used.
 * 4. Inference runs through the single-flight [OrtSessionManager]; the mask is written to
 *    [AiAssetStore], the `SubjectOps` is committed as one undo transaction and the source-space
 *    composite is handed to [EditorPreviewSession].
 *
 * The inference bar is non-blocking and cancellable; cancellation leaves the graph untouched. Every
 * recoverable failure is a localized toast, never a crash.
 */
internal class EditorSubjectController(
    private val context: Context,
    filesDir: File,
    private val photoId: Long,
    private val undoController: EditorUndoController,
    private val previewSession: EditorPreviewSession,
    private val inferenceBar: AiInferenceBarController,
    private val scope: CoroutineScope,
    private val sourceProvider: () -> EditorSource?,
    private val onGraphCommitted: (EditGraph, EditParameterKey) -> Unit,
    onImportRequested: () -> Unit,
    onRepaintRequested: () -> Unit
) {

    /**
     * The process-wide ORT owner, or a private fallback for hosts that did not inject one.
     *
     * A shared manager is never closed here: its tiered idle release, memory-pressure hook and
     * single-flight model switch are what keep one native session alive across both AI tools.
     */
    private val sharedSessions = EditorHostRegistry.ortSessionManager
    private val sessions = sharedSessions ?: OrtSessionManager(
        tier = DeviceMemoryTier.detect(context)
    )
    private val ownsSessions = sharedSessions == null
    private val segmenter = ModnetSubjectSegmenter(sessions, AiAssetStore(filesDir))
    private val installer = SubjectModelInstaller(
        context = context,
        filesDir = filesDir,
        scope = scope,
        inferenceBar = inferenceBar,
        onImportRequested = onImportRequested
    )
    private val composites = SubjectCompositeRefresher(
        filesDir = filesDir,
        sourceProvider = sourceProvider,
        scope = scope,
        onCompositeReady = previewSession::applySubjectComposite,
        onRepaintRequested = onRepaintRequested
    )
    private val inference = SubjectInferenceRunner(
        context = context,
        photoId = photoId,
        inferenceBar = inferenceBar,
        sourceProvider = sourceProvider,
        composites = composites,
        segmenter = segmenter
    )

    private var job: Job? = null
    private var pendingMode: SubjectBackgroundMode? = null
    private var pendingColor: Int = SubjectOps.DEFAULT_BG_COLOR_ARGB

    /** Stops the in-flight download/inference; the graph is left as it was. */
    fun cancel() {
        job?.cancel()
        job = null
        installer.cancel()
        pendingMode = null
        if (inferenceBar.isVisible) inferenceBar.dismiss()
    }

    /** Releases the tool-owned jobs; the shared ORT manager stays alive. */
    fun release() {
        cancel()
        composites.reset()
        if (ownsSessions) sessions.close()
    }

    /**
     * Handles a mode chip tap.
     *
     * @param mode Requested background mode.
     */
    fun requestMode(mode: SubjectBackgroundMode) {
        val graph = undoController.currentGraph
        when {
            mode == SubjectBackgroundMode.ORIGINAL -> {
                previewSession.applySubjectComposite(null)
                commit(graph.copy(subject = graph.subject.copy(bgMode = mode)), null)
            }
            graph.subject.maskRef.isNotBlank() -> applyExistingMask(graph, mode)
            else -> generateMask(graph, mode, graph.subject.bgColorArgb)
        }
    }

    /** Handles a swatch tap; a swatch always selects the solid mode. */
    fun requestColor(argb: Int) {
        val graph = undoController.currentGraph
        if (graph.subject.maskRef.isNotBlank()) {
            applyExistingMask(graph, SubjectBackgroundMode.SOLID, argb)
        } else {
            generateMask(graph, SubjectBackgroundMode.SOLID, argb)
        }
    }

    /**
     * Restores the cut-out after reopening the editor.
     *
     * A cached mask is reused without inference; a subject mode whose mask is gone falls back to
     * `ORIGINAL` so the canvas never shows an unexplained un-edited frame.
     */
    fun restore(graph: EditGraph) {
        if (graph.subject.bgMode == SubjectBackgroundMode.ORIGINAL) return
        if (graph.subject.maskRef.isBlank()) {
            commit(
                graph.copy(subject = graph.subject.copy(bgMode = SubjectBackgroundMode.ORIGINAL)),
                null
            )
            return
        }
        launchWork {
            val composite = composites.load(graph)
            val current = undoController.currentGraph
            if (composite == null) {
                val fallback = current.subject.copy(bgMode = SubjectBackgroundMode.ORIGINAL)
                commitOnMain(current.copy(subject = fallback), null)
            } else {
                commitOnMain(current, composite)
            }
        }
    }

    /** Persists a user-selected `.onnx` file and continues the pending mask generation. */
    fun importModel(input: InputStream) {
        val graph = undoController.currentGraph
        installer.import(
            input = input,
            onReady = { model ->
                generateWithModel(model, graph, pendingMode ?: graph.subject.bgMode, pendingColor)
            },
            onFailed = {
                showUnavailable()
                promptModelInstall()
            }
        )
    }

    /**
     * Re-reads the subject composite after the pixels under it changed, for example a completed
     * erase.
     *
     * The mask stays valid — it describes where the subject is, not what it looks like — but the
     * foreground colours baked into the composite are the pre-repair ones. Rebuilding them keeps the
     * cut-out in lock-step with the canvas; an absent mask or mode is simply a no-op.
     */
    fun refreshSourceComposite(graph: EditGraph) {
        composites.refresh(graph)
    }

    private fun applyExistingMask(
        graph: EditGraph,
        mode: SubjectBackgroundMode,
        color: Int = graph.subject.bgColorArgb
    ) {
        launchWork {
            val composite = composites.load(graph)
            if (composite == null) {
                generateMask(graph.copy(subject = graph.subject.copy(maskRef = "")), mode, color)
                return@launchWork
            }
            commitOnMain(
                undoController.currentGraph.copy(
                    subject = undoController.currentGraph.subject.copy(
                        bgMode = mode,
                        bgColorArgb = color
                    )
                ),
                composite
            )
        }
    }

    private fun generateMask(graph: EditGraph, mode: SubjectBackgroundMode, color: Int) {
        pendingMode = mode
        pendingColor = color
        if (!installer.isInstalled()) {
            installer.showPrompt(onDownload = { downloadModel(graph, mode, color) })
            return
        }
        launchWork {
            generateWithModel(installer.modelFile(), graph, mode, color)
        }
    }

    private fun downloadModel(graph: EditGraph, mode: SubjectBackgroundMode, color: Int) {
        installer.download(
            onReady = { model -> generateWithModel(model, graph, mode, color) },
            onFailed = {
                showUnavailable()
                promptModelInstall()
            }
        )
    }

    private fun promptModelInstall() {
        val graph = undoController.currentGraph
        installer.showPrompt(
            onDownload = {
                downloadModel(
                    graph = graph,
                    mode = pendingMode ?: graph.subject.bgMode,
                    color = pendingColor
                )
            }
        )
    }

    private suspend fun generateWithModel(
        modelFile: File,
        graph: EditGraph,
        mode: SubjectBackgroundMode = graph.subject.bgMode,
        color: Int = graph.subject.bgColorArgb
    ) {
        val result = inference.run(modelFile) ?: return
        commitOnMain(
            undoController.currentGraph.copy(
                subject = undoController.currentGraph.subject.copy(
                    maskRef = result.maskRef,
                    bgMode = mode,
                    bgColorArgb = color
                )
            ),
            result.composite
        )
    }

    private fun commitOnMain(graph: EditGraph, composite: SubjectComposite?) {
        previewSession.applySubjectComposite(composite)
        commit(graph, composite)
    }

    private fun commit(graph: EditGraph, composite: SubjectComposite?) {
        if (composite != null) composites.store(graph.subject, composite) else composites.reset()
        val applied = undoController.onParameterDrag(graph, EditParameterKey.AI_SUBJECT)
        undoController.onParameterRelease()
        onGraphCommitted(applied, EditParameterKey.AI_SUBJECT)
    }

    private fun launchWork(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private fun showUnavailable() {
        Toast.makeText(
            context,
            R.string.editor_subject_model_unavailable,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

}
