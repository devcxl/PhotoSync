package cn.devcxl.photosync.editor.ui.panels

import android.view.View

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.GrainOps
import cn.devcxl.photosync.editor.domain.LutRef
import cn.devcxl.photosync.editor.domain.VignetteOps
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.ui.widgets.GrainPanelView
import cn.devcxl.photosync.editor.ui.widgets.LutPanelView
import cn.devcxl.photosync.editor.ui.widgets.VignettePanelView

/**
 * Binds the Pass D Tier-1 panels (`LutPanelView`, `GrainPanelView` and `VignettePanelView`) to
 * [EditorUndoController] so [EditorPanelController] stays concise.
 */
internal class EffectsPanelsBinder(
    binding: ActivityEditorBinding,
    undoController: EditorUndoController,
    private val lutStore: CubeLutStore,
    private val onImportRequested: () -> Unit,
    onEdited: (EditGraph, EditParameterKey) -> Unit,
    onCommitted: (EditGraph, EditParameterKey) -> Unit
) {

    private val panels = binding.effectPanels
    private val dispatcher = PanelEditDispatcher(undoController, onEdited, onCommitted)

    init {
        panels.lutPanelView.listener = object : LutPanelView.Listener {
            override fun onLutImportRequested() = onImportRequested()

            override fun onLutSelected(id: String?) =
                dispatcher.pushChange(EditParameterKey.COLOR_LUT) { graph ->
                    val currentStrength = graph.color.lut?.strength ?: DEFAULT_LUT_STRENGTH
                    graph.copy(color = graph.color.copy(lut = id?.let { LutRef(it, currentStrength) }))
                }

            override fun onStrengthChanged(strength: Int) =
                dispatcher.pushChange(EditParameterKey.COLOR_LUT) { graph ->
                    val current = graph.color.lut ?: return@pushChange graph
                    graph.copy(color = graph.color.copy(lut = current.copy(strength = strength)))
                }

            override fun onStopTrackingTouch() = dispatcher.commitRelease(EditParameterKey.COLOR_LUT)
        }
        panels.grainPanelView.listener = object : GrainPanelView.Listener {
            override fun onGrainChanged(grain: GrainOps) =
                dispatcher.pushChange(EditParameterKey.DETAIL_GRAIN) {
                    it.copy(effects = it.effects.copy(grain = grain))
                }

            override fun onStopTrackingTouch() = dispatcher.commitRelease(EditParameterKey.DETAIL_GRAIN)
        }
        panels.vignettePanelView.listener = object : VignettePanelView.Listener {
            override fun onVignetteChanged(vignette: VignetteOps) =
                dispatcher.pushChange(EditParameterKey.DETAIL_VIGNETTE) {
                    it.copy(effects = it.effects.copy(vignette = vignette))
                }

            override fun onStopTrackingTouch() = dispatcher.commitRelease(EditParameterKey.DETAIL_VIGNETTE)
        }
    }

    /** Shows the effect panel matching [style] and hides the rest. */
    fun showFor(style: EditorControlStyle?) {
        val showLut = style == EditorControlStyle.LUT
        val showGrain = style == EditorControlStyle.GRAIN
        val showVignette = style == EditorControlStyle.VIGNETTE
        panels.effectPanelsRoot.visibility =
            if (showLut || showGrain || showVignette) View.VISIBLE else View.GONE
        panels.lutPanelView.visibility = if (showLut) View.VISIBLE else View.GONE
        panels.grainPanelView.visibility = if (showGrain) View.VISIBLE else View.GONE
        panels.vignettePanelView.visibility = if (showVignette) View.VISIBLE else View.GONE
    }

    /** Syncs the three panels, including the installed LUT chips, with [graph]. */
    fun refresh(graph: EditGraph) {
        panels.lutPanelView.setInstalledLuts(lutStore.installedLuts())
        panels.lutPanelView.setLut(graph.color.lut)
        panels.grainPanelView.setGrainOps(graph.effects.grain)
        panels.vignettePanelView.setVignetteOps(graph.effects.vignette)
    }

    private companion object {
        private const val DEFAULT_LUT_STRENGTH = 100
    }
}
