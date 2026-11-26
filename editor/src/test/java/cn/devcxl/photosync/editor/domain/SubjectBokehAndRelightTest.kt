package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [SubjectRelightMath], [SubjectBokehMath] and the terminal `SUBJECT_BOKEH` plan.
 *
 * The GLSL stages are expression-for-expression copies of these functions, so these tests lock the
 * math the shader must reproduce: relative foreground/background offsets, a background-only blur
 * that never pulls foreground colour into the backdrop, and the zero-cost neutral short circuits.
 */
class SubjectBokehAndRelightTest {

    private fun linear(r: Float = 0.25f, g: Float = 0.25f, b: Float = 0.25f): FloatArray =
        floatArrayOf(r, g, b)

    @Test
    fun shouldApplyForegroundExposureOnly_whenWeightIsPureForeground() {
        val subject = SubjectOps(fgExposure = 100)
        val foreground = linear()
        val background = linear()
        SubjectRelightMath.apply(subject, weight = 1.0f, linear = foreground)
        SubjectRelightMath.apply(subject, weight = 0.0f, linear = background)

        assertEquals(1.0f, foreground[0], 1e-4f)
        assertEquals(0.25f, background[0], 1e-4f)
    }

    @Test
    fun shouldMixForegroundAndBackgroundOffsetsByWeight_whenInTheTransitionBand() {
        val subject = SubjectOps(fgExposure = 100, bgExposure = -100)
        val sample = linear()

        SubjectRelightMath.apply(subject, weight = 0.5f, linear = sample)

        // +1 EV blended with -1 EV is exactly 0 EV, so the linear sample stays unchanged.
        assertEquals(0.25f, sample[0], 1e-4f)
        assertEquals(0.25f, sample[1], 1e-4f)
        assertEquals(0.25f, sample[2], 1e-4f)
    }

    @Test
    fun shouldWarmRedAndCoolBlue_whenTemperatureIsRaised() {
        val subject = SubjectOps(fgTemp = 100)
        val sample = linear()

        SubjectRelightMath.apply(subject, weight = 1.0f, linear = sample)

        assertTrue("red must rise", sample[0] > 0.25f)
        assertTrue("blue must fall", sample[2] < 0.25f)
    }

    @Test
    fun shouldExpandChroma_whenSaturationIsRaised() {
        val subject = SubjectOps(fgSat = 100)
        val sample = linear(0.4f, 0.2f, 0.2f)

        SubjectRelightMath.apply(subject, weight = 1.0f, linear = sample)

        assertTrue("red must move away from luma", sample[0] > 0.4f)
        assertTrue("green must move away from luma", sample[1] < 0.2f)
    }

    @Test
    fun shouldLeaveTheSampleUntouched_whenNoGradingOffsetsAreSet() {
        val sample = linear(0.31f, 0.42f, 0.53f)

        SubjectRelightMath.apply(SubjectOps(), weight = 1.0f, linear = sample)

        assertEquals(0.31f, sample[0], 1e-6f)
        assertEquals(0.42f, sample[1], 1e-6f)
        assertEquals(0.53f, sample[2], 1e-6f)
    }

    @Test
    fun shouldKeepForegroundPixelsSharp_whenTheSubjectCoversTheWholeFrame() {
        val width = 24
        val height = 16
        val srgb = checkerboard(width, height)
        val before = srgb.copyOf()
        val weight = FloatArray(width * height) { 1.0f }

        SubjectBokehMath.apply(srgb, width, height, weight, radiusPx = 6)

        assertTrue("a fully foreground frame must not change", srgb.contentEquals(before))
    }

