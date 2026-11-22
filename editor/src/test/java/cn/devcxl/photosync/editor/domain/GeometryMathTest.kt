package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the pure-Kotlin geometry compiler that feeds both the Pass A GLES inverse
 * homography and the CPU preview-cache resampler.
 */
class GeometryMathTest {

    // ---------------------------------------------------------------- EXIF upright normalization

    /**
     * Canonical EXIF 2.32 orientation table: each entry lists the upright position reached by
     * the raw decoded bitmap's four corners (TL, TR, BR, BL).
     */
    private val exifCornerExpectations: Map<Int, List<Pair<Uv, Uv>>> = mapOf(
        // 1 = ORIENTATION_NORMAL
        1 to listOf(TL to TL, TR to TR, BR to BR, BL to BL),
        // 2 = ORIENTATION_FLIP_HORIZONTAL
        2 to listOf(TL to TR, TR to TL, BR to BL, BL to BR),
        // 3 = ORIENTATION_ROTATE_180
        3 to listOf(TL to BR, TR to BL, BR to TL, BL to TR),
        // 4 = ORIENTATION_FLIP_VERTICAL
        4 to listOf(TL to BL, TR to BR, BR to TR, BL to TL),
        // 5 = ORIENTATION_TRANSPOSE
        5 to listOf(TL to TL, TR to BL, BR to BR, BL to TR),
        // 6 = ORIENTATION_ROTATE_90
        6 to listOf(TL to TR, TR to BR, BR to BL, BL to TL),
        // 7 = ORIENTATION_TRANSVERSE
        7 to listOf(TL to BR, TR to TR, BR to TL, BL to BL),
        // 8 = ORIENTATION_ROTATE_270
        8 to listOf(TL to BL, TR to TL, BR to TR, BL to BR)
    )

    @Test
    fun shouldMapRawCornersToUprightCorners_whenApplyingEachOfEightExifOrientations() {
        assertEquals(8, exifCornerExpectations.size)
        for ((orientation, corners) in exifCornerExpectations) {
            for ((raw, upright) in corners) {
                val actual = GeometryMath.exifUprightUv(orientation, raw.u, raw.v)
                assertEquals("orientation=$orientation u", upright.u, actual.u, TOL)
                assertEquals("orientation=$orientation v", upright.v, actual.v, TOL)
            }
        }
    }

    @Test
    fun shouldSwapAxesOnlyForTransposedOrientations_whenQueryingOrientationAspectSwap() {
        listOf(1, 2, 3, 4).forEach {
            assertFalse("orientation=$it must keep width/height", GeometryMath.swapsOrientationAxes(it))
        }
        listOf(5, 6, 7, 8).forEach {
            assertTrue("orientation=$it must swap width/height", GeometryMath.swapsOrientationAxes(it))
        }
    }

    @Test
    fun shouldBeSelfInverseOrMutuallyInverse_whenComposingExifOrientations() {
        // Orientations 1, 3, 4, 5, 7 are involutions; 6 and 8 undo each other.
        listOf(1, 3, 4, 5, 7).forEach { orientation ->
            val sample = Uv(0.23f, 0.71f)
            val once = GeometryMath.exifUprightUv(orientation, sample.u, sample.v)
            val twice = GeometryMath.exifUprightUv(orientation, once.u, once.v)
            assertEquals("orientation=$orientation u", sample.u, twice.u, TOL)
            assertEquals("orientation=$orientation v", sample.v, twice.v, TOL)
        }
        val sample = Uv(0.17f, 0.63f)
        val upright = GeometryMath.exifUprightUv(6, sample.u, sample.v)
        val restored = GeometryMath.exifUprightUv(8, upright.u, upright.v)
        assertEquals(sample.u, restored.u, TOL)
        assertEquals(sample.v, restored.v, TOL)
    }

