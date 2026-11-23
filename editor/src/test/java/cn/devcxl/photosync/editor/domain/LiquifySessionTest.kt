package cn.devcxl.photosync.editor.domain

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the liquify session state machine — one undo step per committed stroke and a
 * byte-exact rollback when a two-finger gesture preempts a stroke that never left the slop circle.
 *
 * The session is constructed with a store whose directory is never touched: every case here is pure
 * in-memory state, so the test stays a JVM unit test with no file I/O.
 */
class LiquifySessionTest {

    private val unusedStore = LiquifyMeshStore(File("build/liquify-session-unused"))

    @Test
    fun shouldRestoreThePreStrokeMesh_whenATwoFingerPreemptionCancelsTheStroke() {
        val session = LiquifySession(unusedStore)
        session.beginStroke()
        session.pushStroke(Uv(0.50f, 0.50f), Uv(0.56f, 0.50f), RADIUS, frameAspect = 1.0f)
        assertTrue(session.commitStroke())
        val committedDisplacement = session.mesh.displacementAt(0.56f, 0.50f).u
        assertTrue(committedDisplacement < -0.01f)

        session.beginStroke()
        session.pushStroke(Uv(0.50f, 0.50f), Uv(0.52f, 0.50f), RADIUS, frameAspect = 1.0f)
        session.cancelStroke()

        assertEquals(
            "the rolled-back stroke must not leave any displacement behind",
            committedDisplacement,
            session.mesh.displacementAt(0.56f, 0.50f).u,
            0.0f
        )
    }

    @Test
    fun shouldRecordExactlyOneUndoStep_whenAStrokeIsCommitted() {
        val session = LiquifySession(unusedStore)
        session.beginStroke()
        repeat(40) { step ->
            session.pushStroke(
                prevUv = Uv(0.30f + step * 0.005f, 0.50f),
                nextUv = Uv(0.305f + step * 0.005f, 0.50f),
                radiusNorm = RADIUS,
                frameAspect = 1.5f
            )
        }
        assertTrue(session.commitStroke())

        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertTrue("the whole stroke must collapse into one step", session.undo())
        assertFalse(session.canUndo)
        assertTrue(session.canRedo)
        assertTrue("undoing the only stroke must return to an untouched mesh", session.mesh.isZero())
    }

    @Test
    fun shouldNotRecordAStep_whenTheStrokeDidNotMove() {
        val session = LiquifySession(unusedStore)
        session.beginStroke()

        assertFalse(session.commitStroke())
        assertFalse(session.canUndo)
        assertFalse(session.hasMesh)
    }

    @Test
    fun shouldBeUndoableAndClearTheMesh_whenResetIsApplied() {
        val session = LiquifySession(unusedStore)
        session.beginStroke()
        session.pushStroke(Uv(0.5f, 0.5f), Uv(0.58f, 0.5f), RADIUS, frameAspect = 1.0f)
        session.commitStroke()

        assertTrue(session.reset())
        assertTrue(session.mesh.isZero())
        assertTrue("reset must be undoable", session.canUndo)
        assertTrue(session.undo())
        assertTrue(session.hasMesh)
    }

    @Test
    fun shouldClampBrushSettingsIntoTheirLegalRanges_whenThePanelSendsOutOfBoundsValues() {
        val session = LiquifySession(unusedStore)

        val tooLarge = session.updateSettings { it.withSizeNorm(9.0f).withStrength(4.0f) }
        assertEquals(LiquifyBrushSettings.MAX_SIZE_NORM, tooLarge.sizeNorm, 1e-6f)
        assertEquals(1.0f, tooLarge.strength, 1e-6f)

        val tooSmall = session.updateSettings { it.withSizeNorm(0.0f).withStrength(-1.0f) }
        assertEquals(LiquifyBrushSettings.MIN_SIZE_NORM, tooSmall.sizeNorm, 1e-6f)
        assertEquals(0.0f, tooSmall.strength, 1e-6f)
    }

    private companion object {
        const val RADIUS = 0.08f
    }
}