    @Test
    fun shouldBlurOnlyBackground_whenWeightSeparatesSubjectAndBackground() {
        val width = 32
        val height = 16
        val srgb = FloatArray(width * height * 3)
        val weight = FloatArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val foreground = column >= width / 2
                weight[index] = if (foreground) 1.0f else 0.0f
                val value = if (foreground) 0.5f else if ((row + column) % 2 == 0) 0.0f else 1.0f
                srgb[index * 3] = value
                srgb[index * 3 + 1] = value
                srgb[index * 3 + 2] = value
            }
        }
        val foregroundBefore = (0 until height).map { row ->
            srgb[(row * width + width - 1) * 3]
        }

        SubjectBokehMath.apply(srgb, width, height, weight, radiusPx = 5)

        for (row in 0 until height) {
            val index = (row * width + width - 1) * 3
            assertEquals("foreground must stay sharp", foregroundBefore[row], srgb[index], 1e-6f)
        }
        val backgroundLeft = srgb[(height / 2 * width + 1) * 3]
        val backgroundRight = srgb[(height / 2 * width + width / 2 - 2) * 3]
        assertTrue("background must converge towards its mean", backgroundLeft in 0.2f..0.8f)
        assertEquals(backgroundLeft, backgroundRight, 0.25f)
    }

    @Test
    fun shouldNotPullForegroundColourIntoTheBackground_whenTapsStraddleTheEdge() {
        val width = 32
        val height = 16
        val srgb = FloatArray(width * height * 3)
        val weight = FloatArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val foreground = column >= width / 2
                weight[index] = if (foreground) 1.0f else 0.0f
                srgb[index * 3] = if (foreground) 1.0f else 0.3f
                srgb[index * 3 + 1] = if (foreground) 0.0f else 0.3f
                srgb[index * 3 + 2] = if (foreground) 0.0f else 0.3f
            }
        }

        SubjectBokehMath.apply(srgb, width, height, weight, radiusPx = 6)

        val edgeBackground = (height / 2 * width + width / 2 - 1) * 3
        assertTrue(
            "foreground red must never bleed into the blurred backdrop",
            srgb[edgeBackground] <= 0.31f
        )
    }

    @Test
    fun shouldScaleRadiusWithTheFrame_whenBlurIsRequested() {
        assertEquals(0, SubjectBokehMath.radiusPixels(blur = 0, shortEdge = 2000))
        assertTrue(SubjectBokehMath.radiusPixels(blur = 50, shortEdge = 2000) > 0)
        assertTrue(
            SubjectBokehMath.radiusPixels(blur = 100, shortEdge = 4000) >
                SubjectBokehMath.radiusPixels(blur = 50, shortEdge = 2000)
        )
        assertEquals(
            "a banded bake must cap the radius inside its halo",
            10,
            SubjectBokehMath.radiusPixels(blur = 100, shortEdge = 4000, maxRadiusPx = 10)
        )
    }

    @Test
    fun shouldMatchTheBakeRadiusFraction_whenA60MegapixelPhotoIsPreviewed() {
        val fullShortEdge = 6336
        val previewShortEdge = 1024
        val bakeRadius = SubjectBokehMath.effectiveRadiusPixels(
            blur = 100,
            fullShortEdge = fullShortEdge,
            renderShortEdge = fullShortEdge
        )
        val previewRadius = SubjectBokehMath.effectiveRadiusPixels(
            blur = 100,
            fullShortEdge = fullShortEdge,
            renderShortEdge = previewShortEdge
        )

        assertEquals(SubjectBokehMath.BAND_HALO_RADIUS_CAP_PX, bakeRadius)
        val bakeFraction = bakeRadius.toFloat() / fullShortEdge
        val previewFraction = previewRadius.toFloat() / previewShortEdge
        assertTrue(
            "preview and bake must use the same blur fraction",
            kotlin.math.abs(bakeFraction - previewFraction) < 0.001f
        )
        assertTrue("the preview radius must stay inside the halo cap", previewRadius < bakeRadius)
    }

    @Test
    fun shouldAppendTheBokehStageLast_whenAMaskIsAvailable() {
        val params = SpatialDetailParams(subjectBlur = 0.4f)

        val plan = SpatialDetailPlanner.plan(
            params,
            prepassAvailable = false,
            skinMaskAvailable = false,
            subjectMaskAvailable = true
        )

        assertEquals(SpatialDetailStage.SUBJECT_BOKEH, plan.stages.last())
        assertFalse(plan.isZeroDrawCallShortCircuit)
    }

    @Test
    fun shouldSkipTheBokehStage_whenTheMaskIsMissing() {
        val params = SpatialDetailParams(subjectBlur = 0.4f)

        val plan = SpatialDetailPlanner.plan(
            params,
            prepassAvailable = false,
            skinMaskAvailable = false,
            subjectMaskAvailable = false
        )

        assertTrue(plan.stages.isEmpty())
        assertEquals(listOf(SpatialDetailStage.SUBJECT_BOKEH), plan.skippedStages)
    }

    @Test
    fun shouldApplyTheRelightBeforePassB1InTheFullPassBReference() {
        val subject = SubjectOps(fgExposure = 100)
        val foreground = linear()
        val background = linear()
        val foregroundDisplay = FloatArray(3)
        val backgroundDisplay = FloatArray(3)

        PassBColorMath.apply(
            ToneShaderParams(), ColorGradingParams(), null, foreground, foregroundDisplay,
            subject = subject, subjectWeight = 1.0f
        )
        PassBColorMath.apply(
            ToneShaderParams(), ColorGradingParams(), null, background, backgroundDisplay,
            subject = subject, subjectWeight = 0.0f
        )

        assertTrue("foreground must be +2 EV", foregroundDisplay[0] > backgroundDisplay[0] + 0.3f)
    }

    @Test
    fun shouldApplyBokehOnlyToBackgroundPixelsInPreviewDetailPass() {
        val width = 240
        val height = 120
        val argb = IntArray(width * height) { index ->
            val value = if (index % 2 == 0) 0 else 255
            (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        val weight = FloatArray(width * height) { index ->
            if ((index % width) >= width / 2) 1.0f else 0.0f
        }
        val graph = EditGraph(subject = SubjectOps(blur = 100))

        val output = PreviewDetailPass.apply(
            argb = argb,
            width = width,
            height = height,
            graph = graph,
            subjectWeight = weight
        )

        assertTrue("foreground pixels must stay byte-identical", output[width / 2] == argb[width / 2])
        assertNotEquals("background pixels must be blurred", output[1], argb[1])
    }

    @Test
    fun shouldStayAtZeroDrawCalls_whenTheSubjectGroupIsNeutral() {
        val params = SpatialDetailParams.from(EditGraph())
        assertTrue(params.isZeroDrawCallShortCircuit)

        val blurred = SpatialDetailParams.from(EditGraph(subject = SubjectOps(blur = 1)))
        assertNotEquals(0.0f, blurred.subjectBlur)
        assertFalse(blurred.isZeroDrawCallShortCircuit)
    }

    private fun checkerboard(width: Int, height: Int): FloatArray {
        val srgb = FloatArray(width * height * 3)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val value = if ((row + column) % 2 == 0) 0.0f else 1.0f
                val index = (row * width + column) * 3
                srgb[index] = value
                srgb[index + 1] = value
                srgb[index + 2] = value
            }
        }
        return srgb
    }
}
