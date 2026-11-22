package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: Fritsch-Carlson monotone cubic Hermite splines ([MonotoneCubicCurve]) and
 * 1024-entry 1D LUT packing ([CurveLutBuilder]).
 */
class MonotoneCubicCurveTest {

    @Test
    fun shouldBeStrictlyMonotonicWithoutOvershootAcrossFiveHundredRandomKnotSets_whenKnotsAreNonDecreasing() {
        val rng = Random(20261122)
        val samples = 256
        repeat(500) { trial ->
            val points = randomKnotSet(rng, monotonic = true)
            val curve = MonotoneCubicCurve.compile(points)

            var prev = -1.0f
            for (s in 0..samples) {
                val x = s.toFloat() / samples.toFloat()
                val y = curve.evaluate(x)
                assertTrue("trial=$trial x=$x y=$y not in [0,1]", y in 0.0f..1.0f)
                assertTrue(
                    "trial=$trial non-monotonic at x=$x: prev=$prev curr=$y",
                    y >= prev - 1e-5f
                )
                assertWithinSegmentBounds(trial, points, x, y)
                prev = y
            }
        }
    }

    @Test
    fun shouldStayWithinSegmentBoundsWithoutOvershootAcrossFiveHundredRandomKnotSets_whenKnotsVaryFreely() {
        val rng = Random(99173)
        val samples = 256
        repeat(500) { trial ->
            val points = randomKnotSet(rng, monotonic = false)
            val curve = MonotoneCubicCurve.compile(points)
            for (s in 0..samples) {
                val x = s.toFloat() / samples.toFloat()
                val y = curve.evaluate(x)
                assertTrue("trial=$trial x=$x y=$y not in [0,1]", y in 0.0f..1.0f)
                assertWithinSegmentBounds(trial, points, x, y)
            }
        }
    }

    @Test
    fun shouldEvaluateCompositeRgbThenPerChannelAndPackHalfFloatLut_whenBuildingCurveLut() {
        // Composite lifts 0.5 -> 0.7; Red scales 0.7 -> 0.9, Green keeps linear, Blue pulls down 0.7 -> 0.4
        val curve = CurveOps(
            rgb = listOf(CurvePoint(0.0f, 0.0f), CurvePoint(0.5f, 0.7f), CurvePoint(1.0f, 1.0f)),
            r = listOf(CurvePoint(0.0f, 0.0f), CurvePoint(0.7f, 0.9f), CurvePoint(1.0f, 1.0f)),
            g = CurveOps.DEFAULT_LINEAR_CURVE,
            b = listOf(CurvePoint(0.0f, 0.0f), CurvePoint(0.7f, 0.4f), CurvePoint(1.0f, 1.0f))
        )
        val floatLut = CurveLutBuilder.buildFloatLut(curve)
        val halfLut = CurveLutBuilder.buildHalfFloatLut(curve)

        assertEquals(CurveLutBuilder.LUT_SIZE * CurveLutBuilder.CHANNELS, floatLut.size)
        assertEquals(floatLut.size, halfLut.size)
        for (i in floatLut.indices) {
            assertEquals(floatLut[i], HalfFloatBits.fromBits(halfLut[i]), 1e-3f)
        }

        val out = FloatArray(3)
        CurveLutBuilder.applyCurve(floatLut, floatArrayOf(0.5f, 0.5f, 0.5f), out)
        assertEquals(0.9f, out[0], 2e-3f)
        assertEquals(0.7f, out[1], 2e-3f)
        assertEquals(0.4f, out[2], 2e-3f)
    }

    @Test
    fun shouldEnforceMinimumSpacingAndMaxSixteenPointsPreservingTailEndpoint_whenSanitizingKnots() {
        val crowded = (0 until 24).map { i -> CurvePoint(i * 0.002f, i / 24.0f) }
        val sanitizedCrowded = MonotoneCubicCurve.sanitizePoints(crowded)

        assertTrue(sanitizedCrowded.size in 2..16)
        for (i in 1 until sanitizedCrowded.size) {
            assertTrue(
                sanitizedCrowded[i].x - sanitizedCrowded[i - 1].x >= MonotoneCubicCurve.MIN_SPACING - 1e-7f
            )
        }

        val overMax = (0 until 20).map { i -> CurvePoint(i / 19.0f, i / 19.0f) }
        val sanitizedOverMax = MonotoneCubicCurve.sanitizePoints(overMax)
        assertEquals(MonotoneCubicCurve.MAX_POINTS, sanitizedOverMax.size)
        assertEquals(1.0f, sanitizedOverMax.last().x, 1e-6f)
    }

    @Test
    fun shouldAlignGlTextureCenterCoordinatesWithCpuLutSampling_whenMappingZeroAndOne() {
        assertEquals(0.5f / CurveLutBuilder.LUT_SIZE.toFloat(), CurveLutBuilder.glTextureCoord(0.0f), 1e-7f)
        assertEquals(
            (CurveLutBuilder.LUT_SIZE - 0.5f) / CurveLutBuilder.LUT_SIZE.toFloat(),
            CurveLutBuilder.glTextureCoord(1.0f),
            1e-7f
        )
    }

    private fun randomKnotSet(rng: Random, monotonic: Boolean): List<CurvePoint> {
        val count = rng.nextInt(2, 17)
        val xs = FloatArray(count)
        xs[0] = 0.0f
        xs[count - 1] = 1.0f
        if (count > 2) {
            val minStep = MonotoneCubicCurve.MIN_SPACING * 1.15f
            val slack = 1.0f - (count - 1) * minStep
            val weights = FloatArray(count - 1) { rng.nextFloat() + 0.05f }
            val weightSum = weights.sum()
            for (i in 1 until count - 1) {
                xs[i] = xs[i - 1] + minStep + slack * (weights[i - 1] / weightSum)
            }
        }
        val ys = FloatArray(count) { rng.nextFloat() }
        if (monotonic) ys.sort()
        return List(count) { CurvePoint(xs[it], ys[it]) }
    }

    private fun assertWithinSegmentBounds(
        trial: Int,
        points: List<CurvePoint>,
        x: Float,
        y: Float
    ) {
        if (x <= points.first().x) {
            assertTrue(abs(y - points.first().y) < 1e-5f)
            return
        }
        if (x >= points.last().x) {
            assertTrue(abs(y - points.last().y) < 1e-5f)
            return
        }
        val seg = (0 until points.size - 1).first { x >= points[it].x && x <= points[it + 1].x }
        val lo = min(points[seg].y, points[seg + 1].y) - 1e-5f
        val hi = max(points[seg].y, points[seg + 1].y) + 1e-5f
        assertTrue(
            "trial=$trial x=$x y=$y overshot [$lo, $hi] on seg $seg",
            y in lo..hi
        )
    }
}
