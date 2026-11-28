package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.PresetSubjectGate
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Runs the asynchronous half of `AI -> 主体`: model installation, MODNet inference and the commit of
 * the resulting `SubjectOps`.
 *
 * [EditorSubjectController] keeps the synchronous policy (mode chips, ORIGINAL resets, dial release);
 * this workflow owns the pending-dial state, the install/download prompts and every coroutine that
 * can outlive one tap. Cancel always leaves the graph untouched.
 */
internal class SubjectMaskWorkflow(
    private val context: Context,
    private val installer: SubjectModelInstaller,
    private val inference: SubjectInferenceRunner,
    private val composites: SubjectCompositeRefresher,
    private val undoController: EditorUndoController,
    private val committer: SubjectGraphCommitter,
    private val scope: CoroutineScope
) {

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
    }

    /** Releases the tool-owned jobs; the shared ORT manager stays alive. */
    fun release() {
        cancel()
        composites.reset()
    }

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
        if (EditorSettings(context).resolveMemoryTier(context) != DeviceMemoryTier.STANDARD) {
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
     * it commits; an existing mask is an immediate, coalesced graph write.
     */
    fun requestAdjustment(subject: SubjectOps) {
        val graph = undoController.currentGraph
        val target = committer.applyGrading(target = graph.subject, grading = subject)
        pendingGrading = target
        if (graph.subject.maskRef.isNotBlank()) {
            if (awaitingMask) return
            launchWork {
                val current = undoController.currentGraph
                val composite = composites.load(current)
                if (composite == null) {
                    recoverMissingMask(current)
                    return@launchWork
                }
                committer.stage(composite)
                pendingGrading = null
                commitGrading(current.copy(subject = committer.applyGrading(current.subject, subject)))
            }
            return
        }
        if (job?.isActive == true || awaitingMask) return
        awaitingMask = true
        if (!installer.isInstalled()) {
            showInstallPrompt(graph, target)
            return
        }
        launchWork {
            generateWithModel(installer.modelFile(), graph, target.bgMode, target.bgColorArgb)
        }
    }

    /** Rebuilds the cut-out after reopening the editor, or falls back to `ORIGINAL`. */
    fun restore(graph: EditGraph) {
        if (!graph.subject.requiresMaskProcessing()) return
        if (graph.subject.maskRef.isBlank()) {
            if (graph.subject.bgMode != SubjectBackgroundMode.ORIGINAL) {
                awaitingMask = false
                committer.commit(
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
                installer.showUnavailable()
                promptModelInstall()
            }
        )
    }

    /** Loads the existing mask of [graph] and applies [mode] / [color]. */
    fun applyExistingMask(
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

    /** Starts a mask inference for [graph] after ensuring the model is available. */
    fun generateMask(graph: EditGraph, mode: SubjectBackgroundMode, color: Int) {
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

    private fun recoverMissingMask(current: EditGraph) {
        if (!installer.isInstalled()) {
            awaitingMask = true
            showInstallPrompt(current, current.subject)
            return
        }
        awaitingMask = true
        launchWork {
            generateWithModel(
                installer.modelFile(),
                current,
                current.subject.bgMode,
                current.subject.bgColorArgb
            )
        }
    }

    private fun showInstallPrompt(
        graph: EditGraph,
        grading: SubjectOps,
        mode: SubjectBackgroundMode = grading.bgMode,
        color: Int = grading.bgColorArgb
    ) {
        installer.showPrompt(
            onDownload = { downloadModel(graph, mode, color) },
            onDismiss = {
                if (!installer.isInstalled() && !installer.isBusy()) awaitingMask = false
            }
        )
    }

    private fun downloadModel(graph: EditGraph, mode: SubjectBackgroundMode, color: Int) {
        installer.download(
            onReady = { model -> generateWithModel(model, graph, mode, color) },
            onFailed = {
                installer.showUnavailable()
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
        val graded = pendingGrading?.let { committer.applyGrading(target = base, grading = it) } ?: base
        pendingGrading = null
        awaitingMask = false
        commitOnMain(current.copy(subject = graded), result.composite)
    }

    /** Commits one coalesced dial tick; the undo snapshot merges inside the 300 ms window. */
    private fun commitGrading(graph: EditGraph) {
        committer.commitGrading(graph)
    }

    private fun commitOnMain(graph: EditGraph, composite: SubjectComposite?) {
        awaitingMask = false
        committer.commit(graph, composite)
    }

    private fun launchWork(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

}
