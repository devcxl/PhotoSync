package cn.devcxl.photosync.editor.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.cloud.CloudConsentStore
import cn.devcxl.photosync.editor.cloud.CloudEditCommand
import cn.devcxl.photosync.editor.cloud.CloudEditError
import cn.devcxl.photosync.editor.cloud.CloudEditExecutor
import cn.devcxl.photosync.editor.cloud.CloudEditInputIssue
import cn.devcxl.photosync.editor.cloud.CloudEditMode
import cn.devcxl.photosync.editor.cloud.CloudEditOutcome
import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.cloud.CloudJobStage
import cn.devcxl.photosync.editor.cloud.CloudMaskMode
import cn.devcxl.photosync.editor.cloud.CloudProviderSource
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelListener
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelState

/**
 * Owns the `AI -> 云端` workflow from the panel tap to the committed `EditGraph`.
 *
 * ## Flow
 * 1. [refresh] hides the whole panel while the P4 switch is off.
 * 2. The first send asks for the one-time privacy/cost disclosure; only the explicit accept retries
 *    the same generation.
 * 3. The Post-Pass A frame plus the selected mask source become one [CloudEditCommand]; the
 *    executor stages, sends and persists it off the UI thread.
 * 4. A returned layer is committed as **one** undo transaction and the panel switches to opacity and
 *    removal controls.
 * 5. A Pass A geometry change with a mismatching digest opens the non-destructive conflict sheet:
 *    remove the layer and apply the new framing, or abandon the geometry change.
 *
 * The panel, progress bar and dialogs are abstracted behind [EditorCloudHost] and [CloudPanelPort], so the
 * state machine is unit-testable with no Activity, no network and no real dialog.
 */
