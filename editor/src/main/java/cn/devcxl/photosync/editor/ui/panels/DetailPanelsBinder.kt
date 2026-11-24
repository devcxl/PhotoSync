package cn.devcxl.photosync.editor.ui.panels

import android.view.View

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.NoiseReductionOps
import cn.devcxl.photosync.editor.domain.SharpenOps
import cn.devcxl.photosync.editor.ui.widgets.NoiseReductionPanelView
import cn.devcxl.photosync.editor.ui.widgets.SharpenPanelView

/**
 * Binds the two Pass C multi-row panels (`SharpenPanelView` and `NoiseReductionPanelView`) to
 * [EditorUndoController] so [EditorPanelController] stays a thin router.
 */
internal class DetailPanelsBinder(
    binding: ActivityEditorBinding,
    undoController: EditorUndoController,
    onEdited: (EditGraph, EditParameterKey) -> Unit,
    onCommitted: (EditGraph, EditParameterKey) -> Unit
) {

    private val panels = binding.detailPanels
    private val dispatcher = PanelEditDispatcher(undoController, onEdited, onCommitted)

    init {
        panels.sharpenPanelView.listener = object : SharpenPanelView.Listener {
            override fun onSharpenChanged(sharpen: SharpenOps) =
                dispatcher.pushChange(EditParameterKey.DETAIL_SHARPEN) {
                    it.copy(detail = it.detail.copy(sharpen = sharpen))
                }

            override fun onStopTrackingTouch() = dispatcher.commitRelease(EditParameterKey.DETAIL_SHARPEN)
        }
        panels.noiseReductionPanelView.listener = object : NoiseReductionPanelView.Listener {
            override fun onNoiseReductionChanged(noiseReduction: NoiseReductionOps) =
                dispatcher.pushChange(EditParameterKey.DETAIL_NOISE_REDUCTION) {
                    it.copy(detail = it.detail.copy(nr = noiseReduction))
                }

            override fun onStopTrackingTouch() =
                dispatcher.commitRelease(EditParameterKey.DETAIL_NOISE_REDUCTION)
        }
    }

    /** Shows the Pass C panel matching [style] and hides the other. */
    fun showFor(style: EditorControlStyle?) {
        val showSharpen = style == EditorControlStyle.SHARPEN
        val showNoiseReduction = style == EditorControlStyle.NOISE_REDUCTION
        panels.detailPanelsRoot.visibility =
            if (showSharpen || showNoiseReduction) View.VISIBLE else View.GONE
        panels.sharpenPanelView.visibility = if (showSharpen) View.VISIBLE else View.GONE
        panels.noiseReductionPanelView.visibility =
            if (showNoiseReduction) View.VISIBLE else View.GONE
    }

    /** Syncs both panels with [graph]. */
    fun refresh(graph: EditGraph) {
        panels.sharpenPanelView.setSharpenOps(graph.detail.sharpen)
        panels.noiseReductionPanelView.setNoiseReductionOps(graph.detail.nr)
    }
}
