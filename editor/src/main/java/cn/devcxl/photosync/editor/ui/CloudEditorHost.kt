package cn.devcxl.photosync.editor.ui

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelListener
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelState
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Editor integration seam of the `AI -> 云端` tool.
 *
 * The controller owns the state machine, undo transactions and geometry guard; the activity shell
 * implements this contract with its preview session, shared inference bar and dialog helpers. Keeping
 * the seam explicit is what lets [EditorCloudController] be tested without an Activity, a dialog or a
 * network stack.
 */
internal interface EditorCloudHost {

    /** Current Post-Pass A composite, or `null` while the preview is still loading. */
    fun currentPostPassABitmap(): Bitmap?

    /** Current painted stroke overlay, when the erase brush has uncommitted strokes. */
    fun paintedMaskBitmap(): Bitmap?

    /** Refined subject mask for the background-replacement mode. */
    fun subjectMask(): SubjectMask?

    /** Shows the shared non-blocking progress bar. */
    fun showProgress(labelRes: Int, initialPercent: Int)

    /** Moves the progress bar and label. */
    fun updateProgress(labelRes: Int, percent: Int)

    /** Hides the progress bar. */
    fun hideProgress()

    /** Shows a localized message. */
    fun showToast(messageRes: Int)

    /** Shows the one-time disclosure and continues only after acceptance. */
    fun showConsentDialog(onAccepted: () -> Unit)

    /**
     * Shows the geometry conflict sheet.
     *
     * @param onRemoveLayer The user accepted dropping the layer to keep the new framing.
     * @param onCancel The user kept the layer; the geometry change must be abandoned.
     */
    fun showGeometryConflictDialog(onRemoveLayer: () -> Unit, onCancel: () -> Unit = {})

    /** The graph changed and preview/cache/bake consumers must refresh. */
    fun onGraphCommitted(graph: EditGraph)

    /**
     * Restores panel and canvas to [graph] after a rejected transient geometry change.
     *
     * The geometry guard rejected the write, so no undo step exists; the host only has to repaint
     * whatever transient value the dial pushed before the sheet opened. Hosts without a canvas
     * keep the default no-op.
     */
    fun onGeometryChangeDiscarded(graph: EditGraph) = Unit

    /** Live layer preview that must not create an undo step yet. */
    fun onCloudLayerPreviewChanged(layer: CloudEditLayerRef?)
}

/** View operations the cloud controller drives. */
internal interface CloudPanelPort {

    /** Shows or hides the whole tier-1 cloud panel. */
    fun setCloudPanelVisible(visible: Boolean)

    /** Repaints the panel from [state]. */
    fun renderCloudPanel(state: CloudPanelState)

    /** Routes the panel's user intents to [listener]. */
    fun bindListener(listener: CloudPanelListener)
}