    @Test
    fun shouldMatchEditorDiscreteRotationAndFlipParity_whenNormalizingJpegExifOrientation() {
        // Every EXIF orientation is equivalent to one (rotate90, flipH, flipV) combination, so the
        // JPEG upload path and the interactive geometry sliders share a single transform model.
        val discreteEquivalents = mapOf(
            1 to Triple(0, false, false),
            2 to Triple(0, true, false),
            3 to Triple(2, false, false),
            4 to Triple(0, false, true),
            5 to Triple(1, true, false),
            6 to Triple(1, false, false),
            7 to Triple(3, true, false),
            8 to Triple(3, false, false)
        )
        val probes = listOf(TL, TR, BR, BL, Uv(0.37f, 0.82f))
        for ((orientation, ops) in discreteEquivalents) {
            assertEquals(
                "orientation=$orientation must expose one shared discrete table",
                ExifDiscreteOps(ops.first, ops.second, ops.third),
                GeometryMath.discreteOpsForExifOrientation(orientation)
            )
            for (probe in probes) {
                val expected = GeometryMath.discreteSourceToFrameUv(
                    rotate90 = ops.first,
                    flipH = ops.second,
                    flipV = ops.third,
                    u = probe.u,
                    v = probe.v
                )
                val actual = GeometryMath.exifUprightUv(orientation, probe.u, probe.v)
                assertEquals("orientation=$orientation u", expected.u, actual.u, TOL)
                assertEquals("orientation=$orientation v", expected.v, actual.v, TOL)
            }
        }
    }

    @Test
    fun shouldTreatUnknownOrientationAsNormal_whenNormalizingOutOfRangeExifValue() {
        val probe = Uv(0.31f, 0.44f)
        listOf(0, 9, -3).forEach { bogus ->
            val actual = GeometryMath.exifUprightUv(bogus, probe.u, probe.v)
            assertEquals(probe.u, actual.u, TOL)
            assertEquals(probe.v, actual.v, TOL)
        }
    }

    // ------------------------------------------------------- maximum inscribed straighten rect

    @Test
    fun shouldCoverTheWholeFrame_whenStraightenAngleIsZero() {
        listOf(1.0f, 4.0f / 3.0f, 3.0f / 2.0f, 9.0f / 16.0f, 2.0f).forEach { aspect ->
            val rect = GeometryMath.solveMaxInscribedRect(0.0f, aspect)
            assertEquals("aspect=$aspect halfWidth", aspect / 2.0f, rect.halfWidth, TOL)
            assertEquals("aspect=$aspect halfHeight", 0.5f, rect.halfHeight, TOL)
            assertEquals("aspect=$aspect scale", 1.0f, rect.scale, TOL)
        }
    }

    @Test
    fun shouldKeepEveryInscribedCornerInsideTheSourceFrame_whenStraighteningAcrossBounds() {
        val aspects = listOf(0.5f, 2.0f / 3.0f, 1.0f, 4.0f / 3.0f, 3.0f / 2.0f, 16.0f / 9.0f, 3.0f)
        var angle = -45.0f
        while (angle <= 45.0f + 1e-3f) {
            for (aspect in aspects) {
                val rect = GeometryMath.solveMaxInscribedRect(angle, aspect)
                assertNoCornerLeak(rect, angle, aspect)
            }
            angle += 0.25f
        }
    }

    @Test
    fun shouldRejectAnyLargerRect_whenAskingBeyondTheSolvedInscribedBounds() {
        val aspects = listOf(0.75f, 1.0f, 1.5f, 2.5f)
        for (aspect in aspects) {
            for (angle in listOf(-40.0f, -22.5f, -7.0f, 0.5f, 11.0f, 33.0f, 45.0f)) {
                val rect = GeometryMath.solveMaxInscribedRect(angle, aspect)
                val inflated = InscribedRect(
                    halfWidth = rect.halfWidth * 1.01f,
                    halfHeight = rect.halfHeight * 1.01f
                )
                val leaked = cornersLeak(inflated, angle, aspect)
                assertTrue(
                    "aspect=$aspect angle=$angle must leak once inflated by 1%",
                    leaked
                )
            }
        }
    }

