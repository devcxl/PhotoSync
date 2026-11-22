package cn.devcxl.photosync.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

import cn.devcxl.photosync.wrapper.RawExifMetadata

/**
 * Minimal read seam over EXIF attributes so tag-copying rules can be verified in pure JVM tests
 * without triggering Android framework stubs inside [ExifInterface]'s static initializer.
 */
interface ExifTagReader {
    fun getAttribute(tag: String): String?
    fun getLatLong(): DoubleArray?
    fun getAltitude(defaultValue: Double): Double
}

typealias ExifTagWriter = cn.devcxl.photosync.editor.contract.ExifTagWriter

/**
 * Adapter bridging AndroidX [ExifInterface] to [ExifTagReader] and [ExifTagWriter].
 */
class AndroidExifTagAdapter(private val exif: ExifInterface) : ExifTagReader, ExifTagWriter {
    override fun getAttribute(tag: String): String? = exif.getAttribute(tag)
    override fun getLatLong(): DoubleArray? = exif.latLong
    override fun getAltitude(defaultValue: Double): Double = exif.getAltitude(defaultValue)
    override fun setAttribute(tag: String, value: String?) = exif.setAttribute(tag, value)
    override fun setLatLong(latitude: Double, longitude: Double) = exif.setLatLong(latitude, longitude)
    override fun setAltitude(altitude: Double) = exif.setAltitude(altitude)
}

/**
 * Copies and normalizes shooting EXIF metadata onto newly exported gallery JPEG files.
 *
 * Orientation is always normalized to [ExifInterface.ORIENTATION_NORMAL] (`"1"`) because
 * exported pixels are already rotated upright before encoding. Binary vendor maker notes
 * ([ExifInterface.TAG_MAKER_NOTE]) are intentionally excluded because string round-tripping
 * corrupts their IFD offsets.
 */
object ExifCopier {

    private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"

