package cn.devcxl.photosync.editor.ui.panels

import android.view.View

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.HslOps
import cn.devcxl.photosync.editor.domain.SplitToningOps
import cn.devcxl.photosync.editor.ui.widgets.CurveEditorView
import cn.devcxl.photosync.editor.ui.widgets.HslPanelView
import cn.devcxl.photosync.editor.ui.widgets.SplitToningPanelView

/**
 * Binds the three Tier-1 Pass B2 color panels (`CurveEditorView`, `HslPanelView` and
 * `SplitToningPanelView`) to [EditorUndoController] so [EditorPanelController] stays concise.
 */
internal class ColorPanelsBinder(
    private val binding: ActivityEditorBinding,
    private val undoController: EditorUndoController,
    private val onEdited: (EditGraph, EditParameterKey) -> Unit,
    private val onCommitted: (EditGraph, EditParameterKey) -> Unit
) {

    private val panels = binding.colorPanels

    init {
        panels.curveEditorView.listener = object : CurveEditorView.Listener {
            override fun onCurveChanged(curve: CurveOps, fromUser: Boolean) {
                if (!fromUser) return
                val updated = undoController.currentGraph.let {
                    it.copy(color = it.color.copy(curve = curve))
                }
                val applied = undoController.onParameterDrag(updated, EditParameterKey.COLOR_CURVE)
                onEdited(applied, EditParameterKey.COLOR_CURVE)
            }

            override fun onStopTrackingTouch() {
                undoController.onParameterRelease()
                onCommitted(undoController.currentGraph, EditParameterKey.COLOR_CURVE)
            }
        }
        panels.hslPanelView.listener = object : HslPanelView.Listener {
            override fun onHslChanged(hsl: HslOps, fromUser: Boolean) {
                if (!fromUser) return
                val updated = undoController.currentGraph.let {
                    it.copy(color = it.color.copy(hsl = hsl))
                }
                val applied = undoController.onParameterDrag(updated, EditParameterKey.COLOR_HSL)
                onEdited(applied, EditParameterKey.COLOR_HSL)
            }

            override fun onStopTrackingTouch() {
                undoController.onParameterRelease()
                onCommitted(undoController.currentGraph, EditParameterKey.COLOR_HSL)
            }
        }
        panels.splitToningPanelView.listener = object : SplitToningPanelView.Listener {
            override fun onSplitToningChanged(split: SplitToningOps, fromUser: Boolean) {
                if (!fromUser) return
                val updated = undoController.currentGraph.let {
                    it.copy(color = it.color.copy(split = split))
                }
                val applied = undoController.onParameterDrag(updated, EditParameterKey.COLOR_SPLIT_TONING)
                onEdited(applied, EditParameterKey.COLOR_SPLIT_TONING)
            }

            override fun onStopTrackingTouch() {
                undoController.onParameterRelease()
                onCommitted(undoController.currentGraph, EditParameterKey.COLOR_SPLIT_TONING)
            }
        }
    }

    /** Shows the color panel matching [style] and hides the rest. */
    fun showFor(style: EditorControlStyle?) {
        val showCurve = style == EditorControlStyle.CURVE
        val showHsl = style == EditorControlStyle.HSL
        val showSplit = style == EditorControlStyle.SPLIT_TONING
        panels.colorPanelsRoot.visibility =
            if (showCurve || showHsl || showSplit) View.VISIBLE else View.GONE
        panels.curveEditorView.visibility = if (showCurve) View.VISIBLE else View.GONE
        panels.hslPanelView.visibility = if (showHsl) View.VISIBLE else View.GONE
        panels.splitToningPanelView.visibility = if (showSplit) View.VISIBLE else View.GONE
    }

    /** Syncs the three panels with [graph]. */
    fun refresh(graph: EditGraph) {
        panels.curveEditorView.setCurveOps(graph.color.curve)
        panels.hslPanelView.setHslOps(graph.color.hsl)
        panels.splitToningPanelView.setSplitToningOps(graph.color.split)
    }
}
