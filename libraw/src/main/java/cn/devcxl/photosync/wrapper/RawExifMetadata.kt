package cn.devcxl.photosync.wrapper

/**
 * Shooting metadata extracted from a RAW image header via LibRaw.
 *
 * All numeric fields use `0` (or `0f`) to indicate "not present in the RAW header".
 * [timestampEpochSec] is produced by LibRaw's `mktime()` using the device's local timezone
 * and must therefore be formatted back with the device's local timezone (`localtime`).
 */
data class RawExifMetadata(
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,
    val timestampEpochSec: Long = 0L,
    val shutterSec: Float = 0f,
    val apertureFNumber: Float = 0f,
    val isoSpeed: Float = 0f,
    val focalLengthMm: Float = 0f,
    val focalLengthIn35mm: Int = 0,
    val lensMake: String? = null,
    val lensModel: String? = null,
    val lensSerial: String? = null,
    val bodySerial: String? = null,
    val exposureProgram: Int = 0,
    val meteringMode: Int = 0,
    val imageDescription: String? = null,
    val artist: String? = null,
    val gpsParsed: Boolean = false,
    val gpsLatitude: Double = 0.0,
    val gpsLongitude: Double = 0.0,
    val gpsAltitude: Double = Double.NaN
)
