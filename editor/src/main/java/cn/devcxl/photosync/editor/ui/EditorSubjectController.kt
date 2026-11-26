package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.widget.Toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.PresetSubjectGate
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.ModnetSubjectSegmenter
import cn.devcxl.photosync.editor.vision.OrtSessionManager
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

    /** Dial values waiting for the first mask inference, applied once the mask is committed. */
    private var pendingGrading: SubjectOps? = null

    /** True while a mask prompt/download/inference is already covering the pending dial values. */
    private var awaitingMask = false

    /** Stops the in-flight download/inference; the graph is left as it was. */
    fun cancel() {
        job?.cancel()
        job = null
        installer.cancel()
        pendingMode = null
        pendingGrading = null
        awaitingMask = false
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
        val updated = graph.copy(subject = graph.subject.copy(bgMode = mode))
        when {
            mode == SubjectBackgroundMode.ORIGINAL && !updated.subject.requiresMaskProcessing() -> {
                previewSession.applySubjectComposite(null)
                commit(updated, null)
            }
            mode == SubjectBackgroundMode.ORIGINAL -> {
                // Blur or a foreground/background grade still needs the mask; keep the staged
                // composite and only change the background mode.
                commitDiscrete(updated)
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

    /** The refined subject mask currently staged, or `null` when no subject effect is loaded. */
    fun currentMask(): SubjectMask? = composites.currentMask()

    /**
     * Computes this photo's own `SubjectMask` after a preset carried subject parameters.
     *
     * Presets copy `SubjectOps` values but never the source photo's mask, so applying one on a photo
     * without a mask must run the standard-tier segmentation once. It is deliberately silent: a
     * missing model file or a low-RAM device skips the inference (the preset still applies its
     * ordinary grading) instead of interrupting the user with a download sheet.
     */
    fun ensureMaskForPreset() {
        val graph = undoController.currentGraph
        if (!PresetSubjectGate.needsMask(graph)) return
        if (job?.isActive == true || awaitingMask) return
        if (DeviceMemoryTier.detect(context) != DeviceMemoryTier.STANDARD) {
            Timber.i("Subject preset mask skipped: the device is in the low-memory tier")
            return
        }
        if (!installer.isInstalled()) {
            Timber.i("Subject preset mask skipped: the MODNet model is not installed")
            return
        }
        awaitingMask = true
        launchWork {
            generateWithModel(
                installer.modelFile(),
                undoController.currentGraph,
                graph.subject.bgMode,
                graph.subject.bgColorArgb
            )
        }
    }

    /**
     * Handles one dial value of [subject] (blur or a foreground/background offset).
     *
     * A mask-less photo first runs the shared MODNet inference and applies the pending values when
     * it commits; an existing mask is an immediate, coalesced graph write. This is the "first
     * adjustment triggers inference" contract of the panel.
     */
    fun requestAdjustment(subject: SubjectOps) {
        val graph = undoController.currentGraph
        val target = applyGrading(target = graph.subject, grading = subject)
        pendingGrading = target
        if (graph.subject.maskRef.isNotBlank()) {
            if (awaitingMask) return
            launchWork {
                val current = undoController.currentGraph
                val composite = composites.load(current)
                if (composite == null) {
                    if (!installer.isInstalled()) {
                        awaitingMask = true
                        installer.showPrompt(
                            onDownload = {
                                downloadModel(current, current.subject.bgMode, current.subject.bgColorArgb)
                            },
                            onDismiss = {
                                if (!installer.isInstalled() && !installer.isBusy()) {
                                    awaitingMask = false
                                }
                            }
                        )
                    } else {
                        awaitingMask = true
                        generateWithModel(
                            installer.modelFile(),
                            current,
                            current.subject.bgMode,
                            current.subject.bgColorArgb
                        )
                    }
                    return@launchWork
                }
                previewSession.applySubjectComposite(composite)
                pendingGrading = null
                commitGrading(current.copy(subject = applyGrading(current.subject, subject)))
            }
            return
        }
        if (job?.isActive == true || awaitingMask) return
        awaitingMask = true
        if (!installer.isInstalled()) {
            installer.showPrompt(
                onDownload = { downloadModel(graph, target.bgMode, target.bgColorArgb) },
                onDismiss = {
                    if (!installer.isInstalled() && !installer.isBusy()) awaitingMask = false
                }
            )
            return
        }
        launchWork {
            generateWithModel(installer.modelFile(), graph, target.bgMode, target.bgColorArgb)
        }
    }

    /** Seals the coalesced `AI_SUBJECT` undo step when the user lifts the dial. */
    fun endAdjustment() {
        undoController.onParameterRelease()
        onGraphCommitted(undoController.currentGraph, EditParameterKey.AI_SUBJECT)
    }

    /**
     * Restores the cut-out after reopening the editor.
     *
     * A cached mask is reused without inference; a subject mode whose mask is gone falls back to
     * `ORIGINAL` so the canvas never shows an unexplained un-edited frame.
     */
    fun restore(graph: EditGraph) {
        if (!graph.subject.requiresMaskProcessing()) return
        if (graph.subject.maskRef.isBlank()) {
            if (graph.subject.bgMode != SubjectBackgroundMode.ORIGINAL) {
                commit(
                    graph.copy(subject = graph.subject.copy(bgMode = SubjectBackgroundMode.ORIGINAL)),
                    null
                )
            }
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
        val result = inference.run(modelFile) ?: run {
            awaitingMask = false
            return
        }
        val current = undoController.currentGraph
        val base = current.subject.copy(
            maskRef = result.maskRef,
            bgMode = mode,
            bgColorArgb = color
        )
        val graded = pendingGrading?.let { applyGrading(target = base, grading = it) } ?: base
        pendingGrading = null
        awaitingMask = false
        commitOnMain(current.copy(subject = graded), result.composite)
    }

    /** Commits one coalesced dial tick; the undo snapshot merges inside the 300 ms window. */
    private fun commitGrading(graph: EditGraph) {
        val applied = undoController.onParameterDrag(graph, EditParameterKey.AI_SUBJECT)
        onGraphCommitted(applied, EditParameterKey.AI_SUBJECT)
    }

    /** Commits a discrete graph change without touching the staged composite or its cache. */
    private fun commitDiscrete(graph: EditGraph) {
        awaitingMask = false
        val applied = undoController.onParameterDrag(graph, EditParameterKey.AI_SUBJECT)
        undoController.onParameterRelease()
        onGraphCommitted(applied, EditParameterKey.AI_SUBJECT)
    }

    /** Copies only the effect axes of [grading]; mask, mode and colour stay from [target]. */
    private fun applyGrading(target: SubjectOps, grading: SubjectOps): SubjectOps = target.copy(
        blur = grading.blur,
        fgExposure = grading.fgExposure,
        fgTemp = grading.fgTemp,
        fgSat = grading.fgSat,
        bgExposure = grading.bgExposure,
        bgTemp = grading.bgTemp,
        bgSat = grading.bgSat
    )

    private fun commitOnMain(graph: EditGraph, composite: SubjectComposite?) {
        previewSession.applySubjectComposite(composite)
        commit(graph, composite)
    }

    private fun commit(graph: EditGraph, composite: SubjectComposite?) {
        if (composite != null) composites.store(graph.subject, composite) else composites.reset()
        awaitingMask = false
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