    private val WHITELISTED_JPEG_TAGS = arrayOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_ARTIST
    )

    /**
     * Copies whitelisted shooting EXIF tags from a source JPEG file at [sourcePath] into
     * [target], normalizes orientation to `"1"`, and updates dimensions when positive.
     */
    fun copyFromJpeg(
        sourcePath: String,
        target: ExifTagWriter,
        outputWidth: Int = 0,
        outputHeight: Int = 0
    ) {
        copyFromReader(
            source = AndroidExifTagAdapter(ExifInterface(sourcePath)),
            target = target,
            outputWidth = outputWidth,
            outputHeight = outputHeight
        )
    }

    /**
     * Copies whitelisted shooting EXIF tags from [source] into [target], normalizes
     * orientation to `"1"`, excludes [ExifInterface.TAG_MAKER_NOTE], and writes dimensions.
     */
    fun copyFromReader(
        source: ExifTagReader,
        target: ExifTagWriter,
        outputWidth: Int = 0,
        outputHeight: Int = 0
    ) {
        for (tag in WHITELISTED_JPEG_TAGS) {
            val value = source.getAttribute(tag)
            if (!value.isNullOrBlank()) {
                target.setAttribute(tag, value)
            }
        }
        source.getLatLong()?.let { coords ->
            if (coords.size >= 2) {
                target.setLatLong(coords[0], coords[1])
            }
        }
        val altitude = source.getAltitude(Double.NaN)
        if (!altitude.isNaN()) {
            target.setAltitude(altitude)
        }
        target.setAttribute(ExifInterface.TAG_MAKER_NOTE, null)
        applyNormalizedGeometry(target, outputWidth, outputHeight)
    }

    /**
     * Populates [target] from [RawExifMetadata] decoded by LibRaw, decoding the
     * timestamp in the device's local timezone to match LibRaw's `mktime()` parser.
     */
    fun applyFromRawMetadata(
        metadata: RawExifMetadata,
        target: ExifTagWriter,
        outputWidth: Int,
        outputHeight: Int
    ) {
        setCleanString(target, ExifInterface.TAG_MAKE, metadata.make)
        setCleanString(target, ExifInterface.TAG_MODEL, metadata.model)
        setCleanString(target, ExifInterface.TAG_SOFTWARE, metadata.software)
        setCleanString(target, ExifInterface.TAG_LENS_MAKE, metadata.lensMake)
        setCleanString(target, ExifInterface.TAG_LENS_MODEL, metadata.lensModel)
        setCleanString(target, ExifInterface.TAG_LENS_SERIAL_NUMBER, metadata.lensSerial)
        setCleanString(target, ExifInterface.TAG_BODY_SERIAL_NUMBER, metadata.bodySerial)
        setCleanString(target, ExifInterface.TAG_IMAGE_DESCRIPTION, metadata.imageDescription)
        setCleanString(target, ExifInterface.TAG_ARTIST, metadata.artist)

        if (metadata.timestampEpochSec > 0L) {
            val formatter = SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val dateString = formatter.format(Date(metadata.timestampEpochSec * 1000L))
            target.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
            target.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateString)
            target.setAttribute(ExifInterface.TAG_DATETIME, dateString)
        }

        applyRawExposureTags(metadata, target)
        applyRawLensAndGpsTags(metadata, target)
        applyNormalizedGeometry(target, outputWidth, outputHeight)
    }

    private fun applyRawExposureTags(metadata: RawExifMetadata, target: ExifTagWriter) {
        if (metadata.shutterSec > 0f && metadata.shutterSec.isFinite()) {
            target.setAttribute(
                ExifInterface.TAG_EXPOSURE_TIME,
                String.format(Locale.US, "%.6f", metadata.shutterSec)
            )
        }
        if (metadata.apertureFNumber > 0f && metadata.apertureFNumber.isFinite()) {
            target.setAttribute(
                ExifInterface.TAG_F_NUMBER,
                String.format(Locale.US, "%.1f", metadata.apertureFNumber)
            )
        }
        if (metadata.isoSpeed > 0f && metadata.isoSpeed.isFinite()) {
            target.setAttribute(
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                metadata.isoSpeed.roundToInt().toString()
            )
        }
        if (metadata.exposureProgram > 0) {
            target.setAttribute(
                ExifInterface.TAG_EXPOSURE_PROGRAM,
                metadata.exposureProgram.toString()
            )
        }
        if (metadata.meteringMode > 0) {
            target.setAttribute(
                ExifInterface.TAG_METERING_MODE,
                metadata.meteringMode.toString()
            )
        }
    }

    private fun applyRawLensAndGpsTags(metadata: RawExifMetadata, target: ExifTagWriter) {
        if (metadata.focalLengthMm > 0f && metadata.focalLengthMm.isFinite()) {
            val tenths = (metadata.focalLengthMm * 10f).roundToInt()
            target.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "$tenths/10")
        }
        if (metadata.focalLengthIn35mm > 0) {
            target.setAttribute(
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                metadata.focalLengthIn35mm.toString()
            )
        }
        if (metadata.gpsParsed) {
            try {
                target.setLatLong(metadata.gpsLatitude, metadata.gpsLongitude)
                if (!metadata.gpsAltitude.isNaN()) {
                    target.setAltitude(metadata.gpsAltitude)
                }
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Skipped out-of-range RAW GPS coordinates")
            }
        }
    }

    private fun applyNormalizedGeometry(
        target: ExifTagWriter,
        outputWidth: Int,
        outputHeight: Int
    ) {
        target.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        if (outputWidth > 0 && outputHeight > 0) {
            target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, outputWidth.toString())
            target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, outputHeight.toString())
        }
    }

    private fun setCleanString(target: ExifTagWriter, tag: String, rawValue: String?) {
        val cleaned = rawValue
            ?.trim { it <= ' ' || it.isISOControl() }
            ?.takeIf { str -> str.any { !it.isISOControl() && !it.isWhitespace() } }
        if (cleaned != null) {
            target.setAttribute(tag, cleaned)
        }
    }
}
