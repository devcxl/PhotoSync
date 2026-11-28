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
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadCancellation
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy
import cn.devcxl.photosync.editor.vision.AiModelInstallUi

/**
 * Owns the on-demand MODNet install flow of the subject tool.
 *
 * Download and SAF import share the multi-mirror [AiModelDownloadManager]; both report progress on
 * the editor's non-blocking inference bar and hand a verified model file to the caller. A failure
 * is logged, dismissed from the bar and reported through the shared [AiModelFailureUiCopy] wording,
 * the same sentence the settings page shows, before [onFailed] reopens the install prompt.
 */
internal class SubjectModelInstaller(
    private val context: Context,
    filesDir: File,
    private val scope: CoroutineScope,
    private val inferenceBar: AiInferenceBarController,
    private val onImportRequested: () -> Unit
) {

    private val downloads = AiModelDownloadManager(File(filesDir, AiModelCatalog.MODELS_DIR_NAME))
    private val spec = AiModelCatalog.MODNET_PORTRAIT
    private var job: Job? = null
    private var cancellation: AiModelDownloadCancellation? = null

    /** Absolute path of the installed model, whether or not it exists yet. */
    fun modelFile(): File = downloads.modelFile(spec)

    /** True when the verified model is already installed. */
    fun isInstalled(): Boolean = downloads.isInstalled(spec)

    /**
     * Cancels an in-flight download, import or the generation it handed over.
     *
     * A download receives the cooperative pause flag first so it can return a resumable result and
     * keep the `.part`; if only an import is running, the job itself is cancelled.
     */
    fun cancel() {
        val active = cancellation
        if (active != null) active.cancel() else job?.cancel()
    }

    /** True when a download, import or its hand-over is still running. */
    fun isBusy(): Boolean = job?.isActive == true

    /** Shows the shared reason sentence of the last real attempt, or the generic hint if none. */
    fun showUnavailable() {
        val reason = downloads.lastProbeResult(spec).failureReason
        val message = if (reason != null) {
            context.getString(AiModelFailureUiCopy.of(reason).messageRes)
        } else {
            context.getString(R.string.editor_subject_model_unavailable)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** Shows the shared download/SAF-import sheet, including the last failure reason if any. */
    fun showPrompt(onDownload: () -> Unit, onDismiss: () -> Unit = {}) {
        val state = AiModelInstallUi.idle(
            spec = spec,
            installed = downloads.isInstalled(spec),
            partialBytes = downloads.partFile(spec).takeIf { it.isFile }?.length() ?: 0L,
            lastProbe = downloads.lastProbeResult(spec)
        )
        AiModelPromptDialog.show(
            context = context,
            spec = spec,
            onDownload = onDownload,
            onImport = onImportRequested,
            onDismiss = onDismiss,
            state = state
        )
    }

    /** Imports a user-selected `.onnx` and hands the verified file to [onReady]. */
    fun import(input: InputStream, onReady: suspend (File) -> Unit, onFailed: () -> Unit) {
        launchWork {
            inferenceBar.show(context.getString(R.string.editor_subject_model_importing))
            inferenceBar.updateProgress(IMPORT_PROGRESS)
            val result = downloads.importLocalAny(
                candidates = AiModelCatalog.importCandidates(spec),
                input = input
            )
            inferenceBar.dismiss()
            if (result.isSuccess) {
                onReady(result.getOrThrow())
            } else {
                Timber.e(result.exceptionOrNull(), "The local MODNet model was rejected")
                onFailed()
            }
        }
    }

    /** Downloads the model from the shared mirrors and hands the verified file to [onReady]. */
    fun download(onReady: suspend (File) -> Unit, onFailed: () -> Unit) {
        launchWork {
            val token = AiModelDownloadCancellation()
            cancellation = token
            inferenceBar.show(context.getString(R.string.editor_subject_model_downloading))
            val result = downloads.download(spec, onProgress = { progress ->
                val percent = (progress * PROGRESS_SCALE).toInt().coerceIn(0, 100)
                scope.launch { inferenceBar.updateProgress(percent) }
            }, cancellation = token)
            cancellation = null
            inferenceBar.dismiss()
            if (result.isSuccess) {
                onReady(result.getOrThrow())
            } else {
                Timber.e(result.exceptionOrNull(), "The MODNet model download failed")
                onFailed()
            }
        }
    }

    private fun launchWork(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private companion object {
        const val IMPORT_PROGRESS = 10
        const val PROGRESS_SCALE = 100.0f
    }
}
