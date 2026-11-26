package cn.devcxl.photosync.editor.ui

import android.content.Context

import androidx.appcompat.app.AlertDialog

import cn.devcxl.photosync.editor.R

/**
 * Blocking disclosure sheets of the P4 cloud workflow.
 *
 * Both sheets are deliberately explicit: the first is not cancellable (the user must pick either
 * accept or cancel) and the second names the exact trade-off, so a geometry change can never quietly
 * discard a paid-for cloud result.
 */
internal object CloudDialogs {

    /**
     * Shows the one-time privacy and cost disclosure.
     *
     * @param onAccept Invoked only after the user taps the positive button.
     */
    fun showConsent(context: Context, onAccept: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.editor_cloud_consent_title)
            .setMessage(R.string.editor_cloud_consent_message)
            .setPositiveButton(R.string.editor_cloud_consent_accept) { _, _ -> onAccept() }
            .setNegativeButton(R.string.editor_cloud_consent_cancel, null)
            .setCancelable(false)
            .show()
    }

    /**
     * Shows the non-destructive geometry guard when the layer digest no longer matches.
     *
     * @param onRemoveLayer The user chose to clear the cloud layer and continue.
     * @param onCancel The user chose to keep the layer; the geometry change must be abandoned.
     */
    fun showGeometryConflict(
        context: Context,
        onRemoveLayer: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.editor_cloud_geometry_title)
            .setMessage(R.string.editor_cloud_geometry_message)
            .setPositiveButton(R.string.editor_cloud_geometry_remove) { _, _ -> onRemoveLayer() }
            .setNegativeButton(R.string.editor_cloud_geometry_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
