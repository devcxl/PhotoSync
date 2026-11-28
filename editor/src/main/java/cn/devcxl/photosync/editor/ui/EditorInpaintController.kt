package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.widget.Toast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.InpaintBatch
import cn.devcxl.photosync.editor.domain.InpaintBatchPlanner
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer
import cn.devcxl.photosync.editor.domain.InpaintTilePlan
import cn.devcxl.photosync.editor.domain.InpaintWorkRegion
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.render.InpaintSourceReader
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.ui.panels.InpaintBatchCommit
import cn.devcxl.photosync.editor.ui.panels.InpaintBrushController
import cn.devcxl.photosync.editor.ui.panels.InpaintProgressiveOutcome
import cn.devcxl.photosync.editor.ui.panels.InpaintProgressiveRunner
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadCancellation
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy
import cn.devcxl.photosync.editor.vision.AiModelInstallException
import cn.devcxl.photosync.editor.vision.AiModelInstallUi
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.InpaintVariant
import cn.devcxl.photosync.editor.vision.InpaintVariantDetector
import cn.devcxl.photosync.editor.vision.MiGanFailureReason
import cn.devcxl.photosync.editor.vision.MiGanInpaintException
import cn.devcxl.photosync.editor.vision.MiGanInpainter
import cn.devcxl.photosync.editor.vision.MiGanModelSelection
import cn.devcxl.photosync.editor.vision.MiGanTileOutcome
import cn.devcxl.photosync.editor.vision.OrtSessionManager

/**
 * Owns the `AI -> 消除` workflow from the brush confirmation to the committed `EditGraph`.
 *
 * ## Flow
 * 1. The brush controller collects the visible stroke capsules and asks [executor] to repair them.
 * 2. The strokes are projected through Pass A into pre-crop normalized space, the painted bbox is
 *    expanded by `InpaintWorkRegion.CONTEXT_MARGIN_PX` (512 px, enough for the planner's
 *    single-tile square), and only that window of the **original** file is read at 1:1 — never
 *    the 2048 px preview upscaled.
 * 3. Without the MI-GAN artifact the run fails with [MiGanFailureReason.MODEL_MISSING]; this
 *    controller then shows the shared download/SAF-import sheet and re-runs the same strokes once the
 *    model verified, so nothing the user painted is lost.
 * 4. The plan is cut into the device's `batchTiles` budget. Each batch appends its ordered patches
 *    as **one** undo transaction ([commitBatch]); the measured per-tile time is written back to
 *    [EditorSettings] so the next workload estimate uses the local baseline. Cancelling or failing
 *    a later batch keeps every committed batch, and the brush is told which cores remain so only
 *    the missing coverage has to be repainted (spec §7.3/§7.4).
 *
 * The inference bar is non-blocking and cancellable; every recoverable failure is a localized
 * toast, never a crash.
 */
