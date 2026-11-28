package cn.devcxl.photosync.editor.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.vision.AiModelFailureReason
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy
import cn.devcxl.photosync.editor.vision.AiModelInstallUiState
import cn.devcxl.photosync.editor.vision.AiModelSpec
import cn.devcxl.photosync.editor.vision.AiModelUiAction

/**
 * Confirmation sheet shown the first time an AI tool needs an on-demand model.
 *
 * It is deliberately generic over [AiModelSpec] so every AI tool reuses the same consent flow: the
 * user sees the artifact name, its real download size and how many mirrors will be tried, and may
 * either download it or pick an already downloaded `.onnx` file through the system document picker.
 * Nothing is downloaded before the positive action. When the previous attempt failed, the sheet
 * appends the same sentence and recovery hint the settings page uses for that reason.
 */
internal object AiModelPromptDialog {

    /**
     * Shows the confirmation dialog.
     *
     * @param spec Catalog entry about to be installed.
     * @param state Shared install state for [spec], if the caller already reduced it.
     * @param failureReason Last real failure recorded for [spec], if any; ignored when [state] is
     *   provided.
     * @param onDownload Invoked when the user confirms the download.
     * @param onImport Invoked when the user prefers a local `.onnx` file.
     * @param onDismiss Invoked after the sheet closes for any reason, so a caller can clear a
     *   "prompt already shown" guard.
     */
    fun show(
        context: Context,
        spec: AiModelSpec,
        onDownload: () -> Unit,
        onImport: () -> Unit,
        onDismiss: () -> Unit = {},
        failureReason: AiModelFailureReason? = null,
        state: AiModelInstallUiState? = null
    ) {
        val megabytes = spec.byteSize / BYTES_PER_MB.toDouble()
        val effectiveReason = state?.failureReason ?: failureReason
        val message = buildString {
            append(
                context.getString(
                    R.string.editor_subject_model_dialog_message,
                    spec.fileName,
                    megabytes,
                    spec.mirrorUrls.size
                )
            )
            if (effectiveReason != null) {
                append("\n\n")
                append(context.getString(AiModelFailureUiCopy.of(effectiveReason).messageRes))
            }
        }
        val downloadOffered = state?.offersDownload ?: true
        val importOffered = state?.offers(AiModelUiAction.IMPORT_LOCAL) ?: true
        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.editor_subject_model_dialog_title)
            .setMessage(message)
            .setNegativeButton(R.string.editor_subject_model_cancel, null)
            .setOnDismissListener { onDismiss() }
        if (downloadOffered) {
            builder.setPositiveButton(
                if (state?.downloadMayFail == true) {
                    R.string.editor_subject_model_download_may_fail
                } else {
                    R.string.editor_subject_model_download
                }
            ) { _, _ -> onDownload() }
        }
        if (importOffered) {
            builder.setNeutralButton(R.string.editor_subject_model_import) { _, _ -> onImport() }
        }
        builder.show()
    }

    private const val BYTES_PER_MB = 1_000_000L
}
