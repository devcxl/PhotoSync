package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshCodec
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.LiquifySession

/**
 * Wires the liquify stroke history to the parametric `EditGraph` history.
 *
 * Two stacks cooperate instead of one: strokes are byte-for-byte mesh snapshots, parameters are
 * immutable [EditGraph] snapshots. This coordinator keeps the graph's `geometry.liquify` reference
 * truthful — present while a mesh exists, absent the moment the last displacement is undone or reset
 * — so the chip badge, preview cache and export all agree, and it tells the host when the mesh it
 * renders changed.
 */
internal class EditorLiquifyController(
    private val photoId: Long,
    private val undoController: EditorUndoController,
    private val session: LiquifySession,
    private val onMeshPublished: () -> Unit,
    private val onGraphChanged: () -> Unit
) {

    /** True when a liquify stroke can be undone. */
    val canUndo: Boolean
        get() = session.canUndo

    /** True when a liquify stroke can be re-applied. */
    val canRedo: Boolean
        get() = session.canRedo

    /** Records the mesh reference after a committed stroke. */
    fun onStrokeCommitted() {
        syncLiquifyRef()
    }

    /**
     * Clears every displacement as one undoable step.
     *
     * @return False when the mesh was already empty.
     */
    fun requestReset(): Boolean {
        if (!session.reset()) return false
        afterMeshMoved()
        return true
    }

    /**
     * Reverts one stroke.
     *
     * @return False when the mesh history is empty, so the caller falls back to the graph history.
     */
    fun undo(): Boolean {
        if (!session.undo()) return false
        afterMeshMoved()
        return true
    }

    /**
     * Re-applies one undone stroke.
     *
     * @return False when there is nothing to redo.
     */
    fun redo(): Boolean {
        if (!session.redo()) return false
        afterMeshMoved()
        return true
    }

    private fun afterMeshMoved() {
        onMeshPublished()
        syncLiquifyRef()
        onGraphChanged()
    }

    /**
     * Pins the photo's mesh payload into `geometry.liquify`, or clears it once the grid is empty.
     *
     * The SHA-256 stays blank here; [EditorSaveCoordinator] fills it in when it writes the file.
     */
    private fun syncLiquifyRef() {
        val current = undoController.currentGraph
        val desired = if (session.hasMesh) {
            LiquifyRef(
                meshRelPath = LiquifyMeshCodec.persistedRelativePath(photoId),
                gridSize = LiquifyMesh.GRID_SIZE
            )
        } else {
            null
        }
        if (current.geometry.liquify == desired) return
        undoController.onParameterDrag(
            current.copy(geometry = current.geometry.copy(liquify = desired)),
            EditParameterKey.GEOMETRY_LIQUIFY
        )
        undoController.onParameterRelease()
    }
}
