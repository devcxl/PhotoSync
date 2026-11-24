package cn.devcxl.photosync.editor.ui.panels

import android.view.View

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.BodyOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.ReshapeOps
import cn.devcxl.photosync.editor.domain.SkinOps
import cn.devcxl.photosync.editor.ui.widgets.BodyReshapePanelView
import cn.devcxl.photosync.editor.ui.widgets.FaceReshapePanelView
import cn.devcxl.photosync.editor.ui.widgets.SkinPanelView

/**
 * Binds the three Portrait panels (`SkinPanelView`, `FaceReshapePanelView`, `BodyReshapePanelView`)
 * to [EditorUndoController], so [EditorPanelController] stays a thin router.
 */
internal class PortraitPanelsBinder(
    binding: ActivityEditorBinding,
    undoController: EditorUndoController,
    onEdited: (EditGraph, EditParameterKey) -> Unit,
    onCommitted: (EditGraph, EditParameterKey) -> Unit
) {

    private val panels = binding.portraitPanels
    private val dispatcher = PanelEditDispatcher(undoController, onEdited, onCommitted)

    init {
        panels.skinPanelView.listener = object : SkinPanelView.Listener {
            override fun onSkinChanged(skin: SkinOps) =
                dispatcher.pushChange(EditParameterKey.PORTRAIT_SKIN) {
                    it.copy(portrait = it.portrait.copy(skin = skin))
                }

            override fun onStopTrackingTouch() =
                dispatcher.commitRelease(EditParameterKey.PORTRAIT_SKIN)
        }
        panels.faceReshapePanelView.listener = object : FaceReshapePanelView.Listener {
            override fun onReshapeChanged(reshape: ReshapeOps) =
                dispatcher.pushChange(EditParameterKey.PORTRAIT_RESHAPE) {
                    it.copy(portrait = it.portrait.copy(reshape = reshape))
                }

            override fun onStopTrackingTouch() =
                dispatcher.commitRelease(EditParameterKey.PORTRAIT_RESHAPE)
        }
        panels.bodyReshapePanelView.listener = object : BodyReshapePanelView.Listener {
            override fun onBodyChanged(body: BodyOps) =
                dispatcher.pushChange(EditParameterKey.PORTRAIT_BODY) {
                    it.copy(portrait = it.portrait.copy(body = body))
                }

            override fun onStopTrackingTouch() =
                dispatcher.commitRelease(EditParameterKey.PORTRAIT_BODY)
        }
    }

    /** Shows the Portrait panel matching [style] and hides the others. */
    fun showFor(style: EditorControlStyle?) {
        val showSkin = style == EditorControlStyle.SKIN
        val showFace = style == EditorControlStyle.FACE_RESHAPE
        val showBody = style == EditorControlStyle.BODY_RESHAPE
        panels.portraitPanelsRoot.visibility =
            if (showSkin || showFace || showBody) View.VISIBLE else View.GONE
        panels.skinPanelView.visibility = if (showSkin) View.VISIBLE else View.GONE
        panels.faceReshapePanelView.visibility = if (showFace) View.VISIBLE else View.GONE
        panels.bodyReshapePanelView.visibility = if (showBody) View.VISIBLE else View.GONE
    }

    /** Syncs all three panels with [graph]. */
    fun refresh(graph: EditGraph) {
        panels.skinPanelView.setSkinOps(graph.portrait.skin)
        panels.faceReshapePanelView.setReshapeOps(graph.portrait.reshape)
        panels.bodyReshapePanelView.setBodyOps(graph.portrait.body)
    }
}
