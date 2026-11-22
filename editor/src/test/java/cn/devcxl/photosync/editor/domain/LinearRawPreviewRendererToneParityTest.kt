package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Seam under test: [LinearRawPreviewRenderer] must produce the same pixels the Pass B shader shows
 * on screen, including the D65 white balance, so the "Done" thumbnail never disagrees with the
 * preview the photographer just graded.
 */
class LinearRawPreviewRendererToneParityTest {

    @Test
    fun shouldMatchTheReferenceLook_whenNoToneOrColourSliderIsSet() {
        val source = gradientBuffer(SIZE)
        val sample = FloatArray(4)

        for (baseLook in BaseLook.entries) {
            val pixels = LinearRawPreviewRenderer.renderArgb(source, EditGraph(baseLook = baseLook))
            for (index in pixels.indices) {
                source.readPixel(index, sample)
                val reference = CameraDefaultLookMath.renderLinearToSrgb(
                    linearRed = sample[0],
                    linearGreen = sample[1],
                    linearBlue = sample[2],
                    autoBrightGain = source.autoBrightGain,
                    exposureEv = 0.0f,
                    baseLook = baseLook
                )
                assertPixelMatches(baseLook.name, index, reference, pixels[index])
            }
        }
    }

    @Test
    fun shouldMatchTheShaderMath_whenToneAndWhiteBalanceAreActive() {
        val source = gradientBuffer(SIZE)
        val graphs = listOf(
            EditGraph(tone = ToneOps(exposure = 0.7f), baseLook = BaseLook.LINEAR_NEUTRAL),
            EditGraph(
                tone = ToneOps(
                    exposure = 0.4f,
                    brightness = 20,
                    contrast = 15,
                    glow = 25,
                    highlights = -30,
                    shadows = 20,
                    whites = -10,
                    blacks = 10
                ),
                color = ColorOps(temp = 25, tint = -12),
                baseLook = BaseLook.LINEAR_NEUTRAL
            ),
            EditGraph(
                tone = ToneOps(brightness = -15, contrast = -25, blacks = -20),
                color = ColorOps(temp = -40),
                baseLook = BaseLook.CAMERA_DEFAULT
            )
        )
        val sample = FloatArray(4)

        for (graph in graphs) {
            val pixels = LinearRawPreviewRenderer.renderArgb(source, graph)
            val params = ToneShaderParams.from(graph)
            for (index in pixels.indices) {
                source.readPixel(index, sample)
                assertPixelMatches(
                    "graph=$graph",
                    index,
                    referenceDisplay(sample, source.autoBrightGain, graph, params),
                    pixels[index]
                )
            }
        }
    }

    @Test
    fun shouldStayIdentical_whenOnlyExposureIsSetToZero() {
        val source = gradientBuffer(SIZE)
        val neutral = LinearRawPreviewRenderer.renderArgb(source, EditGraph())
        val zeroExposure = LinearRawPreviewRenderer.renderArgb(
            source,
            EditGraph(tone = ToneOps(exposure = 0.0f, brightness = 0))
        )

        assertTrue(neutral.contentEquals(zeroExposure))
    }

    @Test
    fun shouldWarmThePreview_whenTemperatureIsPositive() {
        val source = grayBuffer(0.18f)
        val neutral = LinearRawPreviewRenderer.renderArgb(source, EditGraph())
        val warm = LinearRawPreviewRenderer.renderArgb(
            source,
            EditGraph(color = ColorOps(temp = 60))
        )

        assertTrue(redOf(warm[0]) > redOf(neutral[0]))
        assertTrue(blueOf(warm[0]) < blueOf(neutral[0]))
    }

    private fun referenceDisplay(
        sample: FloatArray,
        autoBrightGain: Float,
        graph: EditGraph,
        params: ToneShaderParams
    ): FloatArray {
        val gain = if (autoBrightGain.isFinite() && autoBrightGain > 0.0f) autoBrightGain else 1.0f
        val linear = FloatArray(3)
        if (graph.baseLook == BaseLook.CAMERA_DEFAULT) {
            val looked = FloatArray(3) { channel ->
                ExposureShaderMath.encodeSrgb((sample[channel] * gain).coerceIn(0.0f, 1.0f))
            }
            val toned = FloatArray(3) {
                CameraDefaultLookMath.applyToneCurve(looked[it])
            }
            val vibrance = CameraDefaultLookMath.applyVibrance(toned[0], toned[1], toned[2])
            for (channel in 0..2) {
                val clamped = vibrance[channel].coerceIn(0.0f, 1.0f)
                val headroom = (sample[channel] * gain - 1.0f).coerceAtLeast(0.0f)
                linear[channel] = ExposureShaderMath.decodeSrgb(clamped) + headroom
            }
        } else {
            for (channel in 0..2) {
                linear[channel] = (sample[channel] * gain).coerceAtLeast(0.0f)
            }
        }
        val colorParams = ColorGradingParams.from(graph)
        val curveLut = if (colorParams.hasCurve) CurveLutBuilder.buildFloatLut(graph.color.curve) else null
        val out = FloatArray(3)
        PassBColorMath.apply(params, colorParams, curveLut, linear, out)
        return out
    }

