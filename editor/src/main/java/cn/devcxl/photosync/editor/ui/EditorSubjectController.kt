package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context

import kotlinx.coroutines.CoroutineScope

import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.ModnetSubjectSegmenter
import cn.devcxl.photosync.editor.vision.OrtSessionManager
import cn.devcxl.photosync.editor.vision.OrtSessionRuntimeConfig
import cn.devcxl.photosync.editor.vision.SubjectMask

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
 * The synchronous policy (mode chips, `ORIGINAL` resets, dial release) lives here; the asynchronous
 * half — install prompts, inference and its commits — lives in [SubjectMaskWorkflow]. The inference
 * bar is non-blocking and cancellable; cancellation leaves the graph untouched.
 */
internal class EditorSubjectController(
    private val context: Context,
    filesDir: File,
    photoId: Long,
    private val undoController: EditorUndoController,
    private val previewSession: EditorPreviewSession,
    private val inferenceBar: AiInferenceBarController,
    scope: CoroutineScope,
    sourceProvider: () -> EditorSource?,
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
    private val sessions = sharedSessions ?: createFallbackSessionManager(context)
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
    private val committer = SubjectGraphCommitter(
        composites = composites,
        previewSession = previewSession,
        undoController = undoController,
        onGraphCommitted = onGraphCommitted
    )
    private val workflow = SubjectMaskWorkflow(
        context = context,
        installer = installer,
        inference = inference,
        composites = composites,
        undoController = undoController,
        committer = committer,
        scope = scope
    )

    /** Stops the in-flight download/inference; the graph is left as it was. */
    fun cancel() {
        workflow.cancel()
        if (inferenceBar.isVisible) inferenceBar.dismiss()
    }

    /** Releases the tool-owned jobs; the shared ORT manager stays alive. */
    fun release() {
        workflow.release()
        if (ownsSessions) sessions.close()
    }

    /**
     * Handles a mode chip tap.
     *
     * @param mode Requested background mode.
     */
    fun requestMode(mode: SubjectBackgroundMode) {
        val graph = undoController.currentGraph
        val updated = graph.copy(subject = graph.subject.copy(bgMode = mode))
        when {
            mode == SubjectBackgroundMode.ORIGINAL && !updated.subject.requiresMaskProcessing() -> {
                committer.commit(updated, null)
            }
            mode == SubjectBackgroundMode.ORIGINAL -> {
                // Blur or a foreground/background grade still needs the mask; keep the staged
                // composite and only change the background mode.
                committer.commitDiscrete(updated)
            }
            graph.subject.maskRef.isNotBlank() -> workflow.applyExistingMask(graph, mode)
            else -> workflow.generateMask(graph, mode, graph.subject.bgColorArgb)
        }
    }

    /** Handles a swatch tap; a swatch always selects the solid mode. */
    fun requestColor(argb: Int) {
        val graph = undoController.currentGraph
        if (graph.subject.maskRef.isNotBlank()) {
            workflow.applyExistingMask(graph, SubjectBackgroundMode.SOLID, argb)
        } else {
            workflow.generateMask(graph, SubjectBackgroundMode.SOLID, argb)
        }
    }

    /** The refined subject mask currently staged, or `null` when no subject effect is loaded. */
    fun currentMask(): SubjectMask? = composites.currentMask()

    /** Computes this photo's own mask after a preset carried subject parameters. */
    fun ensureMaskForPreset() = workflow.ensureMaskForPreset()

    /** Handles one dial value of [subject] (blur or a foreground/background offset). */
    fun requestAdjustment(subject: SubjectOps) = workflow.requestAdjustment(subject)

    /** Seals the coalesced `AI_SUBJECT` undo step when the user lifts the dial. */
    fun endAdjustment() {
        undoController.onParameterRelease()
        onGraphCommitted(undoController.currentGraph, EditParameterKey.AI_SUBJECT)
    }

    /** Restores the cut-out after reopening the editor. */
    fun restore(graph: EditGraph) = workflow.restore(graph)

    /** Persists a user-selected `.onnx` file and continues the pending mask generation. */
    fun importModel(input: InputStream) = workflow.importModel(input)

    /**
     * Re-reads the subject composite after the pixels under it changed, for example a completed
     * erase. An absent mask or mode is simply a no-op.
     */
    fun refreshSourceComposite(graph: EditGraph) {
        composites.refresh(graph)
    }

    /**
     * Builds a private ORT owner when the host did not inject the process-wide one.
     *
     * The effective tier, thread count and idle policy all come from [EditorSettings], the same
     * resolver the production `App` manager uses, so a test host cannot silently run with a different
     * AI policy than the app.
     */
    private fun createFallbackSessionManager(context: Context): OrtSessionManager {
        val settings = EditorSettings(context)
        return OrtSessionManager(
            tier = settings.resolveMemoryTier(context),
            runtimePolicy = {
                OrtSessionRuntimeConfig(
                    tier = settings.resolveMemoryTier(context),
                    intraOpThreads = settings.intraOpThreads,
                    idleTimeoutMs = settings.idleRelease.timeoutMs
                )
            }
        )
    }
}
