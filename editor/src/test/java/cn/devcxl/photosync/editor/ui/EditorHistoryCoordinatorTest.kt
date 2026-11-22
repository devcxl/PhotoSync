package cn.devcxl.photosync.editor.ui

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.Uv

class EditorHistoryCoordinatorTest {

    @Test
    fun shouldUndoAndRedoChronologicallyAcrossParamAndLiquifySteps_whenToolsAreInterleaved() {
        val undoController = EditorUndoController()
        val session = LiquifySession(42L, LiquifyMeshStore(File("/tmp/opencode/unused_history_test")))
        var meshPublishedCount = 0
        val coordinator = EditorHistoryCoordinator(
            photoId = 42L,
            undoController = undoController,
            liquifySession = session,
            onMeshPublished = { meshPublishedCount++ },
            onStateChanged = {}
        )

        // 1. Adjust Exposure (+1.0 EV)
        val exposed = EditGraph().copy(tone = EditGraph().tone.copy(exposure = 1.0f))
        undoController.onParameterDrag(exposed, EditParameterKey.TONE_EXPOSURE, timestampMs = 100L)
        undoController.onParameterRelease()

        // 2. Paint a liquify stroke
        session.beginStroke()
        session.pushStroke(Uv(0.4f, 0.5f), Uv(0.55f, 0.5f), radiusNorm = 0.1f, frameAspect = 1.5f)
        session.commitStroke()
        coordinator.onLiquifyStrokeCommitted()

        // 3. Adjust Contrast (+25)
        val contrasted = undoController.currentGraph.copy(
            tone = undoController.currentGraph.tone.copy(contrast = 25)
        )
        undoController.onParameterDrag(contrasted, EditParameterKey.TONE_CONTRAST, timestampMs = 500L)
        undoController.onParameterRelease()

        assertEquals(25, undoController.currentGraph.tone.contrast)
        assertNotNull(undoController.currentGraph.geometry.liquify)
        assertTrue(session.hasMesh)

        // Undo 1 -> removes Contrast, keeps Liquify and Exposure
        coordinator.undo()
        assertEquals(0, undoController.currentGraph.tone.contrast)
        assertEquals(1.0f, undoController.currentGraph.tone.exposure, 1e-4f)
        assertTrue(session.hasMesh)
        assertNotNull(undoController.currentGraph.geometry.liquify)

        // Undo 2 -> removes Liquify stroke regardless of active tab, keeps Exposure
        coordinator.undo()
        assertFalse(session.hasMesh)
        assertNull(undoController.currentGraph.geometry.liquify)
        assertEquals(1.0f, undoController.currentGraph.tone.exposure, 1e-4f)

        // Undo 3 -> removes Exposure
        coordinator.undo()
        assertEquals(0.0f, undoController.currentGraph.tone.exposure, 1e-4f)
        assertFalse(coordinator.canUndo)

        // Redo back through Exposure -> Liquify -> Contrast
        coordinator.redo()
        assertEquals(1.0f, undoController.currentGraph.tone.exposure, 1e-4f)
        assertFalse(session.hasMesh)

        coordinator.redo()
        assertTrue(session.hasMesh)
        assertNotNull(undoController.currentGraph.geometry.liquify)

        coordinator.redo()
        assertEquals(25, undoController.currentGraph.tone.contrast)
        assertTrue(meshPublishedCount > 0)
    }
}
