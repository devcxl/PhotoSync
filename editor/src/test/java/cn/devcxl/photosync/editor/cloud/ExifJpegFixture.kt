package cn.devcxl.photosync.editor.cloud

import java.io.ByteArrayOutputStream
import java.io.File

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Builds real JPEGs with a hand-written EXIF APP1 segment: GPS coordinates, camera make/model,
 * body and lens serial numbers, a capture timestamp, a non-normal orientation and an embedded
 * IFD1 JPEG thumbnail. Used to prove the sanitizer drops every one of them.
 */
internal object ExifJpegFixture {

    /** Writes a camera-style JPEG at [width] x [height] with the given EXIF [orientation]. */
    fun writeCameraJpeg(
        target: File,
        width: Int,
        height: Int,
        orientation: Int,
        withThumbnail: Boolean = true
    ) {
        val base = encodeJpeg(width, height, quality = 85)
        val thumbnail = if (withThumbnail) encodeJpeg(32, 24, quality = 60) else null
        val app1 = buildApp1Segment(orientation, thumbnail)
        target.outputStream().use { out ->
            out.write(SOI_MARKER_FIRST)
            out.write(SOI_MARKER_SECOND)
            out.write(app1)
            out.write(base, 2, base.size - 2)
        }
    }

    private fun encodeJpeg(width: Int, height: Int, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(40, 80, 120))
            val paint = Paint().apply { color = Color.rgb(220, 120, 40) }
            canvas.drawRect(width / 4f, height / 4f, width * 3 / 4f, height * 3 / 4f, paint)
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                "Test fixture JPEG encoding failed"
            }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildApp1Segment(orientation: Int, thumbnail: ByteArray?): ByteArray {
        val tiff = buildTiff(orientation, thumbnail)
        val exifHeader = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
        val payloadSize = exifHeader.size + tiff.size
        val segment = ByteArray(4 + payloadSize)
        segment[0] = 0xFF.toByte()
        segment[1] = 0xE1.toByte()
        val length = payloadSize + 2
        segment[2] = (length ushr 8).toByte()
        segment[3] = (length and 0xFF).toByte()
        exifHeader.copyInto(segment, destinationOffset = 4)
        tiff.copyInto(segment, destinationOffset = 4 + exifHeader.size)
        return segment
    }

    private fun buildTiff(orientation: Int, thumbnail: ByteArray?): ByteArray {
        val ifd0Offset = 8
        val probeIfd0 = buildIfd(
            ifd0Offset,
            listOf(
                asciiEntry(TAG_MAKE, "PhotoSync Test Camera"),
                asciiEntry(TAG_MODEL, "PS-1"),
                shortEntry(TAG_ORIENTATION, orientation),
                longEntry(TAG_EXIF_IFD_POINTER, 0),
                longEntry(TAG_GPS_IFD_POINTER, 0)
            ),
            nextIfdOffset = 0
        )
        val exifOffset = ifd0Offset + probeIfd0.size
        val exifIfd = buildIfd(
            exifOffset,
            listOf(
                asciiEntry(TAG_DATETIME_ORIGINAL, "2024:05:06 07:08:09"),
                asciiEntry(TAG_BODY_SERIAL_NUMBER, "BODY-SERIAL-42"),
                asciiEntry(TAG_LENS_SERIAL_NUMBER, "LENS-SERIAL-99")
            ),
            nextIfdOffset = 0
        )
        val gpsOffset = exifOffset + exifIfd.size
        val gpsIfd = buildIfd(
            gpsOffset,
            listOf(
                asciiEntry(TAG_GPS_LATITUDE_REF, "N"),
                rationalEntry(TAG_GPS_LATITUDE, listOf(37 to 1, 48 to 1, 30 to 1)),
                asciiEntry(TAG_GPS_LONGITUDE_REF, "W"),
                rationalEntry(TAG_GPS_LONGITUDE, listOf(122 to 1, 25 to 1, 0 to 1))
            ),
            nextIfdOffset = 0
        )
        val ifd1Offset = gpsOffset + gpsIfd.size
        val ifd1 = buildIfd(
            ifd1Offset,
            if (thumbnail == null) {
                emptyList()
            } else {
                listOf(
                    shortEntry(TAG_COMPRESSION, COMPRESSION_JPEG),
                    longEntry(TAG_JPEG_INTERCHANGE_FORMAT, 0),
                    longEntry(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, thumbnail.size)
                )
            },
            nextIfdOffset = 0
        )
        val thumbnailOffset = ifd1Offset + ifd1.size
        val actualIfd1 = if (thumbnail == null) {
            null
        } else {
            buildIfd(
                ifd1Offset,
                listOf(
                    shortEntry(TAG_COMPRESSION, COMPRESSION_JPEG),
                    longEntry(TAG_JPEG_INTERCHANGE_FORMAT, thumbnailOffset),
                    longEntry(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, thumbnail.size)
                ),
                nextIfdOffset = 0
            )
        }
        val ifd0 = buildIfd(
            ifd0Offset,
            listOf(
                asciiEntry(TAG_MAKE, "PhotoSync Test Camera"),
                asciiEntry(TAG_MODEL, "PS-1"),
                shortEntry(TAG_ORIENTATION, orientation),
                longEntry(TAG_EXIF_IFD_POINTER, exifOffset),
                longEntry(TAG_GPS_IFD_POINTER, gpsOffset)
            ),
            nextIfdOffset = if (thumbnail == null) 0 else ifd1Offset
        )

        val out = ByteArrayOutputStream()
        out.write(TIFF_LITTLE_ENDIAN)
        out.write(TIFF_LITTLE_ENDIAN)
        out.writeShortLe(TIFF_MAGIC)
        out.writeIntLe(TIFF_HEADER_SIZE)
        out.write(ifd0)
        out.write(exifIfd)
        out.write(gpsIfd)
        actualIfd1?.let(out::write)
        thumbnail?.let(out::write)
        return out.toByteArray()
    }

    private fun buildIfd(baseOffset: Int, entries: List<TiffEntry>, nextIfdOffset: Int): ByteArray {
        val sorted = entries.sortedBy { it.tag }
        val tableSize = 2 + sorted.size * 12 + 4
        var dataOffset = baseOffset + tableSize
        val inline = arrayOfNulls<ByteArray>(sorted.size)
        val externalOffset = IntArray(sorted.size) { -1 }
        val externalData = ByteArrayOutputStream()
        for ((index, entry) in sorted.withIndex()) {
            if (entry.data.size <= 4) {
                inline[index] = entry.data.copyOf(4)
            } else {
                externalOffset[index] = dataOffset
                externalData.write(entry.data)
                val padding = (4 - entry.data.size % 4) % 4
                repeat(padding) { externalData.write(0) }
                dataOffset += entry.data.size + padding
            }
        }

        val out = ByteArrayOutputStream()
        out.writeShortLe(sorted.size)
        for ((index, entry) in sorted.withIndex()) {
            out.writeShortLe(entry.tag)
            out.writeShortLe(entry.type)
            out.writeIntLe(entry.count)
            val inlineBytes = inline[index]
            if (inlineBytes != null) out.write(inlineBytes) else out.writeIntLe(externalOffset[index])
        }
        out.writeIntLe(nextIfdOffset)
        out.write(externalData.toByteArray())
        return out.toByteArray()
    }

    private fun asciiEntry(tag: Int, value: String): TiffEntry {
        val bytes = (value + "\u0000").toByteArray(Charsets.US_ASCII)
        return TiffEntry(tag, TYPE_ASCII, bytes.size, bytes)
    }

    private fun shortEntry(tag: Int, value: Int): TiffEntry {
        val bytes = ByteArray(2)
        bytes[0] = (value and 0xFF).toByte()
        bytes[1] = ((value ushr 8) and 0xFF).toByte()
        return TiffEntry(tag, TYPE_SHORT, 1, bytes)
    }

    private fun longEntry(tag: Int, value: Int): TiffEntry {
        val bytes = ByteArray(4)
        bytes[0] = (value and 0xFF).toByte()
        bytes[1] = ((value ushr 8) and 0xFF).toByte()
        bytes[2] = ((value ushr 16) and 0xFF).toByte()
        bytes[3] = ((value ushr 24) and 0xFF).toByte()
        return TiffEntry(tag, TYPE_LONG, 1, bytes)
    }

    private fun rationalEntry(tag: Int, values: List<Pair<Int, Int>>): TiffEntry {
        val out = ByteArrayOutputStream()
        for ((numerator, denominator) in values) {
            out.writeIntLe(numerator)
            out.writeIntLe(denominator)
        }
        return TiffEntry(tag, TYPE_RATIONAL, values.size, out.toByteArray())
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private data class TiffEntry(
        val tag: Int,
        val type: Int,
        val count: Int,
        val data: ByteArray
    )

    private const val SOI_MARKER_FIRST = 0xFF
    private const val SOI_MARKER_SECOND = 0xD8
    private const val TIFF_LITTLE_ENDIAN = 0x49
    private const val TIFF_MAGIC = 42
    private const val TIFF_HEADER_SIZE = 8

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5

    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_EXIF_IFD_POINTER = 0x8769
    private const val TAG_GPS_IFD_POINTER = 0x8825
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_BODY_SERIAL_NUMBER = 0xA431
    private const val TAG_LENS_SERIAL_NUMBER = 0xA435
    private const val TAG_GPS_LATITUDE_REF = 0x0001
    private const val TAG_GPS_LATITUDE = 0x0002
    private const val TAG_GPS_LONGITUDE_REF = 0x0003
    private const val TAG_GPS_LONGITUDE = 0x0004
    private const val TAG_COMPRESSION = 0x0103
    private const val TAG_JPEG_INTERCHANGE_FORMAT = 0x0201
    private const val TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = 0x0202
    private const val COMPRESSION_JPEG = 6
}
