package cn.devcxl.photosync.activity

import android.content.Context
import android.text.format.Formatter

import cn.devcxl.photosync.R
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelFailureReason
import cn.devcxl.photosync.editor.vision.AiModelInstallException
import cn.devcxl.photosync.editor.vision.AiModelSpec

/**
 * Localized text mapping of the AI model settings page.
 *
 * Keeping the resource lookup here leaves [AiModelSettingsActivity] focused on action routing and
 * gives the failure-reason mapping one place to stay exhaustive.
 */
internal object AiModelSettingsText {

    /** Localized model name of [spec]. */
    fun nameRes(spec: AiModelSpec): Int = when (spec.id) {
        AiModelCatalog.MODNET_PORTRAIT.id -> R.string.ai_models_model_modnet
        AiModelCatalog.MI_GAN_GENERATOR.id -> R.string.ai_models_model_migan
        else -> R.string.ai_models_model_unknown
    }

    /** Localized one-line description of [spec]. */
    fun descriptionRes(spec: AiModelSpec): Int = when (spec.id) {
        AiModelCatalog.MODNET_PORTRAIT.id -> R.string.ai_models_model_modnet_desc
        AiModelCatalog.MI_GAN_GENERATOR.id -> R.string.ai_models_model_migan_desc
        else -> R.string.ai_models_model_unknown
    }

    /** Human-readable size of [bytes]; a non-positive value means the artifact is still unpinned. */
    fun formatBytes(context: Context, bytes: Long): String =
        if (bytes <= 0L) {
            context.getString(R.string.ai_models_size_unknown)
        } else {
            Formatter.formatFileSize(context, bytes)
        }

    /** Localized hint for the [AiModelFailureReason] carried by [result]. */
    fun failureMessage(context: Context, result: Result<*>): String {
        val reason = (result.exceptionOrNull() as? AiModelInstallException)?.reason
        val messageRes = when (reason) {
            AiModelFailureReason.NOT_AVAILABLE -> R.string.ai_models_reason_not_available
            AiModelFailureReason.DOWNLOAD_FAILED -> R.string.ai_models_reason_download_failed
            AiModelFailureReason.SIZE_MISMATCH -> R.string.ai_models_reason_size_mismatch
            AiModelFailureReason.CHECKSUM_MISMATCH -> R.string.ai_models_reason_checksum_mismatch
            AiModelFailureReason.STORAGE_FAILURE -> R.string.ai_models_reason_storage_failure
            null -> R.string.ai_models_reason_download_failed
        }
        return context.getString(messageRes)
    }
}
