package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [BradfordWbSolver], the CPU-side box that turns the two white-balance slider
 * values into the linear-sRGB adaptation matrix the Pass B shader multiplies with.
 *
 * The published D65→D50 Bradford matrix is used as an independent oracle for the cone-response
 * algebra: it is not derived from this module, so it can actually disagree with the code.
 */
class BradfordWbSolverTest {

    @Test
    fun shouldEqualIdentity_whenTemperatureAndTintAreZero() {
        val matrix = BradfordWbSolver.adaptationMatrix(temp = 0, tint = 0)

        assertEquals(9, matrix.size)
        MATRIX_IDENTITY.forEachIndexed { index, expected ->
            assertTrue(
                "matrix[$index] = ${matrix[index]} deviates from identity",
                abs(matrix[index] - expected) < BradfordWbSolver.IDENTITY_TOLERANCE
            )
        }
    }

    @Test
    fun shouldKeepLuminanceGrey_whenTemperatureAndTintAreZero() {
        val adapted = BradfordWbSolver.adaptLinearRgb(0.42f, 0.42f, 0.42f, temp = 0, tint = 0)

        assertTrue(abs(adapted[0] - 0.42f) < 1e-5f)
        assertTrue(abs(adapted[1] - 0.42f) < 1e-5f)
        assertTrue(abs(adapted[2] - 0.42f) < 1e-5f)
    }

    /**
     * Oracle: Bruce Lindbloom's published Bradford D65→D50 XYZ matrix. Because the solver rebuilds
     * the same von Kries diagonal in the Bradford cone space, the XYZ result must agree to 1e-5.
     */
    @Test
    fun shouldMatchPublishedBradfordMatrix_whenAdaptingD65WhiteToD50White() {
        val matrix = BradfordWbSolver.adaptationInXyzBetweenWhitePoints(
            sourceXyz = D65_XYZ,
            targetXyz = D50_XYZ
        )

        PUBLISHED_BRADFORD_D65_TO_D50_XYZ.forEachIndexed { index, expected ->
            assertTrue(
                "xyz[$index] = ${matrix[index]} does not match the published matrix",
                abs(matrix[index] - expected) < 1e-5f
            )
        }
    }

    @Test
    fun shouldRaiseRedOverBlue_whenTemperatureIsPositive() {
        val warm = BradfordWbSolver.adaptLinearRgb(GREY, GREY, GREY, temp = 100, tint = 0)
        val neutral = BradfordWbSolver.adaptLinearRgb(GREY, GREY, GREY, temp = 0, tint = 0)
        val cool = BradfordWbSolver.adaptLinearRgb(GREY, GREY, GREY, temp = -100, tint = 0)

        assertTrue("warm red must exceed neutral red", warm[0] > neutral[0])
        assertTrue("warm blue must drop below neutral blue", warm[2] < neutral[2])
        assertTrue("cool blue must exceed neutral blue", cool[2] > neutral[2])
        assertTrue("cool red must drop below neutral red", cool[0] < neutral[0])
    }

    @Test
    fun shouldWarmMonotonically_whenTemperatureSweepsFromCoolToWarm() {
        val ratios = listOf(-100, -50, 0, 50, 100).map { temp ->
            val adapted = BradfordWbSolver.adaptLinearRgb(GREY, GREY, GREY, temp, tint = 0)
            adapted[0] / adapted[2]
        }

        ratios.zipWithNext().forEach { (previous, next) ->
            assertTrue("red/blue gain must grow with temperature: $ratios", next > previous)
        }
    }

    @Test
    fun shouldShiftMagenta_whenTintIsPositive() {
        val magenta = BradfordWbSolver.adaptLinearRgb(GREY, GREY, GREY, temp = 0, tint = 100)
        val neutral = BradfordWbSolver.adaptLinearRgb(GREY, GREY, GREY, temp = 0, tint = 0)
        val green = BradfordWbSolver.adaptLinearRgb(GREY, GREY, GREY, temp = 0, tint = -100)

        assertTrue("magenta must lower the green gain", magenta[1] < neutral[1])
        assertTrue("magenta must raise red above green", magenta[0] > magenta[1])
        assertTrue("magenta must raise blue above green", magenta[2] > magenta[1])
        assertTrue("green tint must raise the green gain", green[1] > neutral[1])
    }

    @Test
    fun shouldStayFiniteAndPositive_whenBothSlidersAreAtTheirExtremes() {
        for (temp in listOf(-100, -37, 0, 61, 100)) {
            for (tint in listOf(-100, 0, 100)) {
                val adapted = BradfordWbSolver.adaptLinearRgb(0.18f, 0.18f, 0.18f, temp, tint)
                assertTrue("temp=$temp tint=$tint produced a non-finite gain", adapted.all { it.isFinite() })
                assertTrue("temp=$temp tint=$tint produced a negative gain", adapted.all { it > 0.0f })
            }
        }
    }

    @Test
    fun shouldClampOutOfRangeSliders_whenSolvingBeyondTheLegalDomain() {
        val clamped = BradfordWbSolver.adaptationMatrix(temp = 400, tint = -400)
        val legal = BradfordWbSolver.adaptationMatrix(temp = 100, tint = -100)

        clamped.forEachIndexed { index, value ->
            assertEquals(legal[index], value, 1e-6f)
        }
    }

    private companion object {
        private const val GREY = 0.18f

        private val MATRIX_IDENTITY = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )

        private val D65_XYZ = floatArrayOf(0.95047f, 1.0f, 1.08883f)
        private val D50_XYZ = floatArrayOf(0.96422f, 1.0f, 0.82521f)

        private val PUBLISHED_BRADFORD_D65_TO_D50_XYZ = floatArrayOf(
            1.0478112f, 0.0228866f, -0.0501270f,
            0.0295424f, 0.9904844f, -0.0170491f,
            -0.0092345f, 0.0150436f, 0.7521316f
        )
    }
}
