package cn.devcxl.photosync.editor.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PngStripSinkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shouldRoundTripEveryRgbaByte_whenWritingOddSizedImageInStrips() {
        val width = 13
        val height = 7
        val pixels = samplePixels(width, height)
        val output = encode(pixels, width, height, stripHeight = 3, idatChunkBytes = 64)

        val decoded = PngTestDecoder.decode(output.toByteArray())
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val argb = pixels[row * width + column]
                val offset = (row * width + column) * 4
                assertEquals(
                    "R mismatch at ($column,$row)",
                    (argb shr 16) and 0xFF,
                    decoded.rgba[offset].toInt() and 0xFF
                )
                assertEquals(
                    "G mismatch at ($column,$row)",
                    (argb shr 8) and 0xFF,
                    decoded.rgba[offset + 1].toInt() and 0xFF
                )
                assertEquals(
                    "B mismatch at ($column,$row)",
                    argb and 0xFF,
                    decoded.rgba[offset + 2].toInt() and 0xFF
                )
                assertEquals(
                    "A mismatch at ($column,$row)",
                    (argb ushr 24) and 0xFF,
                    decoded.rgba[offset + 3].toInt() and 0xFF
                )
            }
        }
    }

    @Test
    fun shouldWriteLegalChunkSequenceWithValidCrc_whenEncodingWithSmallIdatChunks() {
        val width = 9
        val height = 4
        val output = encode(samplePixels(width, height), width, height, stripHeight = 2, idatChunkBytes = 48)

        val chunks = PngTestDecoder.chunks(output.toByteArray())
        assertEquals("IHDR", chunks.first().type)
        assertEquals("IEND", chunks.last().type)
        assertFalse("IDAT must be split into multiple chunks", chunks.count { it.type == "IDAT" } <= 1)

        val ihdr = chunks.first().data
        assertEquals(width, ByteBuffer.wrap(ihdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(height, ByteBuffer.wrap(ihdr, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(8, ihdr[8].toInt())
        assertEquals(6, ihdr[9].toInt())
        assertEquals(0, ihdr[10].toInt())
        assertEquals(0, ihdr[11].toInt())
        assertEquals(0, ihdr[12].toInt())
        assertTrue("eXIf is optional and must be absent here", chunks.none { it.type == "eXIf" })
    }

    @Test
    fun shouldExposeBasicShootingExifAndNormalizedOrientation_whenExifChunkIsProvided() {
        val exifTags = mapOf(
            ExifInterface.TAG_MAKE to "SONY",
            ExifInterface.TAG_MODEL to "ILCE-7M4",
            ExifInterface.TAG_LENS_MODEL to "FE 24-70mm F2.8 GM II",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:22 14:30:45",
            ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_90.toString(),
            ExifInterface.TAG_F_NUMBER to "2.8",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "400",
            ExifInterface.TAG_EXPOSURE_TIME to "1/250"
        )
        val width = 4000
        val height = 3000
        val payload = PngExifPayload.build(exifTags, width, height)
        val output = encode(
            samplePixels(4, 3),
            width = 4,
            height = 3,
            stripHeight = 2,
            idatChunkBytes = 128,
            exifPayload = payload
        )

        val file = temporaryFolder.newFile("bake_exif.png")
        file.writeBytes(output.toByteArray())
        val exif = ExifInterface(file.absolutePath)
        assertEquals("SONY", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("ILCE-7M4", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("FE 24-70mm F2.8 GM II", exif.getAttribute(ExifInterface.TAG_LENS_MODEL))
        assertEquals("2026:11:22 14:30:45", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(
            "Baked pixels are upright, so orientation must be normalized",
            ExifInterface.ORIENTATION_NORMAL,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        )
        assertEquals(width, exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0))
        assertEquals(height, exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
        assertEquals(width, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(height, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
        assertEquals(2.8, exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0), 0.001)
        assertEquals(400, exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0))
        assertEquals(0.004, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0), 1e-6)

        val chunks = PngTestDecoder.chunks(output.toByteArray())
        assertEquals(1, chunks.count { it.type == "eXIf" })
        assertTrue("eXIf must precede the first IDAT", chunks.indexOfFirst { it.type == "eXIf" } <
            chunks.indexOfFirst { it.type == "IDAT" })
    }

    @Test
    fun shouldAbortAtNextRow_whenCancellationProbeTurnsFalse() {
        val width = 8
        val height = 20
        val output = ByteArrayOutputStream()
        var active = true
        val sink = PngStripSink(
            output = output,
            width = width,
            height = height,
            isActive = { active },
            idatChunkBytes = 64
        )
        val buffer = stripBuffer(samplePixels(width, 4), width, startRow = 0, rows = 4)
        sink.writeStrip(buffer, 4)
        active = false

        var cancelled = false
        try {
            sink.writeStrip(buffer, 4)
        } catch (expected: CancellationException) {
            cancelled = true
        } finally {
            sink.close()
        }

        assertTrue("A cancelled bake must stop at the next row", cancelled)
        val chunks = PngTestDecoder.chunks(output.toByteArray())
        assertFalse(
            "An aborted stream must not terminate with IEND",
            chunks.any { it.type == "IEND" }
        )
    }

    @Test
    fun shouldRejectFinish_whenRowsAreMissing() {
        val output = ByteArrayOutputStream()
        val sink = PngStripSink(output, width = 4, height = 4)
        sink.writeStrip(stripBuffer(samplePixels(4, 2), 4, 0, 2), 2)

        var failed = false
        try {
            sink.finish()
        } catch (expected: java.io.IOException) {
            failed = true
        } finally {
            sink.close()
        }

        assertTrue("Finishing a short PNG stream must fail", failed)
    }

    private fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        stripHeight: Int,
        idatChunkBytes: Int,
        exifPayload: ByteArray? = null
    ): ByteArrayOutputStream {
        val output = ByteArrayOutputStream()
        PngStripSink(output, width, height, idatChunkBytes = idatChunkBytes).use { sink ->
            exifPayload?.let(sink::writeExifChunk)
            var row = 0
            while (row < height) {
                val rows = minOf(stripHeight, height - row)
                sink.writeStrip(stripBuffer(pixels, width, row, rows), rows)
                row += rows
            }
            sink.finish()
        }
        return output
    }

    private fun stripBuffer(pixels: IntArray, width: Int, startRow: Int, rows: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(width * rows * 4)
        for (row in 0 until rows) {
            for (column in 0 until width) {
                val argb = pixels[(startRow + row) * width + column]
                buffer.put(((argb shr 16) and 0xFF).toByte())
                buffer.put(((argb shr 8) and 0xFF).toByte())
                buffer.put((argb and 0xFF).toByte())
                buffer.put((argb ushr 24).toByte())
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun samplePixels(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val column = index % width
        val row = index / width
        val alpha = when ((column + row) % 5) {
            0 -> 0
            1 -> 1
            2 -> 127
            3 -> 254
            else -> 255
        }
        (alpha shl 24) or
            (((column * 17 + row * 3) and 0xFF) shl 16) or
            (((column * 5 + row * 29 + 71) and 0xFF) shl 8) or
            ((column * 11 + row * 7 + 193) and 0xFF)
    }
}