internal class EditorInpaintController(
    private val context: Context,
    private val filesDir: File,
    private val photoId: Long,
    private val sourcePath: String,
    private val isRaw: Boolean,
    private val undoController: EditorUndoController,
    private val inferenceBar: AiInferenceBarController,
    private val scope: CoroutineScope,
    private val sourceProvider: () -> EditorSource?,
    private val warpFieldProvider: () -> WarpDisplacementField?,
    private val onCanvasTransform: (CanvasTransform) -> Unit,
    private val onGraphCommitted: (EditGraph, EditParameterKey) -> Unit,
    private val onPatchesApplied: () -> Unit,
    private val onImportRequested: () -> Unit,
    private val onConfirmRequested: () -> Unit,
    private val sourceReader: InpaintSourceReader = InpaintSourceReader()
) : InpaintBrushController.Host {

    private val assets = AiAssetStore(filesDir)
    private val settings = EditorSettings(context)
    private val downloads = AiModelDownloadManager(
        modelsRootDir = File(filesDir, AiModelCatalog.MODELS_DIR_NAME),
        variantCompatibility = InpaintVariantDetector().compatibility()
    )

    /**
     * The process-wide ORT owner, or a private fallback for hosts that did not inject one.
     *
     * A shared manager is never closed here: its own tiered idle release, memory-pressure hook and
     * single-flight model switch are what keep one native session alive across both AI tools.
     */
    private val sharedSessions = EditorHostRegistry.ortSessionManager
    private val sessions = sharedSessions ?: OrtSessionManager(
        tier = settings.resolveMemoryTier(context)
    )
    private val ownsSessions = sharedSessions == null

    private var installJob: Job? = null
    private var installCancellation: AiModelDownloadCancellation? = null

    /** The initial values `EditorInpaintToolHost` pushes into the brush panel. */
    val initialBrushDiameterPx: Int
        get() = settings.inpaintBrushPx

    /** Default quality mode read when the erase tool opens. */
    val initialQualityMode: InpaintQualityMode
        get() = settings.inpaintDefaultMode

    /** Effective memory tier after the user override; every run and the panel estimate share it. */
    fun memoryTier(): DeviceMemoryTier = settings.resolveMemoryTier(context)

    /**
     * Previous single-tile measurement of whichever MI-GAN variant may run, or `null`.
     *
     * The actual variant is only known after `resolveModel()`; the estimate therefore prefers the
     * pipeline pin and falls back to the legacy generator, matching the resolver's candidate order.
     */
    fun measuredTileMs(): Long? {
        val tier = memoryTier()
        return settings.getMeasuredInpaintTileMs(tier, InpaintVariant.PIPELINE_UINT8.name)
            ?: settings.getMeasuredInpaintTileMs(tier, InpaintVariant.GENERATOR_FIXED_512.name)
    }

    /** The executor the brush controller drives; adapts [MiGanInpainter]. */
    fun executor(): InpaintBrushController.InpaintExecutor =
        InpaintBrushController.InpaintExecutor { strokes, mode, onProgress, onRemaining ->
            runInpaint(strokes, mode, onProgress, onRemaining)
        }

    /**
     * Returns the upright full-resolution source size, or `null` when the file cannot be read.
     *
     * The editor probes this once the erase tool is opened so the brush diameter and the workload
     * estimate are evaluated on real source pixels instead of the 2048 px preview.
     */
    suspend fun probeSourceSize(): Pair<Int, Int>? =
        sourceReader.probeDimensions(sourcePath, isRaw)

    /** Stops a model download/import; an in-flight inference is owned by the brush controller. */
    fun cancel() {
        val cancellation = installCancellation
        if (cancellation != null) cancellation.cancel() else installJob?.cancel()
    }

    /**
     * Releases what this controller owns.
     *
     * The shared process manager stays alive; only a private test fallback is closed here.
     */
    fun release() {
        cancel()
        if (ownsSessions) sessions.close()
    }

    /** Persists a user-selected `.onnx` file and continues the pending removal. */
    fun importModel(input: InputStream) {
        launchWork {
            inferenceBar.show(context.getString(R.string.editor_inpaint_model_importing))
            val result = downloads.importLocalAny(
                candidates = AiModelCatalog.importCandidates(AiModelCatalog.MI_GAN_PIPELINE),
                input = input
            )
            inferenceBar.dismiss()
            result.fold(
                onSuccess = { confirmPendingRun() },
                onFailure = { error ->
                    Timber.e(error, "The local MI-GAN model was rejected")
                    showInstallFailure(error)
                    promptModelInstall()
                }
            )
        }
    }

    override fun onInpaintCanvasTransformChanged(transform: CanvasTransform) =
        onCanvasTransform(transform)

    override fun onInpaintInferenceStarted() {
        inferenceBar.show(context.getString(R.string.editor_inpaint_inference_running))
    }

    override fun onInpaintInferenceProgress(percent: Int) {
        // Reported from the inference dispatcher: the bar belongs to the main thread.
        scope.launch { inferenceBar.updateProgress(percent) }
    }

    override fun onInpaintInferenceFinished() {
        inferenceBar.dismiss()
    }

    override fun onInpaintFailed(cause: Throwable) {
        when (cause) {
            is InpaintUserMessageException -> showToast(
                cause.messageRes,
                *cause.messageArgs.toTypedArray()
            )
            is MiGanInpaintException -> when (cause.reason) {
                MiGanFailureReason.MODEL_MISSING -> promptModelInstall()
                else -> showToast(R.string.editor_inpaint_failed)
            }
            else -> {
                Timber.e(cause, "Inpainting failed")
                showToast(R.string.editor_inpaint_failed)
            }
        }
    }

    override fun onInpaintNoStrokes() {
        showToast(R.string.editor_inpaint_no_strokes)
    }

    override fun onInpaintStrokesCleared() = Unit

    private suspend fun runInpaint(
        strokes: List<InpaintStroke>,
        mode: InpaintQualityMode,
        onProgress: (Int) -> Unit,
        onRemainingCoverageChanged: (List<InpaintPixelRect>) -> Unit
    ): Result<Unit> {
        if (sourceProvider() == null) return failure("The preview source is not ready")
        val dimensions = probeSourceSize() ?: return failure("The source dimensions are unavailable")
        val graph = undoController.currentGraph
        val projection = InpaintStrokeProjection.of(
            geometry = graph.geometry,
            srcAspect = dimensions.first.toFloat() / dimensions.second.toFloat().coerceAtLeast(1.0f),
            warpField = warpFieldProvider()
        )
        val projected = projection.projectStrokes(strokes)
        val bounds = InpaintWorkRegion.boundsOf(projected, dimensions.first, dimensions.second)
            ?: return failure("The painted strokes are empty")
        val region = InpaintWorkRegion.around(bounds, dimensions.first, dimensions.second)
            ?: return failure("The painted area could not be framed")
        currentCoroutineContext().ensureActive()
        val pixels = sourceReader.readRegion(
            sourcePath = sourcePath,
            isRaw = isRaw,
            region = region,
            patches = graph.inpaint,
            filesDir = filesDir
        ) ?: return failure("The original pixels could not be read")
        val mask = try {
            withContext(Dispatchers.Default) {
                rasterize(region.localize(projected), region)
            }
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while rasterizing the inpaint mask")
            return failure("The painted area is too large for this device")
        }
        currentCoroutineContext().ensureActive()
        val tier = memoryTier()
        val inpainter = inpainterFor(tier, mode)
        val plan = withContext(Dispatchers.Default) { inpainter.plan(mask) }
        if (plan.isEmpty) return failure(MiGanFailureReason.NO_STROKES, "nothing painted")
        val selection = inpainter.resolveModel().getOrElse { return Result.failure(it) }
        val batches = InpaintBatchPlanner.plan(plan)
        val variantKey = selection.variant.name
        val runner = InpaintProgressiveRunner(
            batches = batches,
            executeBatch = { tiles, onTileProgress ->
                executeTiles(
                    inpainter = inpainter,
                    selection = selection,
                    pixels = pixels,
                    region = region,
                    mask = mask,
                    tiles = tiles,
                    onTileProgress = onTileProgress
                )
            },
            onBatchCommitted = { commit ->
                commitBatch(commit, region)
            },
            onTileMeasured = { averageMs ->
                settings.setMeasuredInpaintTileMs(tier, variantKey, averageMs)
            },
            onMemoryPressure = sessions::onMemoryPressure,
            onBatchStarted = { batch, total ->
                inferenceBar.updateLabel(
                    context.getString(R.string.editor_inpaint_batch_progress, batch.index + 1, total)
                )
            },
            onProgress = { percent -> onProgress(percent.coerceIn(0, 100)) }
        )
        return try {
            when (val outcome = runner.run()) {
                is InpaintProgressiveOutcome.Completed -> Result.success(Unit)
                is InpaintProgressiveOutcome.Failed -> {
                    val remaining = batches.batches.drop(outcome.failedBatch - 1)
                    onRemainingCoverageChanged(sourceCores(remaining, region))
                    Result.failure(partialFailure(outcome))
                }
            }
        } catch (cancelled: CancellationException) {
            val committed = runner.committedBatches.lastOrNull()?.batchIndex ?: -1
            onRemainingCoverageChanged(sourceCores(batches.batches.drop(committed + 1), region))
            throw cancelled
        }
    }

    private suspend fun executeTiles(
        inpainter: MiGanInpainter,
        selection: MiGanModelSelection,
        pixels: IntArray,
        region: InpaintWorkRegion,
        mask: InpaintMaskBuffer,
        tiles: List<InpaintTilePlan>,
        onTileProgress: (Int) -> Unit
    ): Result<List<MiGanTileOutcome>> {
        var completed = 0
        return inpainter.inpaintTiles(
            photoId = photoId,
            sourceArgb = pixels,
            width = region.width,
            height = region.height,
            mask = mask,
            tiles = tiles,
            selection = selection,
            onTile = {
                completed++
                onTileProgress(completed)
            }
        ).map { outcome -> outcome.tiles }
    }

    private fun commitBatch(commit: InpaintBatchCommit, region: InpaintWorkRegion) {
        if (commit.patches.isEmpty()) return
        val patches = commit.patches.map { patch ->
            patch.copy(bbox = region.toSourceBBox(patch.bbox))
        }
        commitPatches(patches)
    }

    private fun sourceCores(
        batches: List<InpaintBatch>,
        region: InpaintWorkRegion
    ): List<InpaintPixelRect> = batches.flatMap { batch ->
        batch.tiles.map { tile ->
            InpaintPixelRect(
                left = region.originX + tile.coreRect.left,
                top = region.originY + tile.coreRect.top,
                right = region.originX + tile.coreRect.right,
                bottom = region.originY + tile.coreRect.bottom
            )
        }
    }

    private fun partialFailure(outcome: InpaintProgressiveOutcome.Failed): Throwable {
        val cause = outcome.cause
        if (cause is MiGanInpaintException && cause.reason == MiGanFailureReason.MODEL_MISSING) {
            return cause
        }
        val reason = context.getString(reasonCopy(cause, outcome.outOfMemory))
        val messageRes = if (outcome.outOfMemory) {
            R.string.editor_inpaint_batch_failed
        } else {
            R.string.editor_inpaint_batch_failed_retry
        }
        val args = listOf<Any>(outcome.failedBatch, outcome.totalBatches, reason)
        return InpaintUserMessageException(messageRes, args, cause)
    }

    private fun reasonCopy(cause: Throwable, outOfMemory: Boolean): Int {
        if (outOfMemory) return R.string.editor_inpaint_reason_out_of_memory
        return when ((cause as? MiGanInpaintException)?.reason) {
            MiGanFailureReason.ENCODING_FAILED -> R.string.editor_inpaint_reason_encode
            MiGanFailureReason.INCOMPATIBLE -> R.string.editor_inpaint_reason_incompatible
            MiGanFailureReason.RUNTIME_ERROR -> R.string.editor_inpaint_reason_runtime
            else -> R.string.editor_inpaint_reason_unknown
        }
    }

    private fun inpainterFor(tier: DeviceMemoryTier, mode: InpaintQualityMode): MiGanInpainter =
        MiGanInpainter(
            sessionManager = sessions,
            downloadManager = downloads,
            assetStore = assets,
            memoryTier = tier,
            qualityMode = mode
        )

    private fun rasterize(strokes: List<InpaintStroke>, region: InpaintWorkRegion): InpaintMaskBuffer {
        val layer = InpaintStrokeLayer()
        strokes.forEach(layer::add)
        return layer.rasterize(region.width, region.height)
    }

    private fun commitPatches(patches: List<InpaintPatchRef>) {
        val graph = undoController.currentGraph
        // One undo step per batch, even if two batches land inside the coalescing window.
        undoController.onParameterRelease()
        val applied = undoController.onParameterDrag(
            graph.copy(inpaint = graph.inpaint + patches),
            EditParameterKey.AI_INPAINT
        )
        undoController.onParameterRelease()
        onGraphCommitted(applied, EditParameterKey.AI_INPAINT)
        onPatchesApplied()
    }

    private fun promptModelInstall() {
        inferenceBar.dismiss()
        val spec = AiModelCatalog.MI_GAN_PIPELINE
        val state = AiModelInstallUi.idle(
            spec = spec,
            installed = downloads.isInstalled(spec),
            partialBytes = downloads.partFile(spec).takeIf { it.isFile }?.length() ?: 0L,
            lastProbe = downloads.lastProbeResult(spec)
        )
        AiModelPromptDialog.show(
            context = context,
            spec = spec,
            onDownload = ::downloadModel,
            onImport = onImportRequested,
            state = state
        )
    }

    private fun downloadModel() {
        launchWork {
            val spec = AiModelCatalog.MI_GAN_PIPELINE
            val token = AiModelDownloadCancellation()
            installCancellation = token
            inferenceBar.show(context.getString(R.string.editor_inpaint_model_downloading))
            val result = downloads.download(spec, onProgress = { progress ->
                val percent = (progress * PROGRESS_SCALE).toInt().coerceIn(0, 100)
                scope.launch { inferenceBar.updateProgress(percent) }
            }, cancellation = token)
            installCancellation = null
            inferenceBar.dismiss()
            result.fold(
                onSuccess = { confirmPendingRun() },
                onFailure = { error ->
                    Timber.e(error, "The MI-GAN model download failed")
                    showInstallFailure(error)
                    promptModelInstall()
                }
            )
        }
    }

    /** Starts the guarded run the brush controller keeps its strokes for. */
    private fun confirmPendingRun() {
        onConfirmRequested()
    }

    private fun launchWork(block: suspend CoroutineScope.() -> Unit) {
        installJob?.cancel()
        installJob = scope.launch { block() }
    }

    private fun failure(message: String): Result<Unit> =
        Result.failure(MiGanInpaintException(MiGanFailureReason.INVALID_INPUT, message))

    private fun failure(reason: MiGanFailureReason, message: String): Result<Unit> =
        Result.failure(MiGanInpaintException(reason, message))

    private fun showInstallFailure(error: Throwable) {
        val reason = (error as? AiModelInstallException)?.reason
        showToast(AiModelFailureUiCopy.of(reason).messageRes)
    }

    private fun showToast(messageRes: Int, vararg args: Any) {
        Toast.makeText(context, context.getString(messageRes, *args), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PROGRESS_SCALE = 100.0f
    }
}
