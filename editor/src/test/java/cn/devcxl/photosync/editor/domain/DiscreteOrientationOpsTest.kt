package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [DiscreteOrientationOps] — verifies that every screen-space rotation or flip
 * moves pixels in the exact visual direction on screen across all 8 symmetry states of `D4`.
 */
class DiscreteOrientationOpsTest {

    private val samplePoint = Uv(0.23f, 0.37f)

    private fun screenPosition(geometry: GeometryOps, source: Uv): Uv =
        GeometryMath.discreteSourceToFrameUv(
            geometry.rotate90,
            geometry.flipH,
            geometry.flipV,
            source.u,
            source.v
        )

    private fun allStates(): List<GeometryOps> = buildList {
        for (rotate in 0..3) {
            for (flipH in listOf(false, true)) {
                for (flipV in listOf(false, true)) {
                    add(GeometryOps(rotate90 = rotate, flipH = flipH, flipV = flipV))
                }
            }
        }
    }

    @Test
    fun shouldRotateDisplayedPixelsClockwise_whenApplyingClockwise90FromEveryD4State() {
        for (initial in allStates()) {
            val before = screenPosition(initial, samplePoint)
            val expectedAfter = Uv(1.0f - before.v, before.u)

            val updated = DiscreteOrientationOps.rotateClockwise90(initial)
            val actualAfter = screenPosition(updated, samplePoint)

            assertEquals("state=$initial u", expectedAfter.u, actualAfter.u, TOL)
            assertEquals("state=$initial v", expectedAfter.v, actualAfter.v, TOL)
        }
    }

    @Test
    fun shouldRotateDisplayedPixelsCounterClockwise_whenApplyingCounterClockwise90FromEveryD4State() {
        for (initial in allStates()) {
            val before = screenPosition(initial, samplePoint)
            val expectedAfter = Uv(before.v, 1.0f - before.u)

            val updated = DiscreteOrientationOps.rotateCounterClockwise90(initial)
            val actualAfter = screenPosition(updated, samplePoint)

            assertEquals("state=$initial u", expectedAfter.u, actualAfter.u, TOL)
            assertEquals("state=$initial v", expectedAfter.v, actualAfter.v, TOL)
        }
    }

    @Test
    fun shouldMirrorDisplayedPixelsHorizontally_whenTogglingFlipHorizontalFromEveryD4State() {
        for (initial in allStates()) {
            val before = screenPosition(initial, samplePoint)
            val expectedAfter = Uv(1.0f - before.u, before.v)

            val updated = DiscreteOrientationOps.toggleFlipHorizontal(initial)
            val actualAfter = screenPosition(updated, samplePoint)

            assertEquals("state=$initial u", expectedAfter.u, actualAfter.u, TOL)
            assertEquals("state=$initial v", expectedAfter.v, actualAfter.v, TOL)
        }
    }

    @Test
    fun shouldMirrorDisplayedPixelsVertically_whenTogglingFlipVerticalFromEveryD4State() {
        for (initial in allStates()) {
            val before = screenPosition(initial, samplePoint)
            val expectedAfter = Uv(before.u, 1.0f - before.v)

            val updated = DiscreteOrientationOps.toggleFlipVertical(initial)
            val actualAfter = screenPosition(updated, samplePoint)

            assertEquals("state=$initial u", expectedAfter.u, actualAfter.u, TOL)
            assertEquals("state=$initial v", expectedAfter.v, actualAfter.v, TOL)
        }
    }

    @Test
    fun shouldReturnToInitialState_whenRotatingClockwiseThenCounterClockwise() {
        for (initial in allStates()) {
            val roundTrip = DiscreteOrientationOps.rotateCounterClockwise90(
                DiscreteOrientationOps.rotateClockwise90(initial)
            )
            assertEquals(initial, roundTrip)
        }
    }

    @Test
    fun shouldRotateCropRectangleAlongWithImage_whenRotating90Degrees() {
        val cropped = GeometryOps(crop = CropRect(0.0f, 0.0f, 0.5f, 0.4f))

        val cw = DiscreteOrientationOps.rotateClockwise90(cropped)
        assertEquals(0.6f, cw.crop.left, TOL)
        assertEquals(0.0f, cw.crop.top, TOL)
        assertEquals(1.0f, cw.crop.right, TOL)
        assertEquals(0.5f, cw.crop.bottom, TOL)

        val restored = DiscreteOrientationOps.rotateCounterClockwise90(cw)
        assertEquals(cropped.crop.left, restored.crop.left, TOL)
        assertEquals(cropped.crop.top, restored.crop.top, TOL)
        assertEquals(cropped.crop.right, restored.crop.right, TOL)
        assertEquals(cropped.crop.bottom, restored.crop.bottom, TOL)
    }

    @Test
    fun shouldReportModifiedAndResetCleanly_whenOrientationChanges() {
        assertFalse(DiscreteOrientationOps.isOrientationModified(GeometryOps()))

        val rotated = DiscreteOrientationOps.rotateClockwise90(GeometryOps())
        assertTrue(DiscreteOrientationOps.isOrientationModified(rotated))

        val reset = DiscreteOrientationOps.resetOrientation(rotated)
        assertFalse(DiscreteOrientationOps.isOrientationModified(reset))
    }

    private companion object {
        const val TOL = 1e-5f
    }
}
