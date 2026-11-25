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
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.render.SubjectCompositeLoader
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.ModnetSubjectSegmenter
import cn.devcxl.photosync.editor.vision.OrtSessionManager
import cn.devcxl.photosync.editor.vision.SubjectImage

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
    private val filesDir: File,
    private val photoId: Long,
    private val undoController: EditorUndoController,
    private val previewSession: EditorPreviewSession,
    private val inferenceBar: AiInferenceBarController,
    private val scope: CoroutineScope,
    private val sourceProvider: () -> EditorSource?,
    private val onGraphCommitted: (EditGraph, EditParameterKey) -> Unit,
    private val onImportRequested: () -> Unit
) {

    private val assets = AiAssetStore(filesDir)
    private val downloads = AiModelDownloadManager(
        File(filesDir, AiModelCatalog.MODELS_DIR_NAME)
    )
    private val sessions = OrtSessionManager(tier = DeviceMemoryTier.detect(context))
    private val segmenter = ModnetSubjectSegmenter(sessions, assets)

    private var job: Job? = null
    private var cachedMaskRef: String = ""
    private var cachedComposite: SubjectComposite? = null
    private var pendingMode: SubjectBackgroundMode? = null
    private var pendingColor: Int = SubjectOps.DEFAULT_BG_COLOR_ARGB

    /** Stops the in-flight download/inference; the graph is left as it was. */
    fun cancel() {
        job?.cancel()
        job = null
        pendingMode = null
        if (inferenceBar.isVisible) inferenceBar.dismiss()
    }

    /** Releases the ORT session this controller owns. */
    fun release() {
        cancel()
        sessions.close()
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
            commit(graph.copy(subject = graph.subject.copy(bgMode = SubjectBackgroundMode.ORIGINAL)), null)
            return
        }
        launchWork {
            val composite = loadComposite(graph, graph.subject)
            if (composite == null) {
                commitOnMain(
                    undoController.currentGraph.copy(
                        subject = undoController.currentGraph.subject.copy(
                            bgMode = SubjectBackgroundMode.ORIGINAL
                        )
                    ),
                    null
                )
            } else {
                commitOnMain(undoController.currentGraph, composite)
            }
        }
    }

    /** Persists a user-selected `.onnx` file and continues the pending mask generation. */
    fun importModel(input: InputStream) {
        launchWork {
            inferenceBar.show(context.getString(R.string.editor_subject_model_importing))
            inferenceBar.updateProgress(10)
            val result = downloads.importLocal(AiModelCatalog.MODNET_PORTRAIT, input)
            inferenceBar.dismiss()
            result.fold(
                onSuccess = { model ->
                    generateWithModel(
                        modelFile = model,
                        graph = undoController.currentGraph,
                        mode = pendingMode ?: undoController.currentGraph.subject.bgMode,
                        color = pendingColor
                    )
                },
                onFailure = { error ->
                    Timber.e(error, "The local MODNet model was rejected")
                    showUnavailable()
                    AiModelPromptDialog.show(
                        context = context,
                        spec = AiModelCatalog.MODNET_PORTRAIT,
                        onDownload = {
                            downloadModel(
                                undoController.currentGraph,
                                pendingMode ?: SubjectBackgroundMode.ORIGINAL,
                                pendingColor
                            )
                        },
                        onImport = onImportRequested
                    )
                }
            )
        }
    }

    private fun applyExistingMask(
        graph: EditGraph,
        mode: SubjectBackgroundMode,
        color: Int = graph.subject.bgColorArgb
    ) {
        launchWork {
            val composite = loadComposite(graph, graph.subject)
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

    private fun generateMask(
        graph: EditGraph,
        mode: SubjectBackgroundMode,
        color: Int
    ) {
        pendingMode = mode
        pendingColor = color
        val spec = AiModelCatalog.MODNET_PORTRAIT
        val modelFile = downloads.modelFile(spec)
        if (!downloads.isInstalled(spec)) {
            AiModelPromptDialog.show(
                context = context,
                spec = spec,
                onDownload = { downloadModel(graph, mode, color) },
                onImport = onImportRequested
            )
            return
        }
        launchWork {
            generateWithModel(modelFile, graph, mode, color)
        }
    }

    private fun downloadModel(graph: EditGraph, mode: SubjectBackgroundMode, color: Int) {
        launchWork {
            inferenceBar.show(context.getString(R.string.editor_subject_model_downloading))
            val result = downloads.download(AiModelCatalog.MODNET_PORTRAIT) { progress ->
                val percent = (progress * PROGRESS_SCALE).toInt()
                scope.launch { inferenceBar.updateProgress(percent) }
            }
            inferenceBar.dismiss()
            result.fold(
                onSuccess = { model -> generateWithModel(model, graph, mode, color) },
                onFailure = { error ->
                    Timber.e(error, "The MODNet model download failed")
                    showUnavailable()
                    AiModelPromptDialog.show(
                        context = context,
                        spec = AiModelCatalog.MODNET_PORTRAIT,
                        onDownload = { downloadModel(undoController.currentGraph, mode, color) },
                        onImport = onImportRequested
                    )
                }
            )
        }
    }

    private suspend fun generateWithModel(
        modelFile: File,
        graph: EditGraph,
        mode: SubjectBackgroundMode = graph.subject.bgMode,
        color: Int = graph.subject.bgColorArgb
    ) {
        val source = sourceProvider()
        val image = source?.let { subjectImage(it) }
        if (image == null) {
            showToast(R.string.editor_subject_inference_failed)
            return
        }
        inferenceBar.show(context.getString(R.string.editor_subject_inference_label))
        inferenceBar.updateProgress(PROGRESS_INFERENCE_START)
        val segmented = withContext(Dispatchers.Default) { segmenter.segment(image, modelFile) }
        currentCoroutineContext().ensureActive()
        inferenceBar.updateProgress(PROGRESS_INFERENCE_DONE)
        val mask = segmented.getOrElse { error ->
            Timber.e(error, "MODNet subject segmentation failed")
            inferenceBar.dismiss()
            showToast(R.string.editor_subject_inference_failed)
            return
        }
        val reference = segmenter.saveMask(photoId, mask)
        if (reference == null) {
            inferenceBar.dismiss()
            showToast(R.string.editor_subject_inference_failed)
            return
        }
        inferenceBar.updateLabel(context.getString(R.string.editor_subject_refine_label))
        val composite = withContext(Dispatchers.Default) {
            SubjectCompositeLoader.fromSource(source, mask)
        }
        currentCoroutineContext().ensureActive()
        inferenceBar.dismiss()
        commitOnMain(
            undoController.currentGraph.copy(
                subject = undoController.currentGraph.subject.copy(
                    maskRef = reference.relativePath,
                    bgMode = mode,
                    bgColorArgb = color
                )
            ),
            composite
        )
    }

    private suspend fun loadComposite(graph: EditGraph, subject: SubjectOps): SubjectComposite? {
        val maskRef = subject.maskRef
        if (maskRef == cachedMaskRef && cachedComposite != null) return cachedComposite
        return withContext(Dispatchers.IO) {
            val source = sourceProvider()
            val mask = SubjectCompositeLoader.loadMask(filesDir, graph)
            if (mask == null || source == null) {
                null
            } else {
                SubjectCompositeLoader.fromSource(source, mask)?.also {
                    cachedMaskRef = maskRef
                    cachedComposite = it
                }
            }
        }
    }

    private fun subjectImage(source: EditorSource): SubjectImage? = try {
        when (source) {
            is EditorSource.BitmapSource -> source.proxy.toSubjectImage()
            is EditorSource.LinearRawSource -> {
                val proxy = source.proxy
                val pixels = LinearRawPreviewRenderer.renderArgb(
                    source = proxy,
                    graph = EditGraph(),
                    fullResolutionShortEdge = minOf(proxy.width, proxy.height)
                )
                SubjectImage(proxy.width, proxy.height, pixels)
            }
        }
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "OOM while preparing the subject inference image")
        null
    }

    private fun android.graphics.Bitmap.toSubjectImage(): SubjectImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return SubjectImage(width, height, pixels)
    }

    private fun commitOnMain(graph: EditGraph, composite: SubjectComposite?) {
        previewSession.applySubjectComposite(composite)
        commit(graph, composite)
    }

    private fun commit(graph: EditGraph, composite: SubjectComposite?) {
        if (composite != null) {
            cachedComposite = composite
            cachedMaskRef = graph.subject.maskRef
        } else {
            cachedComposite = null
            cachedMaskRef = ""
        }
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

    private companion object {
        const val PROGRESS_SCALE = 100.0f
        const val PROGRESS_INFERENCE_START = 35
        const val PROGRESS_INFERENCE_DONE = 75
    }
}
