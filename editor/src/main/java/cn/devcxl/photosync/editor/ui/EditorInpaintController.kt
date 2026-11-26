package cn.devcxl.photosync.editor.ui

import java.io.File
import java.io.InputStream

import android.content.Context
import android.widget.Toast

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
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer
import cn.devcxl.photosync.editor.domain.InpaintWorkRegion
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.render.InpaintSourceReader
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.ui.panels.InpaintBrushController
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.MiGanFailureReason
import cn.devcxl.photosync.editor.vision.MiGanInpaintException
import cn.devcxl.photosync.editor.vision.MiGanInpainter
import cn.devcxl.photosync.editor.vision.OrtSessionManager

/**
 * Owns the `AI -> 消除` workflow from the brush confirmation to the committed `EditGraph`.
 *
 * ## Flow
 * 1. The brush controller collects the visible stroke capsules and asks [executor] to repair them.
 * 2. The strokes are projected through Pass A into pre-crop normalized space, the painted bbox is
 *    expanded by the planner's 256 px context margin, and only that window of the **original**
 *    file is read at 1:1 — never the 2048 px preview upscaled.
 * 3. Without the MI-GAN artifact the run fails with [MiGanFailureReason.MODEL_MISSING]; this
 *    controller then shows the shared download/SAF-import sheet and re-runs the same strokes once the
 *    model verified, so nothing the user painted is lost.
 * 4. A successful run appends its ordered patches as **one** undo transaction, which the preview,
 *    the cache and the full-resolution bake all pick up through `graph.inpaint`.
 *
 * The inference bar is non-blocking and cancellable; a cancelled run keeps the strokes for a retry.
 * Every recoverable failure is a localized toast, never a crash.
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
    private val downloads = AiModelDownloadManager(File(filesDir, AiModelCatalog.MODELS_DIR_NAME))

    /**
     * The process-wide ORT owner, or a private fallback for hosts that did not inject one.
     *
     * A shared manager is never closed here: its own tiered idle release, memory-pressure hook and
     * single-flight model switch are what keep one native session alive across both AI tools.
     */
    private val sharedSessions = EditorHostRegistry.ortSessionManager
    private val sessions = sharedSessions ?: OrtSessionManager(
        tier = DeviceMemoryTier.detect(context)
    )
    private val ownsSessions = sharedSessions == null
    private val inpainter = MiGanInpainter(sessions, downloads, assets)

    private var installJob: Job? = null

    /** The executor the brush controller drives; adapts [MiGanInpainter]. */
    fun executor(): InpaintBrushController.InpaintExecutor =
        InpaintBrushController.InpaintExecutor { strokes, onProgress ->
            runInpaint(strokes, onProgress)
        }

    /**
     * Returns the upright full-resolution source size, or `null` when the file cannot be read.
     *
     * The editor probes this once the erase tool is opened so the amber 256 px advice is evaluated
     * on real source pixels instead of the 2048 px preview.
     */
    suspend fun probeSourceSize(): Pair<Int, Int>? =
        sourceReader.probeDimensions(sourcePath, isRaw)

    /** Stops a model download/import; an in-flight inference is owned by the brush controller. */
    fun cancel() {
        installJob?.cancel()
        installJob = null
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
            val result = downloads.importLocal(AiModelCatalog.MI_GAN_GENERATOR, input)
            inferenceBar.dismiss()
            result.fold(
                onSuccess = { confirmPendingRun() },
                onFailure = { error ->
                    Timber.e(error, "The local MI-GAN model was rejected")
                    promptModelInstall()
                }
            )
        }
    }

    override fun onInpaintCanvasTransformChanged(transform: CanvasTransform) =
        onCanvasTransform(transform)

    override fun onInpaintBboxEstimated(widthPx: Int, heightPx: Int, isOversized: Boolean) = Unit

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

    override fun onInpaintPatchesReady(patches: List<InpaintPatchRef>) {
        if (patches.isEmpty()) return
        commitPatches(patches)
    }

    override fun onInpaintFailed(cause: Throwable) {
        when ((cause as? MiGanInpaintException)?.reason) {
            MiGanFailureReason.MODEL_MISSING -> promptModelInstall()
            MiGanFailureReason.PLAN_TOO_LARGE -> showToast(R.string.editor_inpaint_plan_too_large)
            null -> {
                Timber.e(cause, "Inpainting failed")
                showToast(R.string.editor_inpaint_failed)
            }
            else -> showToast(R.string.editor_inpaint_failed)
        }
    }

    override fun onInpaintNoStrokes() {
        showToast(R.string.editor_inpaint_no_strokes)
    }

    override fun onInpaintStrokesCleared() = Unit

    private suspend fun runInpaint(
        strokes: List<InpaintStroke>,
        onProgress: (Int) -> Unit
    ): Result<List<InpaintPatchRef>> {
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
        val result = inpainter.inpaint(
            photoId = photoId,
            sourceArgb = pixels,
            width = region.width,
            height = region.height,
            mask = mask,
            onProgress = onProgress
        )
        return result.map { patches ->
            patches.map { patch -> patch.copy(bbox = region.toSourceBBox(patch.bbox)) }
        }
    }

    private fun rasterize(strokes: List<InpaintStroke>, region: InpaintWorkRegion): InpaintMaskBuffer {
        val layer = InpaintStrokeLayer()
        strokes.forEach(layer::add)
        return layer.rasterize(region.width, region.height)
    }

    private fun commitPatches(patches: List<InpaintPatchRef>) {
        val graph = undoController.currentGraph
        // One undo step per confirmation, even if two runs landed inside the coalescing window.
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
        AiModelPromptDialog.show(
            context = context,
            spec = AiModelCatalog.MI_GAN_GENERATOR,
            onDownload = ::downloadModel,
            onImport = onImportRequested
        )
    }

    private fun downloadModel() {
        launchWork {
            inferenceBar.show(context.getString(R.string.editor_inpaint_model_downloading))
            val result = downloads.download(AiModelCatalog.MI_GAN_GENERATOR) { progress ->
                val percent = (progress * PROGRESS_SCALE).toInt().coerceIn(0, 100)
                scope.launch { inferenceBar.updateProgress(percent) }
            }
            inferenceBar.dismiss()
            result.fold(
                onSuccess = { confirmPendingRun() },
                onFailure = { error ->
                    Timber.e(error, "The MI-GAN model download failed")
                    showToast(R.string.editor_subject_model_unavailable)
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

    private fun failure(message: String): Result<List<InpaintPatchRef>> =
        Result.failure(MiGanInpaintException(MiGanFailureReason.INVALID_INPUT, message))

    private fun showToast(messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PROGRESS_SCALE = 100.0f
    }
}
