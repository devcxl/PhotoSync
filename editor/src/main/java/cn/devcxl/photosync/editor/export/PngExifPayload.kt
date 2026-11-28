package cn.devcxl.photosync.editor.export

import java.io.IOException

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.ExifTagWriter

/**
 * Builds the bare TIFF/EXIF payload of a PNG `eXIf` chunk from the shooting metadata of a bake.
 *
 * The payload is assembled in memory (a few hundred bytes) so [PngStripSink] can write it before
 * the first `IDAT` instead of reopening the gallery entry to patch EXIF afterwards. Only the
 * "basic shooting" tags are encoded: camera make/model, capture timestamps and offsets, lens
 * descriptors, exposure triangle, dimensions and a normalized `Orientation = 1`. GPS coordinates
 * are intentionally not copied so a transparent cut-out cannot leak the capture location.
 */
internal object PngExifPayload {

    private const val TIFF_HEADER_SIZE = 8
    private const val IFD_ENTRY_SIZE = 12
    private const val IFD_TRAILER_SIZE = 4
    private const val INLINE_VALUE_SIZE = 4

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7

    private const val TAG_IMAGE_WIDTH = 0x0100
    private const val TAG_IMAGE_LENGTH = 0x0101
    private const val TAG_IMAGE_DESCRIPTION = 0x010E
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_SOFTWARE = 0x0131
    private const val TAG_DATETIME = 0x0132
    private const val TAG_ARTIST = 0x013B
    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_F_NUMBER = 0x829D
    private const val TAG_EXPOSURE_PROGRAM = 0x8822
    private const val TAG_PHOTOGRAPHIC_SENSITIVITY = 0x8827
    private const val TAG_EXIF_IFD_POINTER = 0x8769
    private const val TAG_EXIF_VERSION = 0x9000
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_DATETIME_DIGITIZED = 0x9004
    private const val TAG_OFFSET_TIME = 0x9010
    private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
    private const val TAG_OFFSET_TIME_DIGITIZED = 0x9012
    private const val TAG_METERING_MODE = 0x9207
    private const val TAG_FLASH = 0x9209
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_PIXEL_X_DIMENSION = 0xA002
    private const val TAG_PIXEL_Y_DIMENSION = 0xA003
    private const val TAG_BODY_SERIAL_NUMBER = 0xA431
    private const val TAG_LENS_MAKE = 0xA433
    private const val TAG_LENS_MODEL = 0xA434
    private const val TAG_LENS_SERIAL_NUMBER = 0xA435
    private const val TAG_FOCAL_LENGTH_IN_35MM_FILM = 0xA405

    private val IFD0_ASCII_TAGS = mapOf(
        ExifInterface.TAG_IMAGE_DESCRIPTION to TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_MAKE to TAG_MAKE,
        ExifInterface.TAG_MODEL to TAG_MODEL,
        ExifInterface.TAG_SOFTWARE to TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME to TAG_DATETIME,
        ExifInterface.TAG_ARTIST to TAG_ARTIST
    )

    private val EXIF_ASCII_TAGS = mapOf(
        ExifInterface.TAG_DATETIME_ORIGINAL to TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED to TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME to TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL to TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED to TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_BODY_SERIAL_NUMBER to TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE to TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL to TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER to TAG_LENS_SERIAL_NUMBER
    )

    private val EXIF_SHORT_TAGS = mapOf(
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_PROGRAM to TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE to TAG_METERING_MODE,
        ExifInterface.TAG_FLASH to TAG_FLASH
    )

    private val EXIF_RATIONAL_TAGS = mapOf(
        ExifInterface.TAG_EXPOSURE_TIME to TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER to TAG_F_NUMBER,
        ExifInterface.TAG_FOCAL_LENGTH to TAG_FOCAL_LENGTH
    )