    @Test
    fun shouldPreserveTheRequestedAspectRatioSymmetrically_whenStraightening() {
        val aspects = listOf(0.6f, 1.0f, 1.5f, 2.2f)
        for (aspect in aspects) {
            for (angle in listOf(5.0f, 17.5f, 30.0f, 45.0f)) {
                val rect = GeometryMath.solveMaxInscribedRect(angle, aspect)
                assertEquals(
                    "aspect=$aspect angle=$angle must stay aspect-locked",
                    aspect,
                    rect.halfWidth / rect.halfHeight,
                    1e-3f
                )
                val mirrored = GeometryMath.solveMaxInscribedRect(-angle, aspect)
                assertEquals(rect.halfWidth, mirrored.halfWidth, TOL)
                assertEquals(rect.halfHeight, mirrored.halfHeight, TOL)
            }
        }
    }

    @Test
    fun shouldClampOutOfRangeAnglesAndInvalidAspect_whenInputsAreInvalid() {
        val clamped = GeometryMath.solveMaxInscribedRect(120.0f, 1.0f)
        val atLimit = GeometryMath.solveMaxInscribedRect(45.0f, 1.0f)
        assertEquals(atLimit.halfWidth, clamped.halfWidth, TOL)
        assertEquals(atLimit.halfHeight, clamped.halfHeight, TOL)

        val nanAngle = GeometryMath.solveMaxInscribedRect(Float.NaN, 1.0f)
        assertTrue(nanAngle.halfWidth.isFinite())
        assertTrue(nanAngle.halfHeight.isFinite())

        val badAspect = GeometryMath.solveMaxInscribedRect(0.0f, 0.0f)
        assertEquals(0.5f, badAspect.halfWidth, TOL)
        assertEquals(0.5f, badAspect.halfHeight, TOL)
    }

    private fun assertNoCornerLeak(rect: InscribedRect, angleDeg: Float, aspect: Float) {
        assertFalse(
            "aspect=$aspect angle=$angleDeg leaked a black corner",
            cornersLeak(rect, angleDeg, aspect)
        )
    }

