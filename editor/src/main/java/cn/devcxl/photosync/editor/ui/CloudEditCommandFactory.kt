package cn.devcxl.photosync.editor.ui

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.cloud.CloudEditCommand
import cn.devcxl.photosync.editor.cloud.CloudEditMode
import cn.devcxl.photosync.editor.cloud.CloudMaskMode
import cn.devcxl.photosync.editor.cloud.CloudProviderKind
import cn.devcxl.photosync.editor.cloud.CloudProviderProfile
import cn.devcxl.photosync.editor.cloud.CloudProviderSource
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryOps

/**
 * Builds one [CloudEditCommand] from the current canvas and panel state.
 *
 * Kept apart from [EditorCloudController] so the request-shape validation of the three modes stays
 * a pure, individually testable step and the controller's state machine keeps its size.
 */
internal class CloudEditCommandFactory(
    private val host: EditorCloudHost,
    private val graphProvider: () -> EditGraph,
    private val providers: CloudProviderSource
) {

    /**
     * Builds the request for [mode], or `null` after showing the reason it cannot be sent.
     *
     * @param featherPx Blend-mask feather of the next local request, in upload pixels.
     */
    fun build(mode: CloudEditMode, prompt: String, featherPx: Float): CloudEditCommand? {
        val source = host.currentPostPassABitmap()?.takeIf { !it.isRecycled }
        if (source == null || source.width <= 0 || source.height <= 0) {
            host.showToast(R.string.editor_cloud_source_unavailable)
            return null
        }
        val geometry = graphProvider().geometry
        return when (mode) {
            CloudEditMode.WHOLE_IMAGE -> CloudEditCommand(
                mode = mode,
                prompt = prompt,
                geometry = geometry,
                source = source
            )
            CloudEditMode.PAINTED_MASK -> paintedMaskCommand(mode, prompt, geometry, source, featherPx)
            CloudEditMode.SUBJECT_BACKGROUND ->
                subjectBackgroundCommand(mode, prompt, geometry, source, featherPx)
        }
    }

    private fun paintedMaskCommand(
        mode: CloudEditMode,
        prompt: String,
        geometry: GeometryOps,
        source: Bitmap,
        featherPx: Float
    ): CloudEditCommand? {
        if (maskMode() == CloudMaskMode.NONE) {
            host.showToast(R.string.editor_cloud_mask_unsupported)
            return null
        }
        val mask = host.paintedMaskBitmap()?.takeIf { !it.isRecycled }
        if (mask == null) {
            host.showToast(R.string.editor_cloud_painted_mask_missing)
            return null
        }
        return CloudEditCommand(
            mode = mode,
            prompt = prompt,
            geometry = geometry,
            source = source,
            paintedMask = mask,
            featherPx = featherPx
        )
    }

    private fun subjectBackgroundCommand(
        mode: CloudEditMode,
        prompt: String,
        geometry: GeometryOps,
        source: Bitmap,
        featherPx: Float
    ): CloudEditCommand? {
        if (maskMode() == CloudMaskMode.NONE) {
            host.showToast(R.string.editor_cloud_mask_unsupported)
            return null
        }
        val subject = host.subjectMask()
        if (subject == null) {
            host.showToast(R.string.editor_cloud_subject_mask_missing)
            return null
        }
        return CloudEditCommand(
            mode = mode,
            prompt = prompt,
            geometry = geometry,
            source = source,
            subjectMask = subject,
            featherPx = featherPx
        )
    }

    /**
     * Advisory mask capability of the selected profile, used for chip availability and the Gemini
     * warning. The authoritative contract remains [cn.devcxl.photosync.editor.cloud.CloudEditProvider.capabilities]
     * inside the service.
     */
    fun maskMode(): CloudMaskMode {
        val profile: CloudProviderProfile = providers.activeProfile() ?: return CloudMaskMode.NONE
        return when (profile.kind) {
            CloudProviderKind.GEMINI -> CloudMaskMode.SOFT
            CloudProviderKind.OPENAI_COMPATIBLE -> CloudMaskMode.HARD
            CloudProviderKind.COMFY_UI -> {
                if (profile.maskNodeId.isNotBlank()) CloudMaskMode.HARD else CloudMaskMode.NONE
            }
        }
    }
}
