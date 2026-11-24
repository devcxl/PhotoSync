package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the RAW `BaseLook` toggle wiring into the undo graph: switching between
 * `camera_default` and `linear_neutral` is a single undoable edit, and it must survive a
 * JSON round trip so reopening the editor restores the chosen baseline.
 */
class BaseLookToggleTest {

    @Test
    fun shouldRecordSingleUndoStep_whenBaseLookIsToggledOnce() {
        val controller = EditorUndoController()
        assertEquals(BaseLook.CAMERA_DEFAULT, controller.currentGraph.baseLook)

        val toggled = controller.currentGraph.copy(baseLook = BaseLook.LINEAR_NEUTRAL)
        controller.onParameterDrag(toggled, EditParameterKey.BASE_LOOK, timestampMs = 1_000L)
        controller.onParameterRelease()

        assertEquals(BaseLook.LINEAR_NEUTRAL, controller.currentGraph.baseLook)
        assertEquals(1, controller.undoDepth)

        val reverted = controller.undo()
        assertEquals(BaseLook.CAMERA_DEFAULT, reverted.baseLook)
    }

    @Test
    fun shouldCoalesceRepeatedTogglesIntoSingleUndoStep_whenTappedTwiceQuickly() {
        val controller = EditorUndoController()

        controller.onParameterDrag(
            newGraph = controller.currentGraph.copy(baseLook = BaseLook.LINEAR_NEUTRAL),
            parameterKey = EditParameterKey.BASE_LOOK,
            timestampMs = 1_000L
        )
        controller.onParameterDrag(
            newGraph = controller.currentGraph.copy(baseLook = BaseLook.CAMERA_DEFAULT),
            parameterKey = EditParameterKey.BASE_LOOK,
            timestampMs = 1_120L
        )
        controller.onParameterRelease()

        assertEquals(1, controller.undoDepth)
        assertEquals(BaseLook.CAMERA_DEFAULT, controller.currentGraph.baseLook)
    }

    @Test
    fun shouldPersistBaseLookAcrossJsonRoundTrip_whenLinearNeutralIsSelected() {
        val graph = EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL)
        val decoded = EditGraphJsonCodec.decode(EditGraphJsonCodec.encode(graph))

        assertEquals(BaseLook.LINEAR_NEUTRAL, decoded?.baseLook)
        assertFalse(decoded?.isIdentity() ?: true)
    }

    @Test
    fun shouldMarkRepeatedChangeAsNoOp_whenBaseLookAlreadySelected() {
        val controller = EditorUndoController()
        val unchanged = controller.onParameterDrag(
            newGraph = controller.currentGraph,
            parameterKey = EditParameterKey.BASE_LOOK,
            timestampMs = 1_000L
        )

        assertEquals(controller.currentGraph, unchanged)
        assertTrue(controller.undoDepth == 0)
    }
}
