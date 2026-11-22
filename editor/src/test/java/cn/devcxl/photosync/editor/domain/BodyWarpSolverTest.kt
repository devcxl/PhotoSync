package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.hypot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [BodyWarpSolver] — the waist pinch and leg stretch contract, judged through the
 * analytic displacement field the grid is sampled from.
 */
class BodyWarpSolverTest {

    @Test
    fun shouldLeaveBackgroundUntouched_whenTheBodyIsReshaped() {
        val mask = personColumnMask()
        val guides = BodyGuides(waistY = 0.5f, hipY = 0.6f, centreX = 0.5f)

        val inside = BodyWarpSolver.displacementAt(1.0f, 1.0f, guides, mask, 0.48f, 0.52f)
        val background = BodyWarpSolver.displacementAt(1.0f, 1.0f, guides, mask, 0.08f, 0.9f)

        assertTrue(hypot(inside.u, inside.v) > 1e-4f)
        assertEquals(0.0f, background.u, 1e-6f)
        assertEquals(0.0f, background.v, 1e-6f)
    }

    @Test
    fun shouldStayContinuousAndFlatAtTheHipLine_whenTheLegsAreLengthened() {
        val guides = BodyGuides(waistY = 0.45f, hipY = 0.6f, centreX = 0.5f)
        val step = 1e-4f

        val above = BodyWarpSolver.displacementAt(0.0f, 1.0f, guides, null, 0.5f, guides.hipY - step)
        val atLine = BodyWarpSolver.displacementAt(0.0f, 1.0f, guides, null, 0.5f, guides.hipY)
        val below = BodyWarpSolver.displacementAt(0.0f, 1.0f, guides, null, 0.5f, guides.hipY + step)
        val slopeAbove = (atLine.v - above.v) / step
        val slopeBelow = (below.v - atLine.v) / step

        assertEquals(0.0f, above.v, 1e-6f)
        assertEquals(0.0f, atLine.v, 1e-6f)
        assertTrue("the leg stretch must pull the sample upwards", below.v < 0.0f)
        assertEquals("the vertical slope must be continuous at the hip line", 0.0f, slopeAbove, 1e-3f)
        assertEquals("the vertical slope must be continuous at the hip line", 0.0f, slopeBelow, 1e-3f)
    }

    @Test
    fun shouldLengthenOnlyTheLowerBody_whenTheLegSliderIsPositive() {
        val guides = BodyGuides(waistY = 0.45f, hipY = 0.6f, centreX = 0.5f)

        val knee = BodyWarpSolver.displacementAt(0.0f, 1.0f, guides, null, 0.5f, 0.8f)
        val ankle = BodyWarpSolver.displacementAt(0.0f, 1.0f, guides, null, 0.5f, 0.99f)
        val expectedKnee = -BodyWarpSolver.LEG_STRETCH_GAIN * (0.8f - 0.6f) * (0.8f - 0.6f) / (1.0f - 0.6f)
        val expectedAnkle = -BodyWarpSolver.LEG_STRETCH_GAIN * 0.39f * 0.39f / (1.0f - 0.6f)

        assertEquals(expectedKnee, knee.v, 1e-5f)
        assertEquals(expectedAnkle, ankle.v, 1e-5f)
        assertTrue(abs(ankle.v) > abs(knee.v))
        assertEquals(0.0f, knee.u, 1e-6f)
    }

    @Test
    fun shouldKeepTheFrameAspectUnchanged_whenOnlyTheLegsAreStretched() {
        val guides = BodyGuides(waistY = 0.45f, hipY = 0.6f, centreX = 0.5f)

        val grid = BodyWarpSolver.solve(0.0f, 1.0f, guides, null)

        assertEquals(BodyWarpSolver.GRID_SIZE * BodyWarpSolver.GRID_SIZE * 2, grid.size)
        for (cell in 0 until BodyWarpSolver.GRID_SIZE * BodyWarpSolver.GRID_SIZE) {
            assertEquals(0.0f, grid[cell * 2], 1e-6f)
            assertTrue(grid[cell * 2 + 1] <= 0.0f)
        }
    }

