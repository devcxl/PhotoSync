package cn.devcxl.photosync.editor.domain

import cn.devcxl.photosync.wrapper.LinearRawBuffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the CPU gallery-preview renderer against the canonical [CameraDefaultLookMath] and
 * [ExposureShaderMath] pipeline. The renderer uses a lookup table for the per-channel base look
 * while the reference evaluates the curve directly, so agreement within one 8-bit step proves the
 * fast path never drifts.
 */
class LinearRawPreviewRendererTest {

    @Test
    fun shouldMatchCameraDefaultReferenceForMidGray_whenRenderingLinearBuffer() {
        val pixels = LinearRawPreviewRenderer.renderArgb(
            source = grayBuffer(0.18f, gain = 1.0f),
            graph = EditGraph(baseLook = BaseLook.CAMERA_DEFAULT)
        )

        assertEquals(1, pixels.size)
        assertEquals(0xFF, (pixels[0] ushr 24) and 0xFF)
        assertEquals(140, (pixels[0] shr 16) and 0xFF)
        assertEquals(140, pixels[0] and 0xFF)
    }

    @Test
    fun shouldStayWithinOneEightBitStep_whenComparingLookupTableRenderToReferenceMath() {
        val source = gradientBuffer(size = 8)
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
                assertChannelClose("R $baseLook", reference[0], pixels[index], 16)
                assertChannelClose("G $baseLook", reference[1], pixels[index], 8)
                assertChannelClose("B $baseLook", reference[2], pixels[index], 0)
            }
        }
    }

    @Test
    fun shouldRenderBrighterPixels_whenExposureIsRaised() {
        val source = grayBuffer(0.3f, gain = 1.0f)
        val neutral = LinearRawPreviewRenderer.renderArgb(
            source,
            EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL)
        )
        val lifted = LinearRawPreviewRenderer.renderArgb(
            source,
            EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL, tone = ToneOps(exposure = 1.0f))
        )

        assertTrue((lifted[0] and 0xFF) > (neutral[0] and 0xFF))
    }

    @Test
    fun shouldRenderDarkerMidGray_whenBaseLookIsLinearNeutral() {
        val source = grayBuffer(0.18f, gain = 1.0f)
        val cameraDefault = LinearRawPreviewRenderer.renderArgb(
            source,
            EditGraph(baseLook = BaseLook.CAMERA_DEFAULT)
        )
        val linearNeutral = LinearRawPreviewRenderer.renderArgb(
            source,
            EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL)
        )

        assertTrue((linearNeutral[0] and 0xFF) < (cameraDefault[0] and 0xFF))
        assertEquals(118, linearNeutral[0] and 0xFF)
    }

    @Test
    fun shouldApplyAutoBrightGain_whenBufferReportsGainAboveOne() {
        val plain = LinearRawPreviewRenderer.renderArgb(
            source = grayBuffer(0.1f, gain = 1.0f),
            graph = EditGraph(baseLook = BaseLook.CAMERA_DEFAULT)
        )
        val gained = LinearRawPreviewRenderer.renderArgb(
            source = grayBuffer(0.1f, gain = 2.0f),
            graph = EditGraph(baseLook = BaseLook.CAMERA_DEFAULT)
        )

        assertTrue((gained[0] and 0xFF) > (plain[0] and 0xFF))
    }

    private fun assertChannelClose(name: String, reference: Float, packedPixel: Int, shift: Int) {
        val actual = (packedPixel shr shift) and 0xFF
        val expected = (reference * 255.0f).toInt()
        assertTrue(
            "$name channel: expected ~$expected, got $actual",
            kotlin.math.abs(actual - expected) <= 1
        )
    }

    private fun grayBuffer(value: Float, gain: Float): LinearRawBuffer =
        LinearRawBuffer.fromSamples(
            width = 1,
            height = 1,
            autoBrightGain = gain,
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
}