    /**
     * Independent geometric check: rotate the inscribed rectangle's four corners into the source
     * frame and verify none of them escapes `[-aspect/2, aspect/2] x [-1/2, 1/2]`.
     */
    private fun cornersLeak(rect: InscribedRect, angleDeg: Float, aspect: Float): Boolean {
        val radians = Math.toRadians(angleDeg.toDouble())
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        val halfW = rect.halfWidth.toDouble()
        val halfH = rect.halfHeight.toDouble()
        val limitX = aspect / 2.0 + CORNER_TOL
        val limitY = 0.5 + CORNER_TOL
        for (sx in listOf(-1.0, 1.0)) {
            for (sy in listOf(-1.0, 1.0)) {
                val x = sx * halfW
                val y = sy * halfH
                val sourceX = x * cos - y * sin
                val sourceY = x * sin + y * cos
                if (abs(sourceX) > limitX || abs(sourceY) > limitY) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ lateral CA resampling

    @Test
    fun shouldLeaveAllThreeChannelsIdentical_whenLateralCaSlidersAreNeutral() {
        listOf(Uv(0f, 0f), Uv(1f, 1f), Uv(0.37f, 0.81f), Uv(0.5f, 0.5f)).forEach { probe ->
            val sample = GeometryMath.applyLateralCaSampleUv(probe, kRed = 0.0f, kBlue = 0.0f)
            assertEquals(probe.u, sample.red.u, TOL)
            assertEquals(probe.v, sample.red.v, TOL)
            assertEquals(probe.u, sample.green.u, TOL)
            assertEquals(probe.v, sample.green.v, TOL)
            assertEquals(probe.u, sample.blue.u, TOL)
            assertEquals(probe.v, sample.blue.v, TOL)
        }
    }

    @Test
    fun shouldScaleRedAndBlueRadiallyAroundTheOpticalCentre_whenLateralCaIsNonZero() {
        // On a 1:1 source, maxCornerR2 = 0.5, so at (1.0, 0.5) r^2 = 0.25 / 0.5 = 0.5.
        val edge = Uv(1.0f, 0.5f)
        val k = 0.02f
        val sample = GeometryMath.applyLateralCaSampleUv(edge, kRed = k, kBlue = -k, srcAspect = 1.0f)
        val expectedScale = 1.0f + k * 0.5f
        assertEquals(0.5f + 0.5f * expectedScale, sample.red.u, 1e-5f)
        assertEquals(0.5f, sample.red.v, 1e-5f)
        assertEquals(0.5f + 0.5f * (2.0f - expectedScale), sample.blue.u, 1e-5f)
        assertEquals(0.5f, sample.blue.v, 1e-5f)
        assertEquals(edge.u, sample.green.u, TOL)
        assertEquals(edge.v, sample.green.v, TOL)
    }

    @Test
    fun shouldScaleIsotropicallyInPhysicalSpace_whenSourceAspectIsNonSquare() {
        // On a 2:1 frame, a horizontal offset du = 0.25 has the same physical distance from
        // centre as a vertical offset dv = 0.5 (since 0.25 * 2.0 == 0.5), so both get equal scale.
        val k = 0.02f
        val horizSample = GeometryMath.applyLateralCaSampleUv(
            Uv(0.75f, 0.5f), kRed = k, kBlue = 0.0f, srcAspect = 2.0f
        )
        val vertSample = GeometryMath.applyLateralCaSampleUv(
            Uv(0.5f, 1.0f), kRed = k, kBlue = 0.0f, srcAspect = 2.0f
        )
        val horizScale = (horizSample.red.u - 0.5f) / 0.25f
        val vertScale = (vertSample.red.v - 0.5f) / 0.5f
        assertEquals(horizScale, vertScale, 1e-5f)
    }

    @Test
    fun shouldPinTheOpticalCentreExactly_whenAnyLateralCaIsApplied() {
        val centre = Uv(0.5f, 0.5f)
        val sample = GeometryMath.applyLateralCaSampleUv(centre, kRed = 0.05f, kBlue = -0.05f)
        assertEquals(0.5f, sample.red.u, TOL)
        assertEquals(0.5f, sample.red.v, TOL)
        assertEquals(0.5f, sample.blue.u, TOL)
        assertEquals(0.5f, sample.blue.v, TOL)
    }

    @Test
    fun shouldShiftFartherOutAtLargerRadius_whenScalingAChannelAwayFromTheCentre() {
        val k = 0.03f
        val inner = GeometryMath.applyLateralCaSampleUv(Uv(0.6f, 0.5f), k, 0.0f, srcAspect = 1.0f).red
        val outer = GeometryMath.applyLateralCaSampleUv(Uv(1.0f, 0.5f), k, 0.0f, srcAspect = 1.0f).red
        val innerOffset = inner.u - 0.5f
        val outerOffset = outer.u - 0.5f
        assertEquals(0.1f * (1.0f + k * (0.01f / 0.5f)), innerOffset, 1e-5f)
        assertEquals(0.5f * (1.0f + k * 0.5f), outerOffset, 1e-5f)
        assertTrue("outer radius must shift further than the inner one", outerOffset > innerOffset)
    }

    @Test
    fun shouldMapTheSignedSliderRangeOntoSymmetricRadialCoefficients_whenConvertingCaSliders() {
        assertEquals(0.0f, GeometryMath.lateralCaKFromSlider(0), 1e-9f)
        assertEquals(
            GeometryMath.CA_MAX_RADIAL_SCALE,
            GeometryMath.lateralCaKFromSlider(100),
            1e-9f
        )
        assertEquals(
            -GeometryMath.CA_MAX_RADIAL_SCALE,
            GeometryMath.lateralCaKFromSlider(-100),
            1e-9f
        )
        assertEquals(
            GeometryMath.CA_MAX_RADIAL_SCALE,
            GeometryMath.lateralCaKFromSlider(480),
            1e-9f
        )
        assertTrue(GeometryMath.CA_MAX_RADIAL_SCALE > 0.0f)
    }

    companion object {
        private const val TOL = 1e-4f
        private const val CORNER_TOL = 1e-3
        private val TL = Uv(0.0f, 0.0f)
        private val TR = Uv(1.0f, 0.0f)
        private val BR = Uv(1.0f, 1.0f)
        private val BL = Uv(0.0f, 1.0f)
    }
}
