package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.render.SubjectComposite

/**
 * Turns a subject graph write into exactly one undo transaction.
 *
 * A mask-only change and a dial tick both have to look identical to history: one `AI_SUBJECT` step
 * that undo can revert in a single tap. Keeping the transaction in one class also guarantees the
 * staged composite and the stored cache are updated before the host repaints.
 */
internal class SubjectGraphCommitter(
    private val composites: SubjectCompositeRefresher,
    private val previewSession: EditorPreviewSession,
    private val undoController: EditorUndoController,
    private val onGraphCommitted: (EditGraph, EditParameterKey) -> Unit
) {

    /** Stages [composite] as the live canvas cut-out without closing an undo step. */
    fun stage(composite: SubjectComposite?) = previewSession.applySubjectComposite(composite)

    /** Commits [graph] and stages [composite] as the canvas cut-out. */
    fun commit(graph: EditGraph, composite: SubjectComposite?) {
        previewSession.applySubjectComposite(composite)
        if (composite != null) composites.store(graph.subject, composite) else composites.reset()
        val applied = undoController.onParameterDrag(graph, EditParameterKey.AI_SUBJECT)
        undoController.onParameterRelease()
        onGraphCommitted(applied, EditParameterKey.AI_SUBJECT)
    }

    /** Commits a dense graph change without touching the staged composite or its cache. */
    fun commitDiscrete(graph: EditGraph) {
        val applied = undoController.onParameterDrag(graph, EditParameterKey.AI_SUBJECT)
        undoController.onParameterRelease()
        onGraphCommitted(applied, EditParameterKey.AI_SUBJECT)
    }

    /** Commits one coalesced dial tick; the undo snapshot merges inside the 300 ms window. */
    fun commitGrading(graph: EditGraph) {
        val applied = undoController.onParameterDrag(graph, EditParameterKey.AI_SUBJECT)
        onGraphCommitted(applied, EditParameterKey.AI_SUBJECT)
    }

    /** Copies only the effect axes of [grading]; mask, mode and colour stay from [target]. */
    fun applyGrading(target: SubjectOps, grading: SubjectOps): SubjectOps = target.copy(
        blur = grading.blur,
        fgExposure = grading.fgExposure,
        fgTemp = grading.fgTemp,
        fgSat = grading.fgSat,
        bgExposure = grading.bgExposure,
        bgTemp = grading.bgTemp,
        bgSat = grading.bgSat
    )
}