    /**
     * Collects the normalized shooting tags of [sourcePath] and encodes them as a TIFF payload.
     *
     * @return The eXIf payload, or `null` when the source metadata cannot be read; the export then
     *   continues without EXIF rather than failing the whole bake.
     */
    fun fromSource(sourcePath: String, isRaw: Boolean, width: Int, height: Int): ByteArray? {
        val tags = LinkedHashMap<String, String>()
        return try {
            BakeExifWriter.apply(sourcePath, isRaw, width, height, MapExifTagWriter(tags))
            build(tags, width, height)
        } catch (io: IOException) {
            Timber.w(io, "PNG eXIf payload skipped: source metadata is unreadable")
            null
        } catch (runtime: RuntimeException) {
            Timber.w(runtime, "PNG eXIf payload skipped: source metadata is unreadable")
            null
        }
    }

    /**
     * Encodes [tags] and the output dimensions as a little-endian TIFF block.
     *
     * `Orientation` is always written as `1` (upright) and `ImageWidth`/`ImageLength` always come
     * from [width]/[height], so stale source values can never survive a rotated or cropped bake.
     *
     * @param tags EXIF tag names (`ExifInterface.TAG_*`) mapped to their string values.
     * @param width Exported width in pixels.
     * @param height Exported height in pixels.
     */
    fun build(tags: Map<String, String>, width: Int, height: Int): ByteArray {
        val ifd0 = mutableListOf<Entry>()
        val exifIfd = mutableListOf<Entry>()
        ifd0 += Entry(TAG_IMAGE_WIDTH, TYPE_LONG, 1, longBytes(width))
        ifd0 += Entry(TAG_IMAGE_LENGTH, TYPE_LONG, 1, longBytes(height))
        // Baked pixels are already upright; the source orientation must never be re-applied.
        ifd0 += Entry(TAG_ORIENTATION, TYPE_SHORT, 1, shortBytes(ExifInterface.ORIENTATION_NORMAL))
        addAscii(ifd0, tags, IFD0_ASCII_TAGS)
        addAscii(exifIfd, tags, EXIF_ASCII_TAGS)
        addShort(exifIfd, tags, EXIF_SHORT_TAGS)
        addRational(exifIfd, tags, EXIF_RATIONAL_TAGS)
        exifIfd += Entry(TAG_EXIF_VERSION, TYPE_UNDEFINED, 4, "0232".toByteArray(Charsets.US_ASCII))
        exifIfd += Entry(TAG_PIXEL_X_DIMENSION, TYPE_LONG, 1, longBytes(width))
        exifIfd += Entry(TAG_PIXEL_Y_DIMENSION, TYPE_LONG, 1, longBytes(height))

        val ifd0Offset = TIFF_HEADER_SIZE
        // Size IFD0 with the ExifIFD pointer slot even though its value is only known afterwards.
        val exifIfdOffset = ifd0Offset + ifdSize(ifd0.size + 1)
        ifd0 += Entry(TAG_EXIF_IFD_POINTER, TYPE_LONG, 1, longBytes(exifIfdOffset))
        ifd0.sortBy { it.tag }
        exifIfd.sortBy { it.tag }

        val ifd0ValuesOffset = exifIfdOffset + ifdSize(exifIfd.size)
        val exifValuesOffset = ifd0ValuesOffset + valuesSize(ifd0)
        val totalSize = exifValuesOffset + valuesSize(exifIfd)

        val output = ByteArray(totalSize)
        output[0] = 'I'.code.toByte()
        output[1] = 'I'.code.toByte()
        writeShort(output, 2, TIFF_MAGIC)
        writeInt(output, 4, ifd0Offset)
        writeIfd(output, ifd0Offset, ifd0, ifd0ValuesOffset)
        writeIfd(output, exifIfdOffset, exifIfd, exifValuesOffset)
        return output
    }

    private fun addAscii(
        entries: MutableList<Entry>,
        tags: Map<String, String>,
        mapping: Map<String, Int>
    ) {
        for ((name, tagId) in mapping) {
            val value = tags[name]?.takeIf { it.isNotBlank() } ?: continue
            val payload = asciiBytes(value)
            entries += Entry(tagId, TYPE_ASCII, payload.size, payload)
        }
    }