internal class EditorCloudController(
    private val scope: CoroutineScope,
    private val host: EditorCloudHost,
    private val panel: CloudPanelPort,
    private val undoController: EditorUndoController,
    private val providers: CloudProviderSource,
    private val executor: CloudEditExecutor,
    private val consent: CloudConsentStore,
    /** Dispatcher that renders the Post-Pass A upload frame; overridable in unit tests. */
    private val frameDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CloudPanelListener {

    init {
        panel.bindListener(this)
    }

    private var mode = CloudEditMode.WHOLE_IMAGE
    private var prompt = ""
    private var featherPx = CloudEditCommand.DEFAULT_FEATHER_PX.toInt()
    private var job: Job? = null
    private val geometryGuard = CloudGeometryGuard(
        undoController = undoController,
        host = { host },
        commit = ::commit
    )
    private val commandFactory = CloudEditCommandFactory(
        host = host,
        graphProvider = { undoController.currentGraph },
        providers = providers
    )

    /** Installs the non-destructive Pass A geometry guard on [controller]. */
    fun installGeometryGuard(controller: EditorUndoController) = geometryGuard.install(controller)

    /**
     * Repaints visibility and state; call after the editor loaded or the cloud switch changed.
     *
     * @param enabled Known switch value, so a caller that already read the persisted flag off the
     *   main thread does not read it again here.
     */
    fun refresh(enabled: Boolean = providers.isCloudEnabled()) {
        if (!enabled) {
            cancel()
            panel.setCloudPanelVisible(false)
            return
        }
        panel.setCloudPanelVisible(true)
        panel.renderCloudPanel(currentState())
    }

    /** True when the P4 switch is on, so the drawer may offer the cloud sub-tool. */
    fun isEnabled(): Boolean = providers.isCloudEnabled()

    /** Cancels a running generation; a committed layer is never touched. */
    fun cancel() {
        if (job?.isActive == true) {
            job?.cancel()
            host.hideProgress()
        }
        job = null
    }

    /** Cancels jobs owned by this controller. */
    fun release() = cancel()

    override fun onCloudModeSelected(mode: CloudEditMode) {
        if (!providers.isCloudEnabled()) {
            refresh()
            return
        }
        if (mode != CloudEditMode.WHOLE_IMAGE && commandFactory.maskMode() == CloudMaskMode.NONE) {
            host.showToast(R.string.editor_cloud_mask_unsupported)
            return
        }
        this.mode = mode
        refresh()
    }

    override fun onCloudPromptChanged(text: String) {
        prompt = text
    }

    override fun onCloudFeatherChanged(featherPx: Int) {
        this.featherPx = featherPx.coerceIn(0, MAX_FEATHER_PX)
        refresh()
    }

    override fun onCloudGenerateClicked() {
        if (!providers.isCloudEnabled()) {
            refresh()
            return
        }
        if (prompt.isBlank()) {
            host.showToast(R.string.editor_cloud_prompt_required)
            return
        }
        if (!consent.isAccepted()) {
            host.showConsentDialog(::startGeneration)
            return
        }
        startGeneration()
    }

    override fun onCloudOpacityChanged(opacity: Int) {
        val layer = undoController.currentGraph.cloudLayer ?: return
        host.onCloudLayerPreviewChanged(layer.copy(opacity = opacity.coerceIn(0, 100)))
    }

    override fun onCloudOpacityCommitted(opacity: Int) {
        val graph = undoController.currentGraph
        val layer = graph.cloudLayer ?: return
        val updated = opacity.coerceIn(0, 100)
        if (layer.opacity == updated) return
        commit(graph.copy(cloudLayer = layer.copy(opacity = updated)), EditParameterKey.AI_CLOUD)
    }

    override fun onCloudRemoveLayerClicked() {
        val graph = undoController.currentGraph
        if (graph.cloudLayer == null) return
        commit(graph.copy(cloudLayer = null), EditParameterKey.AI_CLOUD)
        host.showToast(R.string.editor_cloud_layer_removed)
    }

    private fun startGeneration() {
        job?.cancel()
        job = scope.launch {
            host.showProgress(R.string.editor_cloud_progress_preparing, 0)
            // The Post-Pass A frame is decoded and rasterized here, off the UI thread; building the
            // command must never block the drawer.
            val command = withContext(frameDispatcher) {
                commandFactory.build(mode, prompt, featherPx.toFloat())
            }
            if (command == null) {
                host.hideProgress()
                return@launch
            }
            val outcome = try {
                executor.execute(command) { stage ->
                    // A provider may report from its own worker thread; the launch keeps the update
                    // on the main thread and cancels it together with this job.
                    launch(Dispatchers.Main) { host.updateProgress(labelFor(stage), percentFor(stage)) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Timber.e(error, "Cloud edit failed")
                CloudEditOutcome.Failed(CloudEditError.Unknown(error))
            } finally {
                host.hideProgress()
            }
            handleOutcome(outcome)
        }
    }

    private fun handleOutcome(outcome: CloudEditOutcome) {
        when (outcome) {
            is CloudEditOutcome.Applied -> {
                val graph = undoController.currentGraph
                if (!CloudGeometryDigest.matches(outcome.layer, graph.geometry)) {
                    // The user changed framing while the request was in flight; the result no longer
                    // belongs to the current canvas and must not be committed as if it did.
                    Timber.w("Discarding a cloud result produced for a different geometry")
                    host.showToast(R.string.editor_cloud_failed)
                    return
                }
                commit(graph.copy(cloudLayer = outcome.layer), EditParameterKey.AI_CLOUD)
                host.showToast(R.string.editor_cloud_applied)
            }
            is CloudEditOutcome.Failed -> host.showToast(
                if (outcome.error is CloudEditError.Cancelled) {
                    R.string.editor_cloud_cancelled
                } else {
                    R.string.editor_cloud_failed
                }
            )
            CloudEditOutcome.ConsentRequired -> host.showConsentDialog(::startGeneration)
            CloudEditOutcome.ProviderUnavailable -> host.showToast(R.string.editor_cloud_failed)
            is CloudEditOutcome.InvalidInput -> host.showToast(labelFor(outcome.issue))
        }
    }

    private fun commit(graph: EditGraph, parameterKey: EditParameterKey) {
        val applied = undoController.onParameterDrag(graph, parameterKey)
        undoController.onParameterRelease()
        host.onGraphCommitted(applied)
        refresh()
    }

    private fun currentState(): CloudPanelState {
        val layer = undoController.currentGraph.cloudLayer
        return CloudPanelState(
            mode = mode,
            prompt = prompt,
            hasLayer = layer != null,
            opacity = layer?.opacity ?: 100,
            featherPx = featherPx,
            supportsMask = mode != CloudEditMode.WHOLE_IMAGE &&
                commandFactory.maskMode() != CloudMaskMode.NONE,
            softMaskWarning = mode != CloudEditMode.WHOLE_IMAGE &&
                commandFactory.maskMode() == CloudMaskMode.SOFT
        )
    }

    private fun labelFor(stage: CloudJobStage): Int = when (stage) {
        CloudJobStage.SUBMITTING -> R.string.editor_cloud_progress_submitting
        CloudJobStage.QUEUED, CloudJobStage.RUNNING -> R.string.editor_cloud_progress_generating
        CloudJobStage.DOWNLOADING -> R.string.editor_cloud_progress_downloading
    }

    private fun percentFor(stage: CloudJobStage): Int = when (stage) {
        CloudJobStage.SUBMITTING -> 15
        CloudJobStage.QUEUED -> 30
        CloudJobStage.RUNNING -> 55
        CloudJobStage.DOWNLOADING -> 85
    }

    private fun labelFor(issue: CloudEditInputIssue): Int = when (issue) {
        CloudEditInputIssue.MISSING_SOURCE -> R.string.editor_cloud_source_unavailable
        CloudEditInputIssue.MISSING_PAINTED_MASK -> R.string.editor_cloud_painted_mask_missing
        CloudEditInputIssue.MISSING_SUBJECT_MASK -> R.string.editor_cloud_subject_mask_missing
        CloudEditInputIssue.BLANK_PROMPT -> R.string.editor_cloud_prompt_required
        CloudEditInputIssue.MASK_NOT_SUPPORTED -> R.string.editor_cloud_mask_unsupported
    }

    private companion object {
        /** UI bound of the local blend-mask feather, in upload pixels. */
        const val MAX_FEATHER_PX = 32
    }
}
