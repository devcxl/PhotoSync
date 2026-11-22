package cn.devcxl.photosync.export

import java.util.Calendar
import java.util.TimeZone

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

import cn.devcxl.photosync.wrapper.RawExifMetadata

class ExifCopierTest {

    private class FakeExifTagStore : ExifTagReader, ExifTagWriter {
        private val tags = mutableMapOf<String, String>()
        private var latLongCoords: DoubleArray? = null
        private var altitudeMeters: Double = Double.NaN

        override fun getAttribute(tag: String): String? = tags[tag]

        override fun getLatLong(): DoubleArray? = latLongCoords

        override fun getAltitude(defaultValue: Double): Double =
            if (altitudeMeters.isNaN()) defaultValue else altitudeMeters

        override fun setAttribute(tag: String, value: String?) {
            if (value == null) {
                tags.remove(tag)
            } else {
                tags[tag] = value
            }
        }

        override fun setLatLong(latitude: Double, longitude: Double) {
            latLongCoords = doubleArrayOf(latitude, longitude)
        }

        override fun setAltitude(altitude: Double) {
            altitudeMeters = altitude
        }
    }

    @Test
    fun shouldCopyWhitelistedTagsNormalizeOrientationTo1AndExcludeMakerNote_whenCopyingFromJpeg() {
        val src = FakeExifTagStore().apply {
            setAttribute(ExifInterface.TAG_MAKE, "SONY")
            setAttribute(ExifInterface.TAG_MODEL, "ILCE-7M4")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:22 14:30:45")
            setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            setAttribute(ExifInterface.TAG_LENS_MODEL, "FE 24-70mm F2.8 GM II")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKER_NOTE, "BINARY_PROPRIETARY_BLOB")
            setAttribute(ExifInterface.TAG_XMP, "<x:xmpmeta/>")
            setLatLong(31.2304, 121.4737)
            setAltitude(12.5)
        }
        val dst = FakeExifTagStore().apply {
            setAttribute(ExifInterface.TAG_MAKER_NOTE, "STALE_MAKER_NOTE")
        }

        ExifCopier.copyFromReader(
            source = src,
            target = dst,
            outputWidth = 4000,
            outputHeight = 6000
        )

        assertEquals("SONY", dst.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("ILCE-7M4", dst.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2026:11:22 14:30:45", dst.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("2.8", dst.getAttribute(ExifInterface.TAG_F_NUMBER))
        assertEquals("400", dst.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
        assertEquals("FE 24-70mm F2.8 GM II", dst.getAttribute(ExifInterface.TAG_LENS_MODEL))
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL.toString(),
            dst.getAttribute(ExifInterface.TAG_ORIENTATION)
        )
        assertEquals("4000", dst.getAttribute(ExifInterface.TAG_IMAGE_WIDTH))
        assertEquals("6000", dst.getAttribute(ExifInterface.TAG_IMAGE_LENGTH))
        assertNull(dst.getAttribute(ExifInterface.TAG_MAKER_NOTE))
        assertNull(dst.getAttribute(ExifInterface.TAG_XMP))
        val latLong = dst.getLatLong()
        assertNotNull(latLong)
        assertEquals(31.2304, latLong!![0], 0.0001)
        assertEquals(121.4737, latLong[1], 0.0001)
        assertEquals(12.5, dst.getAltitude(0.0), 0.0001)
    }

    @Test
    fun shouldWriteRawExifWithLocalTimezoneAndOrientation1_whenApplyingRawMetadata() {
        val localCal = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(2026, Calendar.NOVEMBER, 22, 9, 15, 30)
            set(Calendar.MILLISECOND, 0)
        }
        val epochSec = localCal.timeInMillis / 1000L

        val rawMeta = RawExifMetadata(
            make = "Canon  \u0000",
            model = "EOS R5 ",
            timestampEpochSec = epochSec,
            shutterSec = 1.0f / 250.0f,
            apertureFNumber = 4.0f,
            isoSpeed = 800f,
            focalLengthMm = 85.0f,
            focalLengthIn35mm = 85,
            lensMake = "Canon",
            lensModel = "RF85mm F1.2 L USM",
            exposureProgram = 3,
            meteringMode = 5,
            imageDescription = "\u0000\u0007",
            artist = "devcxl",
            gpsParsed = true,
            gpsLatitude = 35.6895,
            gpsLongitude = 139.6917,
            gpsAltitude = 0.0
        )

        val dst = FakeExifTagStore()
        ExifCopier.applyFromRawMetadata(
            metadata = rawMeta,
            target = dst,
            outputWidth = 8192,
            outputHeight = 5464
        )

        assertEquals("Canon", dst.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("EOS R5", dst.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2026:11:22 09:15:30", dst.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("2026:11:22 09:15:30", dst.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        assertEquals("2026:11:22 09:15:30", dst.getAttribute(ExifInterface.TAG_DATETIME))
        assertEquals("0.004000", dst.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        assertEquals("4.0", dst.getAttribute(ExifInterface.TAG_F_NUMBER))
        assertEquals("800", dst.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
        assertEquals("850/10", dst.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
        assertEquals("85", dst.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM))
        assertEquals("RF85mm F1.2 L USM", dst.getAttribute(ExifInterface.TAG_LENS_MODEL))
        assertEquals("devcxl", dst.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull(dst.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL.toString(),
            dst.getAttribute(ExifInterface.TAG_ORIENTATION)
        )
        assertEquals("8192", dst.getAttribute(ExifInterface.TAG_IMAGE_WIDTH))
        assertEquals("5464", dst.getAttribute(ExifInterface.TAG_IMAGE_LENGTH))
        assertEquals(35.6895, dst.getLatLong()!![0], 0.0001)
        assertEquals(0.0, dst.getAltitude(Double.NaN), 0.0001)
    }

    @Test
    fun shouldOmitDateTimeAndZeroNumerics_whenRawMetadataFieldsAreUnset() {
        val rawMeta = RawExifMetadata(
            make = "Nikon",
            model = "Z 8",
            timestampEpochSec = 0L,
            shutterSec = 0f,
            apertureFNumber = 0f,
            isoSpeed = 0f,
            focalLengthMm = 0f
        )

        val dst = FakeExifTagStore()
        ExifCopier.applyFromRawMetadata(
            metadata = rawMeta,
            target = dst,
            outputWidth = 1024,
            outputHeight = 768
        )

        assertEquals("Nikon", dst.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(dst.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(dst.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        assertNull(dst.getAttribute(ExifInterface.TAG_F_NUMBER))
        assertNull(dst.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
        assertEquals("1", dst.getAttribute(ExifInterface.TAG_ORIENTATION))
    }
}
