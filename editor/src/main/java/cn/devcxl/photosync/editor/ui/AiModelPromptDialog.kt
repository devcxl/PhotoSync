package cn.devcxl.photosync.editor.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.vision.AiModelSpec

/**
 * Confirmation sheet shown the first time an AI tool needs an on-demand model.
 *
 * It is deliberately generic over [AiModelSpec] so the P2 inpainting tool and any future P3/P4
 * model reuse the same consent flow: the user sees the artifact name, its real download size and how
 * many mirrors will be tried, and may either download it or pick an already downloaded `.onnx` file
 * through the system document picker. Nothing is downloaded before the positive action.
 */
internal object AiModelPromptDialog {

    /**
     * Shows the confirmation dialog.
     *
     * @param spec Catalog entry about to be installed.
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
        onDismiss: () -> Unit = {}
    ) {
        val megabytes = spec.byteSize / BYTES_PER_MB.toDouble()
        AlertDialog.Builder(context)
            .setTitle(R.string.editor_subject_model_dialog_title)
            .setMessage(
                context.getString(
                    R.string.editor_subject_model_dialog_message,
                    spec.fileName,
                    megabytes,
                    spec.mirrorUrls.size
                )
            )
            .setPositiveButton(R.string.editor_subject_model_download) { _, _ -> onDownload() }
            .setNeutralButton(R.string.editor_subject_model_import) { _, _ -> onImport() }
            .setNegativeButton(R.string.editor_subject_model_cancel, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    private const val BYTES_PER_MB = 1_000_000L
}
