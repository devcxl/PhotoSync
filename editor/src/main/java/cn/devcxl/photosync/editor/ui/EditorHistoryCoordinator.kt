package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshCodec
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.LiquifySession

/**
 * Coordinates the parametric [EditorUndoController] and stroke-based [LiquifySession] under a
 * single chronological undo/redo timeline so undoing works in exact reverse edit order across tabs.
 */
internal class EditorHistoryCoordinator(
    private val photoId: Long,
    private val undoController: EditorUndoController,
    private val liquifySession: LiquifySession,
    private val onMeshPublished: () -> Unit,
    private val onStateChanged: (EditGraph) -> Unit
) {
    private enum class StepKind {
        PARAM,
        LIQUIFY
    }

    private val undoTimeline = ArrayDeque<StepKind>()
    private val redoTimeline = ArrayDeque<StepKind>()

    init {
        undoController.onStepPushed = {
            pushTimeline(undoTimeline, StepKind.PARAM)
            redoTimeline.clear()
        }
    }

    val canUndo: Boolean
        get() = undoTimeline.isNotEmpty() || undoController.canUndo || liquifySession.canUndo

    val canRedo: Boolean
        get() = redoTimeline.isNotEmpty() || undoController.canRedo || liquifySession.canRedo

    fun resetSession(graph: EditGraph) {
        undoTimeline.clear()
        redoTimeline.clear()
        undoController.resetTo(graph)
    }

    fun onLiquifyStrokeCommitted() {
        pushTimeline(undoTimeline, StepKind.LIQUIFY)
        redoTimeline.clear()
        syncLiquifyRefInPlace()
    }

    fun requestLiquifyReset(): Boolean {
        if (!liquifySession.reset()) return false
        pushTimeline(undoTimeline, StepKind.LIQUIFY)
        redoTimeline.clear()
        onMeshPublished()
        syncLiquifyRefInPlace()
        onStateChanged(undoController.currentGraph)
        return true
    }

    fun undo() {
        val kind = undoTimeline.removeLastOrNull() ?: return
        redoTimeline.addLast(kind)
        when (kind) {
            StepKind.LIQUIFY -> {
                liquifySession.undo()
                onMeshPublished()
                syncLiquifyRefInPlace()
            }
            StepKind.PARAM -> undoController.undo()
        }
        onStateChanged(undoController.currentGraph)
    }

    fun redo() {
        val kind = redoTimeline.removeLastOrNull() ?: return
        undoTimeline.addLast(kind)
        when (kind) {
            StepKind.LIQUIFY -> {
                liquifySession.redo()
                onMeshPublished()
                syncLiquifyRefInPlace()
            }
            StepKind.PARAM -> undoController.redo()
        }
        onStateChanged(undoController.currentGraph)
    }

    private fun syncLiquifyRefInPlace() {
        val desired = if (liquifySession.hasMesh) {
            LiquifyRef(
                meshRelPath = LiquifyMeshCodec.persistedRelativePath(photoId),
                gridSize = LiquifyMesh.GRID_SIZE
            )
        } else {
            null
        }
        undoController.replaceLiquifyRefInPlace(desired)
    }

    private fun pushTimeline(stack: ArrayDeque<StepKind>, step: StepKind) {
        if (stack.size >= EditorUndoController.DEFAULT_MAX_UNDO_DEPTH) {
            stack.removeFirst()
        }
        stack.addLast(step)
    }
}
