package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.render.PreviewCacheWriter
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SubjectImage

/**
 * Seam under test: the P3 colour pipeline — Pre-pass OKLab statistics, the Pass B foreground relight
 * and the Pass D sky composite rules.
 */
class SkyRelightingAndBlendTest {

    @Test
    fun shouldDerivePositiveWarmDeltas_whenTheNewSkyIsWarmerThanTheOldOne() {
        val stats = SkyRelightMath.compute(
            originalSkyMeanLinear = floatArrayOf(0.18f, 0.18f, 0.18f),
            newSkyMeanLinear = floatArrayOf(0.45f, 0.20f, 0.06f)
        )

        assertTrue(stats.deltaL > 0.0f)
        assertTrue(stats.deltaA > 0.0f)
        assertTrue(stats.deltaB > 0.0f)
    }

    @Test
    fun shouldClampTheLightnessLiftToTwelvePercent_whenTheDeltaIsMuchLarger() {
        val linear = floatArrayOf(0.5f, 0.5f, 0.5f)
        val stats = SkyRelightStats(deltaL = 0.9f, deltaA = 0.0f, deltaB = 0.0f)

        SkyRelightMath.apply(stats, relight = 100, skyCoverage = 0.0f, linear = linear)

        assertTrue("brightness must stay under the +12 % ceiling", linear[0] < 0.57f)
        assertTrue(linear[0] > 0.5f)
    }

    @Test
    fun shouldNotTouchTheForeground_whenThePixelIsAlreadySky() {
        val linear = floatArrayOf(0.3f, 0.3f, 0.3f)
        val stats = SkyRelightStats(deltaL = 0.1f, deltaA = 0.05f, deltaB = 0.05f)

        SkyRelightMath.apply(stats, relight = 100, skyCoverage = 1.0f, linear = linear)

        assertEquals(0.3f, linear[0], 1e-6f)
        assertEquals(0.3f, linear[1], 1e-6f)
        assertEquals(0.3f, linear[2], 1e-6f)
    }

    @Test
    fun shouldKeepDeepShadowsOutOfTheRelight_whenTheForegroundIsAlmostBlack() {
        assertEquals(0.0f, SkyRelightMath.weightOf(relight = 100, skyCoverage = 0.0f, luminance = 0.02f), 1e-6f)
        assertTrue(SkyRelightMath.weightOf(relight = 100, skyCoverage = 0.0f, luminance = 0.5f) > 0.5f)
    }

    @Test
    fun shouldComputeTheOriginalSkyMeanOnlyOverTheMaskedPixels_whenTheImageHasGround() {
        val image = SubjectImage(
            width = 4,
            height = 2,
            argb = intArrayOf(
                argb(64, 128, 200), argb(64, 128, 200),
                argb(64, 128, 200), argb(64, 128, 200),
                argb(90, 80, 60), argb(90, 80, 60),
                argb(90, 80, 60), argb(90, 80, 60)
            )
        )
        val mask = SkyMask(
            width = 2,
            height = 4,
            probability = floatArrayOf(1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f),
            coverageRatio = 0.5f
        )

        val mean = checkNotNull(SkyRelightMath.originalSkyMean(image, mask))

        val expected = ExposureShaderMath.decodeSrgb(64 / 255.0f)
        assertEquals(expected, mean[0], 0.02f)
        assertTrue("the brown ground must not contribute", mean[2] > mean[0])
    }

    @Test
    fun shouldReplaceTheWholePixel_whenCoverageIsOne() {
        val pixels = intArrayOf(argb(200, 40, 30))
        val coverage = floatArrayOf(1.0f)
        val params = SkyBlendParams(procedural = ProceduralSkyPreset.CLEAR_BLUE)

        SkyBlendMath.applyProceduralToArgb(pixels, 1, 1, coverage, params)

        val expected = FloatArray(3)
        ProceduralSky.sampleSrgb(ProceduralSkyPreset.CLEAR_BLUE, 0.5f, 0.5f, 0.0f, expected)
        assertEquals(expected[0] * 255f, ((pixels[0] ushr 16) and 0xFF).toFloat(), 2f)
        assertEquals(expected[1] * 255f, ((pixels[0] ushr 8) and 0xFF).toFloat(), 2f)
        assertEquals(expected[2] * 255f, (pixels[0] and 0xFF).toFloat(), 2f)
    }

    @Test
    fun shouldLeaveThePixelUntouched_whenCoverageIsZero() {
        val pixels = intArrayOf(argb(200, 40, 30))
        val params = SkyBlendParams(procedural = ProceduralSkyPreset.SUNSET_GLOW)

        SkyBlendMath.applyProceduralToArgb(pixels, 1, 1, floatArrayOf(0.0f), params)

        assertEquals(argb(200, 40, 30), pixels[0])
    }