    @Test
    fun shouldPinchTowardsThePersonCentroid_whenTheWaistIsSlimmed() {
        val mask = personColumnMask(centreU = 0.4f)
        val guides = BodyWarpSolver.resolveGuides(null, mask)

        val right = BodyWarpSolver.displacementAt(
            1.0f, 0.0f, guides, mask, guides.centreX + 0.05f, guides.waistY
        )
        val left = BodyWarpSolver.displacementAt(
            1.0f, 0.0f, guides, mask, guides.centreX - 0.05f, guides.waistY
        )

        assertTrue("the right flank must move towards the centre", right.u < 0.0f)
        assertTrue("the left flank must move towards the centre", left.u > 0.0f)
        assertEquals("the pinch must be symmetric", -right.u, left.u, 1e-5f)
    }

    @Test
    fun shouldDeriveGuidesFromTheFace_whenAFaceIsDetected() {
        val face = FaceMeshFixtures.face(centreU = 0.5f, centreV = 0.3f, width = 0.16f, height = 0.1f)

        val guides = BodyWarpSolver.resolveGuides(face, personColumnMask())

        assertEquals(0.3f + BodyWarpSolver.WAIST_FACE_HEIGHT_FACTOR * 0.1f, guides.waistY, 1e-4f)
        assertEquals(0.3f + BodyWarpSolver.HIP_FACE_HEIGHT_FACTOR * 0.1f, guides.hipY, 1e-4f)
        assertEquals(0.4f, guides.centreX, 2e-2f)
    }

    @Test
    fun shouldPersistUserGuideOverrides_overTheDerivedDefaults() {
        val face = FaceMeshFixtures.face(centreU = 0.5f, centreV = 0.3f, width = 0.16f, height = 0.1f)

        val guides = BodyWarpSolver.resolveGuides(
            face,
            null,
            BodyOps(waist = 20, legs = 10, waistY = 0.42f, hipY = 0.58f)
        )

        assertEquals(0.42f, guides.waistY, 1e-6f)
        assertEquals(0.58f, guides.hipY, 1e-6f)
    }

    @Test
    fun shouldFallBackToThePersonBounds_whenNoFaceIsDetected() {
        val mask = FloatArray(BodyWarpSolver.MASK_SIZE * BodyWarpSolver.MASK_SIZE)
        val topRow = (0.2f * BodyWarpSolver.MASK_SIZE).toInt()
        val bottomRow = (0.8f * BodyWarpSolver.MASK_SIZE).toInt()
        for (row in topRow until bottomRow) {
            for (column in 0 until BodyWarpSolver.MASK_SIZE) {
                mask[row * BodyWarpSolver.MASK_SIZE + column] = 0.9f
            }
        }

        val guides = BodyWarpSolver.resolveGuides(null, mask)

        assertEquals(0.2f + BodyWarpSolver.PERSON_WAIST_FRACTION * 0.6f, guides.waistY, 5e-3f)
        assertEquals(0.2f + BodyWarpSolver.PERSON_HIP_FRACTION * 0.6f, guides.hipY, 5e-3f)
    }

    private fun personColumnMask(centreU: Float = 0.4f): FloatArray {
        val mask = FloatArray(BodyWarpSolver.MASK_SIZE * BodyWarpSolver.MASK_SIZE)
        val halfWidth = 0.12f
        for (row in 0 until BodyWarpSolver.MASK_SIZE) {
            val v = (row + 0.5f) / BodyWarpSolver.MASK_SIZE
            val inBody = v in 0.15f..0.95f
            for (column in 0 until BodyWarpSolver.MASK_SIZE) {
                val u = (column + 0.5f) / BodyWarpSolver.MASK_SIZE
                if (inBody && abs(u - centreU) <= halfWidth) {
                    mask[row * BodyWarpSolver.MASK_SIZE + column] = 1.0f
                }
            }
        }
        return mask
    }
}