    private fun addShort(
        entries: MutableList<Entry>,
        tags: Map<String, String>,
        mapping: Map<String, Int>
    ) {
        for ((name, tagId) in mapping) {
            val value = tags[name]?.trim()?.toIntOrNull() ?: continue
            if (value < 0 || value > 0xFFFF) continue
            entries += Entry(tagId, TYPE_SHORT, 1, shortBytes(value))
        }
    }

    private fun addRational(
        entries: MutableList<Entry>,
        tags: Map<String, String>,
        mapping: Map<String, Int>
    ) {
        for ((name, tagId) in mapping) {
            val payload = tags[name]?.let(::rationalBytes) ?: continue
            entries += Entry(tagId, TYPE_RATIONAL, 1, payload)
        }
    }

    private fun writeIfd(
        output: ByteArray,
        offset: Int,
        entries: List<Entry>,
        valuesOffset: Int
    ) {
        var position = offset
        writeShort(output, position, entries.size)
        position += 2
        var valuePosition = valuesOffset
        for (entry in entries) {
            writeShort(output, position, entry.tag)
            writeShort(output, position + 2, entry.type)
            writeInt(output, position + 4, entry.count)
            if (entry.value.size <= INLINE_VALUE_SIZE) {
                entry.value.copyInto(output, position + 8)
            } else {
                writeInt(output, position + 8, valuePosition)
                entry.value.copyInto(output, valuePosition)
                valuePosition += alignToWord(entry.value.size)
            }
            position += IFD_ENTRY_SIZE
        }
        writeInt(output, position, 0)
    }

    private fun ifdSize(entryCount: Int): Int =
        2 + entryCount * IFD_ENTRY_SIZE + IFD_TRAILER_SIZE

    private fun valuesSize(entries: List<Entry>): Int =
        entries.sumOf { if (it.value.size > INLINE_VALUE_SIZE) alignToWord(it.value.size) else 0 }

    private fun alignToWord(size: Int): Int = (size + 1) and 1.inv()

    private fun asciiBytes(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.ISO_8859_1)
        return raw.copyOf(raw.size + 1)
    }

    private fun shortBytes(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun longBytes(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )

    private fun rationalBytes(raw: String): ByteArray? {
        val trimmed = raw.trim()
        val numerator: Long
        val denominator: Long
        if ('/' in trimmed) {
            val parts = trimmed.split('/')
            if (parts.size != 2) return null
            numerator = parts[0].trim().toDoubleOrNull()?.toLong() ?: return null
            denominator = parts[1].trim().toDoubleOrNull()?.toLong() ?: return null
        } else {
            val value = trimmed.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value < 0.0) return null
            numerator = kotlin.math.round(value * RATIONAL_SCALE).toLong()
            denominator = RATIONAL_SCALE.toLong()
        }
        if (numerator < 0 || denominator <= 0) return null
        val divisor = greatestCommonDivisor(numerator, denominator).coerceAtLeast(1)
        return longBytes((numerator / divisor).toInt()) + longBytes((denominator / divisor).toInt())
    }

    private tailrec fun greatestCommonDivisor(left: Long, right: Long): Long =
        if (right == 0L) left else greatestCommonDivisor(right, left % right)

    private fun writeShort(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value shr 8) and 0xFF).toByte()
        output[offset + 2] = ((value shr 16) and 0xFF).toByte()
        output[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

    private class MapExifTagWriter(private val tags: MutableMap<String, String>) : ExifTagWriter {
        override fun setAttribute(tag: String, value: String?) {
            if (value == null) tags.remove(tag) else tags[tag] = value
        }

        override fun setLatLong(latitude: Double, longitude: Double) = Unit

        override fun setAltitude(altitude: Double) = Unit
    }

    private const val TIFF_MAGIC = 42
    private const val RATIONAL_SCALE = 1_000_000.0
}
