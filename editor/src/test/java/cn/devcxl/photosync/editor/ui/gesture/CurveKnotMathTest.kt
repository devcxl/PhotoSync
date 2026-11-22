package cn.devcxl.photosync.editor.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.CurvePoint
import cn.devcxl.photosync.editor.domain.MonotoneCubicCurve

/**
 * Seam under test: [CurveKnotMath] — interactive curve knot insertion, neighbor spacing clamping,
 * and drag-below-grid deletion with a 2-knot floor.
 */
class CurveKnotMathTest {

    @Test
    fun shouldInsertKnotSortedByXAndRejectCloserThanMinSpacing_whenAddingKnots() {
        val initial = CurveOps.DEFAULT_LINEAR_CURVE

        val inserted = CurveKnotMath.insertKnot(initial, 0.4f, 0.6f)
        assertNotNull(inserted)
        val (updated, index) = inserted!!
        assertEquals(3, updated.size)
        assertEquals(1, index)
        assertEquals(0.4f, updated[1].x, 1e-6f)
        assertEquals(0.6f, updated[1].y, 1e-6f)

        val tooClose = CurveKnotMath.insertKnot(updated, 0.4f + MonotoneCubicCurve.MIN_SPACING * 0.5f, 0.7f)
        assertNull("must reject knot closer than 1/256", tooClose)
    }

    @Test
    fun shouldClampDraggedKnotStrictlyBetweenNeighbors_whenMovingKnot() {
        val points = listOf(
            CurvePoint(0.0f, 0.0f),
            CurvePoint(0.4f, 0.5f),
            CurvePoint(0.7f, 0.8f),
            CurvePoint(1.0f, 1.0f)
        )

        val movedPastRight = CurveKnotMath.moveKnot(points, 1, 0.9f, 0.6f)
        assertTrue(movedPastRight[1].x <= 0.7f - MonotoneCubicCurve.MIN_SPACING + 1e-6f)

        val movedPastLeft = CurveKnotMath.moveKnot(points, 1, 0.0f, 0.6f)
        assertTrue(movedPastLeft[1].x >= MonotoneCubicCurve.MIN_SPACING - 1e-6f)
    }

    @Test
    fun shouldDeleteInteriorKnotAndPreserveTwoKnotMinimum_whenDeletingKnots() {
        val three = listOf(
            CurvePoint(0.0f, 0.0f),
            CurvePoint(0.5f, 0.65f),
            CurvePoint(1.0f, 1.0f)
        )
        val afterFirstDelete = CurveKnotMath.deleteKnot(three, 1)
        assertEquals(2, afterFirstDelete.size)

        val afterFloorDelete = CurveKnotMath.deleteKnot(afterFirstDelete, 0)
        assertEquals(2, afterFloorDelete.size)
    }
}
