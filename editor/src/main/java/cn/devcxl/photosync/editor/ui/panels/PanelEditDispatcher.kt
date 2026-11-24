package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController

/**
 * Shared undo-coalescing dispatcher for the Tier-1 sub-panel binders (`ColorPanelsBinder`,
 * `DetailPanelsBinder`, `EffectsPanelsBinder`), removing duplicated drag/release boilerplate.
 */
internal class PanelEditDispatcher(
    private val undoController: EditorUndoController,
    private val onEdited: (EditGraph, EditParameterKey) -> Unit,
    private val onCommitted: (EditGraph, EditParameterKey) -> Unit
) {

    /** Applies [transform] inside [key]'s rolling undo window and notifies the preview host. */
    fun pushChange(key: EditParameterKey, transform: (EditGraph) -> EditGraph) {
        val applied = undoController.onParameterDrag(transform(undoController.currentGraph), key)
        onEdited(applied, key)
    }

    /** Seals [key]'s active undo transaction on finger lift. */
    fun commitRelease(key: EditParameterKey) {
        undoController.onParameterRelease()
        onCommitted(undoController.currentGraph, key)
    }
}