    @Test
    fun shouldWidenTheTransitionBand_whenHorizonFeatherIsRaised() {
        val sharp = SkyBlendParams(
            procedural = ProceduralSkyPreset.CLEAR_BLUE,
            horizonFeather = 0
        )
        val hazy = sharp.copy(horizonFeather = 100)

        assertEquals(0.0f, SkyBlendMath.adjustedCoverage(0.30f, sharp), 1e-6f)
        assertTrue(SkyBlendMath.adjustedCoverage(0.30f, hazy) > 0.1f)
    }

    @Test
    fun shouldShiftTheMaskSampleOutwards_whenEdgeShiftIsPositive() {
        val positive = SkyBlendMath.shiftedCoordinate(0.2f, 0.5f, 1, 100, 100)
        val negative = SkyBlendMath.shiftedCoordinate(0.2f, 0.5f, -1, 100, 100)

        assertTrue(positive.first < 0.2f)
        assertTrue(negative.first > 0.2f)
    }

    @Test
    fun shouldScaleEdgeShiftByTheFullResolutionShortEdge_whenTheSourceSizeChanges() {
        val atFourK = SkyBlendMath.edgeScale(2, 4000)
        val atProxy = SkyBlendMath.edgeScale(2, 1000)

        assertTrue("the same tick must be a smaller UV shrink on a larger source", atFourK < atProxy)
        assertEquals(1.0f, SkyBlendMath.edgeScale(2, 0), 1e-6f)
    }

    @Test
    fun shouldLimitSpillSuppressionToTheTransitionBand() {
        assertTrue(SkyBlendMath.isSpillBand(0.5f))
        assertFalse(SkyBlendMath.isSpillBand(0.01f))
        assertFalse(SkyBlendMath.isSpillBand(0.99f))
    }

    @Test
    fun shouldGradeTheSkyThroughTheLut_whenPassDRunsAfterTheComposite() {
        val redLut = Lut3dSamples(
            size = 2,
            rgba = FloatArray(2 * 2 * 2 * 4) { index ->
                when (index % 4) {
                    0 -> 1.0f
                    3 -> 1.0f
                    else -> 0.0f
                }
            }
        )
        val graph = EditGraph(color = ColorOps(lut = LutRef(id = "red", strength = 100)))
        val sky = SkyCpuLayer(
            width = 1,
            height = 1,
            coverage = floatArrayOf(1.0f),
            skySrgb = intArrayOf(argb(20, 80, 200)),
            params = SkyBlendParams(procedural = ProceduralSkyPreset.CLEAR_BLUE)
        )

        val out = PreviewCacheWriter.gradeDisplayPixels(
            pixels = intArrayOf(argb(10, 10, 10)),
            width = 1,
            height = 1,
            graph = graph,
            fullResolutionShortEdge = 1,
            lut = redLut,
            sky = sky
        )

        assertTrue("the LUT must grade the composited sky too", (out[0] ushr 16) and 0xFF > 200)
        assertTrue((out[0] ushr 8) and 0xFF < 60)
        assertTrue(out[0] and 0xFF < 60)
    }

    @Test
    fun shouldRelightTheForeground_whenOnlyTheSkyRelightIsActive() {
        val input = argb(220, 220, 220)
        val pixels = intArrayOf(input)
        val sky = SkyCpuLayer(
            width = 1,
            height = 1,
            coverage = floatArrayOf(0.0f),
            skySrgb = intArrayOf(argb(80, 140, 220)),
            params = SkyBlendParams(procedural = ProceduralSkyPreset.CLEAR_BLUE),
            stats = SkyRelightStats(deltaL = 0.12f, deltaA = 0.0f, deltaB = 0.0f),
            relight = 100
        )

        val out = PreviewCacheWriter.gradeDisplayPixels(
            pixels = pixels,
            width = 1,
            height = 1,
            graph = EditGraph(),
            fullResolutionShortEdge = 1,
            sky = sky
        )

        assertTrue(
            "a relight-only graph must still warm the foreground (in=$input out=${out[0]})",
            ((out[0] ushr 16) and 0xFF) > ((input ushr 16) and 0xFF)
        )
    }

    @Test
    fun shouldSampleAHigherSky_whenHorizonOffsetIsPositive() {
        val center = FloatArray(3)
        val lowered = FloatArray(3)
        ProceduralSky.sampleSrgb(ProceduralSkyPreset.SUNSET_GLOW, 0.5f, 0.5f, 0.0f, center)
        ProceduralSky.sampleSrgb(ProceduralSkyPreset.SUNSET_GLOW, 0.5f, 0.5f, 0.2f, lowered)

        assertTrue("a positive offset samples higher in the sky", lowered[0] < center[0])
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
