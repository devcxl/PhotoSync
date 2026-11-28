package cn.devcxl.photosync.activity

import android.content.Context
import android.text.format.Formatter

import cn.devcxl.photosync.R
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelFailureReason
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy
import cn.devcxl.photosync.editor.vision.AiModelInstallException
import cn.devcxl.photosync.editor.vision.AiModelSpec

/**
 * Localized text mapping of the AI model settings page.
 *
 * Failure copy and recovery actions come from the shared `:editor` mapping, so the settings page
 * and the editor's first-install prompt cannot drift into two different explanations of the same
 * reason.
 */
internal object AiModelSettingsText {

    /** Localized model name of [spec]. */
    fun nameRes(spec: AiModelSpec): Int = when (spec.id) {
        AiModelCatalog.MODNET_PORTRAIT.id -> R.string.ai_models_model_modnet
        AiModelCatalog.MI_GAN_PIPELINE.id,
        AiModelCatalog.MI_GAN_GENERATOR_LEGACY.id -> R.string.ai_models_model_migan
        else -> R.string.ai_models_model_unknown
    }

    /** Localized one-line description of [spec]. */
    fun descriptionRes(spec: AiModelSpec): Int = when (spec.id) {
        AiModelCatalog.MODNET_PORTRAIT.id -> R.string.ai_models_model_modnet_desc
        AiModelCatalog.MI_GAN_PIPELINE.id,
        AiModelCatalog.MI_GAN_GENERATOR_LEGACY.id -> R.string.ai_models_model_migan_desc
        else -> R.string.ai_models_model_unknown
    }

    /** Human-readable size of [bytes]; a non-positive value means the artifact is still unpinned. */
    fun formatBytes(context: Context, bytes: Long): String =
        if (bytes <= 0L) {
            context.getString(R.string.ai_models_size_unknown)
        } else {
            Formatter.formatFileSize(context, bytes)
        }

    /** Resource id of the shared hint for [reason]. */
    fun failureMessageRes(reason: AiModelFailureReason?): Int =
        AiModelFailureUiCopy.of(reason).messageRes

    /** Localized hint for the [AiModelFailureReason] carried by [result]. */
    fun failureMessage(context: Context, result: Result<*>): String {
        val reason = (result.exceptionOrNull() as? AiModelInstallException)?.reason
        return context.getString(failureMessageRes(reason))
    }
}
