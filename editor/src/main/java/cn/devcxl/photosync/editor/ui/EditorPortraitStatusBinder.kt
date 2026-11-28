package cn.devcxl.photosync.editor.ui

import android.view.View

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding

/**
 * Renders the portrait analysis banner under the canvas.
 *
 * The banner is advisory: a missing MediaPipe model is a hint, never a blocking error, so the
 * editor stays usable while the AI group waits for its artifact.
 */
internal object EditorPortraitStatusBinder {

    /** Applies [status] to the banner row of [binding]. */
    fun render(binding: ActivityEditorBinding, status: PortraitPreviewStatus) {
        val row = binding.portraitPreviewStatus
        row.root.visibility = if (status == PortraitPreviewStatus.Idle) View.GONE else View.VISIBLE
        row.portraitPreviewSpinner.visibility =
            if (status == PortraitPreviewStatus.Analyzing) View.VISIBLE else View.GONE
        row.portraitPreviewMessage.setText(
            if (status == PortraitPreviewStatus.Unavailable) {
                R.string.editor_portrait_preview_unavailable
            } else {
                R.string.editor_portrait_analyzing
            }
        )
    }
}
