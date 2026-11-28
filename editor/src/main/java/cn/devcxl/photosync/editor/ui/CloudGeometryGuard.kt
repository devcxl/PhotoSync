package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController

/**
 * Non-destructive Pass A geometry guard of the P4 cloud layer.
 *
 * A `CloudEditLayer` is generated for exactly one framing (crop, rotate, mirror, straighten,
 * perspective and liquify), so changing any of them afterwards would paste the result onto a frame
 * it does not describe. Every geometry write of the editor funnels through
 * [EditorUndoController.onParameterDrag] or [EditorUndoController.replaceLiquifyRefInPlace], so the
 * guard installs itself as that controller's veto hook instead of asking six call sites to remember
 * the check.
 *
 * The user always keeps control: the change is rejected while the conflict sheet is open, and
 * either the framing change is abandoned (the layer stays) or the layer is removed in the same undo
 * step that applies the new geometry.
 */
internal class CloudGeometryGuard(
    private val undoController: EditorUndoController,
    private val host: () -> EditorCloudHost,
    private val commit: (EditGraph, EditParameterKey) -> Unit
) {

    private var pending = false
    private var bypass = false
    private var pendingTarget: EditGraph? = null
    private var pendingKey: EditParameterKey? = null

    /** Installs the veto hook on [controller]. */
    fun install(controller: EditorUndoController) {
        controller.parameterGuard = { target, key -> bypass || check(target, key) }
    }

    /**
     * @return True when the change may proceed; false when it was rejected and the sheet is open.
     */
    fun check(target: EditGraph, parameterKey: EditParameterKey): Boolean {
        val current = undoController.currentGraph
        val layer = current.cloudLayer ?: return true
        if (target.geometry == current.geometry) return true
        // The dial may have moved away and back again within one sheet: compare the new framing's
        // digest with the layer binding so returning to the original crop never re-opens the guard.
        if (!CloudGeometryDigest.isMismatch(layer, target.geometry)) return true
        // A continuous dial drag fires one write per step; the sheet must open exactly once.
        if (pending) {
            // A further stroke or dial step while the sheet is open: remember its framing so the
            // "remove the layer" decision applies what the user actually did last.
            pendingTarget = target
            pendingKey = parameterKey
            return false
        }
        pending = true
        pendingTarget = target
        pendingKey = parameterKey
        host().showGeometryConflictDialog(
            onRemoveLayer = { resolve() },
            onCancel = { cancel() }
        )
        return false
    }

    /**
     * Abandons the pending framing change and repaints the editor from the unchanged graph.
     *
     * The slider already produced a transient canvas preview, so the host must be told to restore
     * the panel and canvas to [EditorUndoController.currentGraph]; the next geometry write then
     * opens the sheet again instead of silently bypassing the guard.
     */
    private fun cancel() {
        clearPending()
        host().onGeometryChangeDiscarded(undoController.currentGraph)
    }

    private fun resolve() {
        val target = pendingTarget ?: return clearPending()
        val parameterKey = pendingKey ?: return clearPending()
        clearPending()
        if (undoController.currentGraph.cloudLayer == null) return
        // Removing the layer does not change the framing, so this very commit must not re-open the
        // sheet through the guard.
        bypass = true
        try {
            commit(target.copy(cloudLayer = null), parameterKey)
        } finally {
            bypass = false
        }
        host().showToast(R.string.editor_cloud_geometry_removed)
    }

    private fun clearPending() {
        pending = false
        pendingTarget = null
        pendingKey = null
    }
}
