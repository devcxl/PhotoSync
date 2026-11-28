package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the `graph.inpaint` list as an undo axis.
 *
 * Every confirmed erase appends its ordered patch refs as **one** transaction, so undo must step
 * back one erase at a time (not the whole list), redo must restore exactly the same refs, and two
 * runs that land inside the 300ms coalescing window must still be two separate steps.
 */
class EditorUndoControllerInpaintTest {

    @Test
    fun shouldUndoOneErasePerStep_whenPatchesWereAppendedRunByRun() {
        val controller = EditorUndoController()
        val first = patch("a")
        val second = patch("b")

        commitErase(controller, listOf(first))
        commitErase(controller, listOf(first, second))

        assertEquals(2, controller.undoDepth)
        assertEquals(listOf(first, second), controller.currentGraph.inpaint)
        assertFalse("a repaired graph is not the neutral graph", controller.currentGraph.isIdentity())

        controller.undo()
        assertEquals(listOf(first), controller.currentGraph.inpaint)
        controller.undo()
        assertTrue(controller.currentGraph.inpaint.isEmpty())
        assertFalse(controller.canUndo)
    }

    @Test
    fun shouldRestoreTheSamePatchRefs_whenRedoingAnUndoneErase() {
        val controller = EditorUndoController()
        val first = patch("a")
        val second = patch("b")
        commitErase(controller, listOf(first))
        commitErase(controller, listOf(first, second))
        controller.undo()
        controller.undo()

        controller.redo()
        assertEquals(listOf(first), controller.currentGraph.inpaint)
        controller.redo()
        assertEquals(listOf(first, second), controller.currentGraph.inpaint)
        assertFalse(controller.canRedo)
    }

    @Test
    fun shouldPushTwoSteps_whenBothRunsLandInsideTheCoalescingWindow() {
        val controller = EditorUndoController()
        val first = patch("a")
        val second = patch("b")
        val timestamp = 1_000L

        // Same timestamp models the worst case: both confirmations inside one coalescing window.
        controller.onParameterRelease()
        controller.onParameterDrag(
            EditGraph(inpaint = listOf(first)),
            EditParameterKey.AI_INPAINT,
            timestampMs = timestamp
        )
        controller.onParameterRelease()
        controller.onParameterDrag(
            EditGraph(inpaint = listOf(first, second)),
            EditParameterKey.AI_INPAINT,
            timestampMs = timestamp
        )
        controller.onParameterRelease()

        assertEquals(2, controller.undoDepth)
        controller.undo()
        assertEquals(listOf(first), controller.currentGraph.inpaint)
    }

    @Test
    fun shouldKeepEveryPatch_whenTheRepairedGraphIsSanitized() {
        val graph = EditGraph(inpaint = listOf(patch("a"), patch("b")))

        val sanitized = EditValidator.sanitize(graph)

        assertEquals(graph.inpaint, sanitized.inpaint)
        assertEquals(EditGraph.CURRENT_SCHEMA_VERSION, sanitized.schema)
    }

    private fun commitErase(controller: EditorUndoController, patches: List<InpaintPatchRef>) {
        // Mirrors EditorInpaintController.commitPatches: one erase is always its own undo step.
        controller.onParameterRelease()
        controller.onParameterDrag(EditGraph(inpaint = patches), EditParameterKey.AI_INPAINT)
        controller.onParameterRelease()
    }

    private fun patch(id: String): InpaintPatchRef = InpaintPatchRef(
        bbox = InpaintBBox(0.2f, 0.2f, 0.6f, 0.6f),
        patchRelPath = "edits/3/inpaint_patch_$id.png",
        maskRelPath = "edits/3/inpaint_mask_$id.png",
        sha256 = ""
    )
}
