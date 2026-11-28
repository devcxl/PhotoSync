package cn.devcxl.photosync.editor.ui.panels

import android.view.View

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.SkyCustomAsset
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.ui.widgets.SkyPanelView

/**
 * Binds the `SkyPanelView` include to the sky controller.
 *
 * The binder owns no policy: it forwards asset choices and slider values, and asks the host for the
 * imported library and the `< 3 %` state on every refresh. Keeping it here lets
 * [EditorPanelController] stay a visibility switch and keeps the sky wiring out of the activity.
 */
internal class SkyPanelBinder(
    private val binding: ActivityEditorBinding,
    private val host: Host
) {

    /** Callbacks the activity implements for the sky tool. */
    interface Host {
        /** The user chose a replacement sky (or the original). */
        fun onSkySelected(sky: SkyOps)

        /** A slider moved; the graph is updated as a transient edit. */
        fun onSkyAdjusted(sky: SkyOps)

        /** The slider was released; the undo transaction can be sealed. */
        fun onSkyAdjustmentStopped()

        /** Opens the SAF picker for a sky photograph. */
        fun onSkyImportRequested()

        /** A long press asked to delete the imported sky [sha256]. */
        fun onSkyDeleteRequested(sha256: String)

        /** Imported skies shown by the picker row. */
        fun skyCustomAssets(): List<SkyCustomAsset>

        /** True when the current segmentation found a usable sky area. */
        fun skyHasSky(): Boolean
    }

    private val panel: SkyPanelView = binding.skyPanel.root

    init {
        panel.listener = object : SkyPanelView.Listener {
            override fun onSkySelected(sky: SkyOps) = host.onSkySelected(sky)
            override fun onSkyAdjusted(sky: SkyOps) = host.onSkyAdjusted(sky)
            override fun onSkyAdjustmentStopped() = host.onSkyAdjustmentStopped()
            override fun onImportRequested() = host.onSkyImportRequested()
            override fun onCustomDeleteRequested(sha256: String) = host.onSkyDeleteRequested(sha256)
        }
    }

    /** Shows or hides the tier-1 sky panel. */
    fun setVisible(visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Repaints the chips and sliders from the current graph and imported library. */
    fun refresh(sky: SkyOps) {
        panel.bind(sky, host.skyCustomAssets(), host.skyHasSky())
    }
}
