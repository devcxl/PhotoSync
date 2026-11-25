package cn.devcxl.photosync.editor.cloud

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudUploadSanitizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shouldStripSensitiveMetadataAndRotateUpright_whenSourceCarriesGpsAndThumbnail() {
        val source = File(tempFolder.root, "IMG_4242.jpg")
        ExifJpegFixture.writeCameraJpeg(source, width = 2400, height = 1600, orientation = 6)
        assertSourceIsSensitive(source)

        val sanitizer = CloudUploadSanitizer(uploadDir())
        var sanitizedFile: File? = null
        sanitizer.withSanitizedUpload(source.absolutePath) { handle ->
            sanitizedFile = handle.file
            assertEquals(1365, handle.width)
            assertEquals(2048, handle.height)
            assertTrue(handle.file.parentFile == uploadDir())
            assertTrue(UPLOAD_NAME.matches(handle.file.name))
            assertFalse(handle.file.name.contains("IMG_4242"))

            val exif = ExifInterface(handle.file.absolutePath)
            assertNull(exif.latLong)
            for (tag in SENSITIVE_TAGS) {
                assertNull("Unexpected residual tag: $tag", exif.getAttribute(tag))
            }
            assertFalse(exif.hasThumbnail())
            assertEquals(
                ExifInterface.ORIENTATION_UNDEFINED,
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            )
            assertNoExifSegmentOrSourceText(handle.file)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(handle.file.absolutePath, bounds)
            assertEquals(1365, bounds.outWidth)
            assertEquals(2048, bounds.outHeight)
            assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 2048)
        }

        assertFalse(checkNotNull(sanitizedFile).exists())
    }

    @Test
    fun shouldChoosePowerOfTwoSamples_whenDecodedLongEdgeMustStayAboveLimit() {
        assertEquals(1, CloudUploadSanitizer.calculateInSampleSize(1000, 800, 2048))
        assertEquals(1, CloudUploadSanitizer.calculateInSampleSize(2048, 1365, 2048))
        assertEquals(1, CloudUploadSanitizer.calculateInSampleSize(2049, 1366, 2048))
        assertEquals(2, CloudUploadSanitizer.calculateInSampleSize(4096, 2730, 2048))
        assertEquals(2, CloudUploadSanitizer.calculateInSampleSize(6000, 4000, 2048))
        assertEquals(4, CloudUploadSanitizer.calculateInSampleSize(11648, 8736, 2048))
    }

    @Test
    fun shouldKeepDimensions_whenSourceLongEdgeFitsTheLimit() {
        val source = File(tempFolder.root, "small.jpg")
        ExifJpegFixture.writeCameraJpeg(
            source,
            width = 1200,
            height = 800,
            orientation = ExifInterface.ORIENTATION_NORMAL
        )
        val sanitizer = CloudUploadSanitizer(uploadDir())

        val handle = sanitizer.createUpload(source.absolutePath)
        try {
            assertEquals(1200, handle.width)
            assertEquals(800, handle.height)
            assertNull(ExifInterface(handle.file.absolutePath).getAttribute(ExifInterface.TAG_MAKE))
        } finally {
            handle.close()
        }
        assertFalse(handle.file.exists())
    }

    @Test
    fun shouldDeleteUpload_whenTheRequestBlockThrows() {
        val source = File(tempFolder.root, "IMG_9000.jpg")
        ExifJpegFixture.writeCameraJpeg(source, width = 640, height = 480, orientation = 1)
        val sanitizer = CloudUploadSanitizer(uploadDir())
        var staged: File? = null

        try {
            sanitizer.withSanitizedUpload(source.absolutePath) { handle ->
                staged = handle.file
                assertTrue(handle.file.exists())
                throw IllegalStateException("network request failed")
            }
            fail("Expected the request failure to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("network request failed", expected.message)
        }

        assertFalse(checkNotNull(staged).exists())
    }

    @Test
    fun shouldBeIdempotent_whenHandleIsClosedTwice() {
        val source = File(tempFolder.root, "close.jpg")
        ExifJpegFixture.writeCameraJpeg(source, width = 320, height = 240, orientation = 1)
        val sanitizer = CloudUploadSanitizer(uploadDir())
        val handle = sanitizer.createUpload(source.absolutePath)
        val staged = handle.file

        handle.close()
        handle.close()

        assertFalse(staged.exists())
    }

    @Test
    fun shouldInvertPaintedStrokes_whenBuildingEditableMaskAtUploadResolution() {
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            mask.eraseColor(Color.BLACK)
            mask.setPixel(1, 1, Color.WHITE)
            mask.setPixel(2, 1, Color.WHITE)
            val sanitizer = CloudUploadSanitizer(uploadDir())

            val editable = sanitizer.createEditableMaskBitmap(mask, width = 4, height = 4)
            try {
                assertEquals(0, Color.alpha(editable.getPixel(1, 1)))
                assertEquals(0, Color.alpha(editable.getPixel(2, 1)))
                assertEquals(255, Color.alpha(editable.getPixel(0, 0)))
                assertEquals(255, Color.alpha(editable.getPixel(3, 3)))
                assertEquals(0, Color.red(editable.getPixel(1, 1)))
                assertEquals(0, Color.green(editable.getPixel(1, 1)))
                assertEquals(0, Color.blue(editable.getPixel(1, 1)))
            } finally {
                editable.recycle()
            }
        } finally {
            mask.recycle()
        }
    }

    @Test
    fun shouldScaleMaskToUploadDimensions_whenWritingEditableMaskPng() {
        val mask = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = File(tempFolder.root, "mask.png")
        try {
            mask.eraseColor(Color.WHITE)
            val sanitizer = CloudUploadSanitizer(uploadDir())

            sanitizer.writeEditableMaskPng(mask, width = 32, height = 20, outputFile = output)

            val decoded = BitmapFactory.decodeFile(output.absolutePath)
            assertNotNull(decoded)
            checkNotNull(decoded)
            try {
                assertEquals(32, decoded.width)
                assertEquals(20, decoded.height)
                assertEquals(0, Color.alpha(decoded.getPixel(16, 10)))
                assertEquals(0, Color.alpha(decoded.getPixel(0, 0)))
            } finally {
                decoded.recycle()
            }
        } finally {
            mask.recycle()
        }
    }

    @Test
    fun shouldNotLeakSourcePath_whenSourceCannotBeDecoded() {
        val source = File(tempFolder.root, "private/MySecretPhoto.jpg")
        val sanitizer = CloudUploadSanitizer(uploadDir())
        val messages = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
                t?.message?.let(messages::add)
            }
        }

        Timber.plant(tree)
        val thrown = try {
            assertThrows(CloudSanitizationException::class.java) {
                sanitizer.createUpload(source.absolutePath)
            }
        } finally {
            Timber.uproot(tree)
        }

        assertFalse(thrown.message.orEmpty().contains("MySecretPhoto"))
        assertFalse(thrown.message.orEmpty().contains(source.absolutePath))
        assertFalse(messages.any { it.contains("MySecretPhoto") })
    }

    private fun assertSourceIsSensitive(source: File) {
        val exif = ExifInterface(source.absolutePath)
        assertEquals(6, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertNotNull(exif.latLong)
        assertEquals("PhotoSync Test Camera", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("PS-1", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("BODY-SERIAL-42", exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        assertEquals(
            "2024:05:06 07:08:09",
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        )
        assertTrue(exif.hasThumbnail())
        // LensSerialNumber (0xA435) is written into the fixture's EXIF IFD but is not part of the
        // 1.4.2 tag table, so it is only observable byte-wise.
        assertTrue(source.readBytes().containsSubsequence(LENS_SERIAL_BYTES))
    }

    private fun assertNoExifSegmentOrSourceText(file: File) {
        val bytes = file.readBytes()
        assertFalse(bytes.containsSubsequence(EXIF_APP1_IDENTIFIER))
        assertFalse(bytes.containsSubsequence(LENS_SERIAL_BYTES))
        assertFalse(bytes.containsSubsequence("BODY-SERIAL-42".toByteArray(Charsets.US_ASCII)))
        assertFalse(bytes.containsSubsequence("PhotoSync Test Camera".toByteArray(Charsets.US_ASCII)))
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun uploadDir(): File = File(tempFolder.root, "upload")

    private companion object {
        val UPLOAD_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        val EXIF_APP1_IDENTIFIER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
        val LENS_SERIAL_BYTES = "LENS-SERIAL-99".toByteArray(Charsets.US_ASCII)

        val SENSITIVE_TAGS = arrayOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_SOFTWARE
        )
    }
}
