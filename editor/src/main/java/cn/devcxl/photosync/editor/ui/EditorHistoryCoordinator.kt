package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshCodec
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.PresetGraphMerger

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

    /**
     * One entry on the shared timeline.
     *
     * [Param] and [Liquify] each move one of the two underlying histories; [ResetAll] is the only
     * composite step, because "back to the original" must restore the parametric graph **and** the
     * out-of-graph liquify grid with a single undo.
     */
    private sealed interface Step {
        object Param : Step
        object Liquify : Step

        /** @property liquifyCleared True when the reset also cleared a non-empty grid. */
        data class ResetAll(val liquifyCleared: Boolean) : Step
    }

    private val undoTimeline = ArrayDeque<Step>()
    private val redoTimeline = ArrayDeque<Step>()

    init {
        undoController.onStepPushed = {
            pushTimeline(undoTimeline, Step.Param)
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
        pushTimeline(undoTimeline, Step.Liquify)
        redoTimeline.clear()
        syncLiquifyRefInPlace()
    }

    fun requestLiquifyReset(): Boolean {
        if (!liquifySession.reset()) return false
        pushTimeline(undoTimeline, Step.Liquify)
        redoTimeline.clear()
        onMeshPublished()
        syncLiquifyRefInPlace()
        onStateChanged(undoController.currentGraph)
        return true
    }

    /**
     * Clears every adjustment — grade, portrait and frame geometry — back to the imported original.
     *
     * The liquify grid lives outside [EditGraph], so the reset clears the session mesh as well and
     * records both halves under one [Step.ResetAll]: a single undo restores the grid and the
     * geometry reference together, instead of requiring two.
     *
     * @return False when the photo is already untouched.
     */
    fun requestResetAll(): Boolean {
        val current = undoController.currentGraph
        val cleared = PresetGraphMerger.resetToOriginal(current)
        val liquifyCleared = liquifySession.hasMesh
        if (cleared == current && !liquifyCleared) return false
        if (liquifyCleared) liquifySession.reset()
        // The controller would push its own Param step; this reset owns exactly one composite step.
        val stepPushed = undoController.onStepPushed
        undoController.onStepPushed = null
        try {
            undoController.onParameterDrag(cleared, EditParameterKey.PRESET)
            undoController.onParameterRelease()
        } finally {
            undoController.onStepPushed = stepPushed
        }
        pushTimeline(undoTimeline, Step.ResetAll(liquifyCleared))
        redoTimeline.clear()
        onMeshPublished()
        syncLiquifyRefInPlace()
        onStateChanged(undoController.currentGraph)
        return true
    }

    fun undo() {
        val step = undoTimeline.removeLastOrNull() ?: return
        redoTimeline.addLast(step)
        when (step) {
            Step.Liquify -> {
                liquifySession.undo()
                onMeshPublished()
                syncLiquifyRefInPlace()
            }
            Step.Param -> undoController.undo()
            is Step.ResetAll -> {
                undoController.undo()
                if (step.liquifyCleared) liquifySession.undo()
                onMeshPublished()
                syncLiquifyRefInPlace()
            }
        }
        onStateChanged(undoController.currentGraph)
    }

    fun redo() {
        val step = redoTimeline.removeLastOrNull() ?: return
        undoTimeline.addLast(step)
        when (step) {
            Step.Liquify -> {
                liquifySession.redo()
                onMeshPublished()
                syncLiquifyRefInPlace()
            }
            Step.Param -> undoController.redo()
            is Step.ResetAll -> {
                undoController.redo()
                if (step.liquifyCleared) liquifySession.redo()
                onMeshPublished()
                syncLiquifyRefInPlace()
            }
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

    private fun pushTimeline(stack: ArrayDeque<Step>, step: Step) {
        if (stack.size >= EditorUndoController.DEFAULT_MAX_UNDO_DEPTH) {
            stack.removeFirst()
        }
        stack.addLast(step)
    }
}
