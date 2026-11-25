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
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.render.EditorSource
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
 * 1. The brush controller rasterizes the painted strokes and asks [executor] to repair them.
 * 2. Without the MI-GAN artifact the run fails with [MiGanFailureReason.MODEL_MISSING]; this
 *    controller then shows the shared download/SAF-import sheet and re-runs the same strokes once the
 *    model verified, so nothing the user painted is lost.
 * 3. A successful run appends its ordered patches as **one** undo transaction, which the preview,
 *    the cache and the full-resolution bake all pick up through `graph.inpaint`.
 *
 * The inference bar is non-blocking and cancellable; a cancelled run keeps the strokes for a retry.
 * Every recoverable failure is a localized toast, never a crash.
 */
internal class EditorInpaintController(
    private val context: Context,
    filesDir: File,
    private val photoId: Long,
    private val undoController: EditorUndoController,
    private val inferenceBar: AiInferenceBarController,
    private val scope: CoroutineScope,
    private val sourceProvider: () -> EditorSource?,
    private val onCanvasTransform: (CanvasTransform) -> Unit,
    private val onGraphCommitted: (EditGraph, EditParameterKey) -> Unit,
    private val onPatchesApplied: () -> Unit,
    private val onImportRequested: () -> Unit,
    private val onConfirmRequested: () -> Unit
) : InpaintBrushController.Host {

    private val assets = AiAssetStore(filesDir)
    private val downloads = AiModelDownloadManager(File(filesDir, AiModelCatalog.MODELS_DIR_NAME))
    private val sessions = OrtSessionManager(tier = DeviceMemoryTier.detect(context))
    private val inpainter = MiGanInpainter(sessions, downloads, assets)

    private var installJob: Job? = null
    private var cachedSource: EditorSource? = null
    private var cachedArgb: IntArray? = null

    /** The executor the brush controller drives; adapts [MiGanInpainter]. */
    fun executor(): InpaintBrushController.InpaintExecutor =
        InpaintBrushController.InpaintExecutor { mask, onProgress -> runInpaint(mask, onProgress) }

    /** Stops a model download/import; an in-flight inference is owned by the brush controller. */
    fun cancel() {
        installJob?.cancel()
        installJob = null
    }

    /** Releases the ORT session this controller owns. */
    fun release() {
        cancel()
        sessions.close()
        cachedSource = null
        cachedArgb = null
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
        mask: InpaintMaskBuffer,
        onProgress: (Int) -> Unit
    ): Result<List<InpaintPatchRef>> {
        val source = sourceProvider()
            ?: return failure("The preview source is not ready")
        val pixels = argbFor(source) ?: return failure("The preview pixels could not be read")
        currentCoroutineContext().ensureActive()
        return inpainter.inpaint(
            photoId = photoId,
            sourceArgb = pixels,
            width = source.width,
            height = source.height,
            mask = mask,
            onProgress = onProgress
        )
    }

    /**
     * Returns the ARGB pixels of [source], reusing the previous conversion while the source instance
     * is unchanged. The preview source is replaced (not mutated) whenever the graph's patches change,
     * so identity is a sufficient cache key.
     */
    private suspend fun argbFor(source: EditorSource): IntArray? {
        if (source === cachedSource) cachedArgb?.let { return it }
        val pixels = withContext(Dispatchers.Default) {
            try {
                when (source) {
                    is EditorSource.BitmapSource -> {
                        val bitmap = source.full
                        IntArray(bitmap.width * bitmap.height).also {
                            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        }
                    }
                    is EditorSource.LinearRawSource -> LinearRawPreviewRenderer.renderArgb(
                        source = source.full,
                        graph = EditGraph(),
                        fullResolutionShortEdge = minOf(source.width, source.height)
                    )
                }
            } catch (oom: OutOfMemoryError) {
                Timber.e(oom, "OOM while reading the preview source for inpainting")
                null
            }
        }
        if (pixels != null) {
            cachedSource = source
            cachedArgb = pixels
        }
        return pixels
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