    @Test
    fun shouldRescueOverexposedLinearRawHeadroomUnderCameraDefault_whenHighlightsArePulledDown() {
        // Blown highlight at 1.0 * autoBrightGain(2.0) = 2.0 (> 1.0) vs diffuse white at 0.5 * 2.0 = 1.0
        val blown = LinearRawBuffer.fromSamples(
            width = 2,
            height = 1,
            autoBrightGain = 2.0f,
            effectiveIso = 100.0f,
            rgbaSamples = floatArrayOf(
                0.5f, 0.5f, 0.5f, 1.0f,
                1.0f, 1.0f, 1.0f, 1.0f
            )
        )
        val neutral = LinearRawPreviewRenderer.renderArgb(
            blown,
            EditGraph(baseLook = BaseLook.CAMERA_DEFAULT)
        )
        val rescued = LinearRawPreviewRenderer.renderArgb(
            blown,
            EditGraph(baseLook = BaseLook.CAMERA_DEFAULT, tone = ToneOps(highlights = -100))
        )
        // Both are clipped at 255 in neutral camera_default, but pulling highlights recovers separation
        assertEquals(255, redOf(neutral[0]))
        assertEquals(255, redOf(neutral[1]))
        assertTrue(
            "Recovered highlight (${redOf(rescued[1])}) must exceed recovered diffuse white (${redOf(rescued[0])})",
            redOf(rescued[1]) > redOf(rescued[0])
        )
    }

    @Test
    fun shouldMatchPassBColorMath_whenCurveVibranceHslSplitAndFadeAreCombined() {
        val source = gradientBuffer(SIZE)
        val graph = EditGraph(
            baseLook = BaseLook.LINEAR_NEUTRAL,
            color = ColorOps(
                saturation = 15,
                vibrance = 35,
                hsl = HslOps(blue = HslBandShift(hue = 10, saturation = 20, luminance = -15)),
                curve = CurveOps(
                    rgb = listOf(CurvePoint(0.0f, 0.0f), CurvePoint(0.5f, 0.58f), CurvePoint(1.0f, 1.0f))
                ),
                split = SplitToningOps(hiHue = 45, hiSat = 25, shHue = 210, shSat = 20, balance = 10),
                fade = 20
            )
        )
        val pixels = LinearRawPreviewRenderer.renderArgb(source, graph)
        val params = ToneShaderParams.from(graph)
        val sample = FloatArray(4)
        for (index in pixels.indices) {
            source.readPixel(index, sample)
            assertPixelMatches(
                "passB2",
                index,
                referenceDisplay(sample, source.autoBrightGain, graph, params),
                pixels[index]
            )
        }
    }

    private fun assertPixelMatches(context: String, index: Int, reference: FloatArray, pixel: Int) {
        for (shift in listOf(16, 8, 0)) {
            val actual = (pixel shr shift) and 0xFF
            val expected = (reference[shiftToChannel(shift)] * 255.0f + 0.5f).toInt()
            assertTrue(
                "$context pixel $index channel $shift: expected ~$expected, got $actual",
                abs(actual - expected) <= PARITY_STEP
            )
        }
        assertEquals(0xFF, (pixel ushr 24) and 0xFF)
    }

    private fun shiftToChannel(shift: Int): Int = when (shift) {
        16 -> 0
        8 -> 1
        else -> 2
    }

    private fun redOf(pixel: Int): Int = (pixel shr 16) and 0xFF
    private fun blueOf(pixel: Int): Int = pixel and 0xFF

    private fun grayBuffer(value: Float): LinearRawBuffer = LinearRawBuffer.fromSamples(
        width = 1,
        height = 1,
        autoBrightGain = 1.0f,
        effectiveIso = 100.0f,
        rgbaSamples = floatArrayOf(value, value, value, 1.0f)
    )

    private fun gradientBuffer(size: Int): LinearRawBuffer {
        val samples = FloatArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val index = (y * size + x) * 4
                samples[index] = x / (size - 1).toFloat()
                samples[index + 1] = y / (size - 1).toFloat()
                samples[index + 2] = (x + y) / (2.0f * (size - 1))
                samples[index + 3] = 1.0f
            }
        }
        return LinearRawBuffer.fromSamples(
            width = size,
            height = size,
            autoBrightGain = 1.5f,
            effectiveIso = 320.0f,
            rgbaSamples = samples
        )
    }

    private companion object {
        private const val SIZE = 6
        private const val PARITY_STEP = 1
    }
}
