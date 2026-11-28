package cn.devcxl.photosync.editor.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPixelRect

/**
 * Seam under test: [MiganPipelineCodec] and the [MiganPipelineRunner] contract.
 *
 * The assertions lock the four facts the uint8 pipeline path depends on (spec §2.2, §6.2):
 * mask inversion at the model boundary, RGB channel order, direct uint8 output decoding, and the
 * identity of a fully "keep" mask. No ONNX Runtime session is loaded: a fake runner returns the
 * encoded image, which is exactly what the measured artifact does for a no-hole input.
 */
class MiganPipelineRunnerTest {

    @Test
    fun shouldInvertTheMaskAtTheModelBoundary_whenTheDomainMaskMarksHolesWith255() {
        val mask = InpaintMaskBuffer(
            width = 4,
            height = 1,
            coverage = byteArrayOf(0, 0xFF.toByte(), 128.toByte(), 1)
        )

        val encoded = MiganPipelineCodec.encodeInvertedHardMaskUint8(mask, InpaintPixelRect(0, 0, 4, 1))

        assertArrayEquals(
            byteArrayOf(255.toByte(), 0, 0, 0),
            encoded
        )
    }

    @Test
    fun shouldNeverPreFeatherTheMask_whenTheMaskHasASoftBrushEdge() {
        val coverage = ByteArray(64)
        for (index in coverage.indices) coverage[index] = (index * 4).toByte()
        val mask = InpaintMaskBuffer(8, 8, coverage)

        val encoded = MiganPipelineCodec.encodeInvertedHardMaskUint8(mask, InpaintPixelRect(0, 0, 8, 8))

        assertTrue("mask input must stay hard", encoded.all { it.toInt() == 0 || it.toInt() == 255.toByte().toInt() })
    }

    @Test
    fun shouldEncodePlanarRgb_whenBuildingTheImageTensor() {
        val sourceWidth = 2
        val source = IntArray(4) { index ->
            when (index) {
                0 -> 0xFFC8280A.toInt()
                1 -> 0xFF102030.toInt()
                2 -> 0xFFE0D0C0.toInt()
                else -> 0xFF000000.toInt()
            }
        }

        val encoded = MiganPipelineCodec.encodeImageRgbUint8(
            sourceArgb = source,
            sourceWidth = sourceWidth,
            crop = InpaintPixelRect(0, 0, 2, 2)
        )

        assertEquals(12, encoded.size)
        val plane = 4
        assertEquals(200.toByte(), encoded[0])
        assertEquals(16.toByte(), encoded[1])
        assertEquals(224.toByte(), encoded[2])
        assertEquals(0.toByte(), encoded[3])
        assertEquals(40.toByte(), encoded[plane + 0])
        assertEquals(32.toByte(), encoded[plane + 1])
        assertEquals(208.toByte(), encoded[plane + 2])
        assertEquals(0.toByte(), encoded[plane + 3])
        assertEquals(10.toByte(), encoded[2 * plane + 0])
        assertEquals(48.toByte(), encoded[2 * plane + 1])
        assertEquals(192.toByte(), encoded[2 * plane + 2])
        assertEquals(0.toByte(), encoded[2 * plane + 3])
    }

    @Test
    fun shouldDecodePlanarRgbToOpaqueArgb_whenReadingTheResultTensor() {
        val plane = 1
        val result = byteArrayOf(
            200.toByte(), 40.toByte(), 10.toByte()
        )

        val decoded = MiganPipelineCodec.decodeResultRgbUint8(result, width = 1, height = 1)

        assertEquals(0xFFC8280A.toInt(), decoded[0])
    }

    @Test
    fun shouldReturnTheSourceExactly_whenTheMaskKeepsEverything() {
        val width = 8
        val height = 6
        val source = IntArray(width * height) { index ->
            val red = (index * 3) and 0xFF
            val green = (index * 5) and 0xFF
            val blue = (index * 7) and 0xFF
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val mask = InpaintMaskBuffer(width, height, ByteArray(width * height))
        val crop = InpaintPixelRect(0, 0, width, height)
        val image = MiganPipelineCodec.encodeImageRgbUint8(source, width, crop)
        val maskInput = MiganPipelineCodec.encodeInvertedHardMaskUint8(mask, crop)

        val identityRunner = MiganPipelineRunner { _, encodedImage, _, _, _ -> encodedImage }
        val result = identityRunner.run(StubHandle, image, maskInput, width, height)

        assertTrue("the keep mask must be all 255", maskInput.all { it.toInt() and 0xFF == 255 })
        assertNotEquals("the source must contain more than one colour", 1, source.toSet().size)
        assertArrayEquals(source, MiganPipelineCodec.decodeResultRgbUint8(result, width, height))
    }

    @Test
    fun shouldKeepTheChannelOrder_whenThePipelineRoundTripsAFlatCrop() {
        val source = IntArray(9) { 0xFFC8280A.toInt() }
        val crop = InpaintPixelRect(0, 0, 3, 3)
        val image = MiganPipelineCodec.encodeImageRgbUint8(source, 3, crop)
        val runner = MiganPipelineRunner { _, encodedImage, _, _, _ -> encodedImage }

        val decoded = MiganPipelineCodec.decodeResultRgbUint8(
            runner.run(StubHandle, image, ByteArray(9) { 255.toByte() }, 3, 3),
            3,
            3
        )

        assertArrayEquals(source, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectAMisSizedImage_whenTheCropEscapesTheSource() {
        MiganPipelineCodec.encodeImageRgbUint8(
            sourceArgb = IntArray(4),
            sourceWidth = 2,
            crop = InpaintPixelRect(0, 0, 3, 2)
        )
    }

    private object StubHandle : OrtSessionHandle {
        override val modelPath: String = "stub.onnx"
        override fun close() = Unit
    }
}
