package cn.devcxl.photosync.wrapper

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LinearRawBufferTest {

    @Test
    fun shouldRoundTripHalfFloatsWithinBinary16Precision_whenConvertingFromFloat() {
        val samples = floatArrayOf(0.0f, 1.0f, 0.5f, 0.15625f, 0.001f, 12.5f, 65504.0f)
        for (value in samples) {
            val roundTripped = HalfFloat.fromHalfBits(HalfFloat.toHalfBits(value))
            val tolerance = if (value == 0.0f) 0.0f else value * 0.002f
            assertEquals("half round-trip of $value", value.toDouble(), roundTripped.toDouble(), tolerance.toDouble())
        }
    }

    @Test
    fun shouldEncodeNeutralAlphaAndWhite_whenWritingKnownHalfValues() {
        assertEquals(0x3C00.toShort(), HalfFloat.toHalfBits(1.0f))
        assertEquals(0x0000.toShort(), HalfFloat.toHalfBits(0.0f))
        assertEquals(0x3800.toShort(), HalfFloat.toHalfBits(0.5f))
    }

    @Test
    fun shouldHalveLongestEdgeAndAreaAverageSourceBlocks_whenDownscaling() {
        val source = buildBuffer(width = 4, height = 4)

        val downscaled = source.downscaledTo(maxLongEdge = 2)

        assertEquals(2, downscaled.width)
        assertEquals(2, downscaled.height)
        val pixels = downscaled.duplicateFloats()
        // Top-left 2x2 block of the ramp is (0 + 1 + 4 + 5) / 4 = 2.5, stored as index / 16.
        assertEquals(2.5f / 16.0f, pixels[pixelOffset(0, 0, 2)], 1e-6f)
        assertEquals((10 + 11 + 14 + 15) / 4.0f / 16.0f, pixels[pixelOffset(1, 1, 2)], 1e-6f)
    }

    @Test
    fun shouldPreserveAspectRatioAndMetadata_whenDownscalingNonSquareBuffer() {
        val source = buildBuffer(width = 8, height = 4).copy(autoBrightGain = 2.5f, effectiveIso = 640.0f)

        val downscaled = source.downscaledTo(maxLongEdge = 4)

        assertEquals(4, downscaled.width)
        assertEquals(2, downscaled.height)
        assertEquals(2.5f, downscaled.autoBrightGain, 1e-6f)
        assertEquals(640.0f, downscaled.effectiveIso, 1e-6f)
    }

    @Test
    fun shouldClampAlphaToOne_whenDownscalingHalfFloatPixels() {
        val source = buildBuffer(width = 4, height = 4)
        val downscaled = source.downscaledTo(maxLongEdge = 2)
        val pixels = downscaled.duplicateFloats()
        val offset = pixelOffset(1, 0, 2)
        assertEquals(1.0f, pixels[offset + 3], 1e-6f)
    }

    @Test
    fun shouldReturnSameBuffer_whenTargetEdgeIsNotSmallerThanSource() {
        val source = buildBuffer(width = 4, height = 4)

        assertSame(source, source.downscaledTo(maxLongEdge = 4))
        assertSame(source, source.downscaledTo(maxLongEdge = 0))
        assertSame(source, source.downscaledTo(maxLongEdge = -8))
    }

    private fun buildBuffer(width: Int, height: Int): LinearRawBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * LinearRawBuffer.BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                HalfFloat.writeHalf(buffer, index.toFloat() / 16.0f)
                HalfFloat.writeHalf(buffer, 1.0f)
                HalfFloat.writeHalf(buffer, 0.0f)
                HalfFloat.writeHalf(buffer, 1.0f)
            }
        }
        buffer.rewind()
        return LinearRawBuffer(
            width = width,
            height = height,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgba16fBuffer = buffer
        )
    }

    private fun LinearRawBuffer.duplicateFloats(): FloatArray {
        val view = rgba16fBuffer.duplicate().order(ByteOrder.nativeOrder())
        view.rewind()
        val values = FloatArray(width * height * 4)
        for (index in values.indices) {
            values[index] = HalfFloat.fromHalfBits(view.short)
        }
        return values
    }

    private fun pixelOffset(x: Int, y: Int, width: Int): Int = (y * width + x) * 4
}
