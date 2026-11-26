package cn.devcxl.photosync.editor.cloud

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

/** Upload sanitization failed before a clean, metadata-free image could be handed out. */
class CloudSanitizationException(message: String) : IOException(message)

/**
 * A ready-to-upload JPEG produced by [CloudUploadSanitizer].
 *
 * [file] lives under the app cache until [close] runs. Callers must always close the handle; the
 * sanitizer's `withSanitizedUpload` helper does this in a `finally` block.
 */
interface SanitizedUploadHandle : Closeable {
    /** Sanitized JPEG with a random, semantically empty file name. */
    val file: File

    /** Upright width in pixels. */
    val width: Int

    /** Upright height in pixels. */
    val height: Int
}

/**
 * Closed-world upload sanitizer for BYOK cloud requests.
 *
 * Every uploaded image is decoded and re-encoded from pixels: bounds are read first, the JPEG is
 * sampled down by a power of two while decoding, rotated upright from its EXIF orientation,
 * scaled so the long edge never exceeds [maxLongEdgePx], and encoded as JPEG quality 90. Because
 * `Bitmap.compress` receives no EXIF payload, GPS coordinates, camera/lens serial numbers,
 * capture timestamps, maker notes and the embedded IFD1 thumbnail cannot survive. The output is
 * read back and verified before the handle is returned.
 *
 * All methods perform bitmap and file I/O and **must be called off the main thread**.
 *
 * @param uploadDir Directory that receives sanitized JPEGs. Defaults to `cacheDir/upload`.
 * @param maxLongEdgePx Maximum upright long edge; must be positive.
 */
class CloudUploadSanitizer(
    private val uploadDir: File,
    private val maxLongEdgePx: Int = DEFAULT_MAX_LONG_EDGE_PX
) {

    /** Creates a sanitizer that stages uploads under the app's cache directory. */
    constructor(context: Context) : this(File(context.cacheDir, UPLOAD_DIR_NAME))

    init {
        require(maxLongEdgePx > 0) { "maxLongEdgePx must be positive" }
    }

    /**
     * Sanitizes [sourcePath] and returns a handle whose file must be deleted by [close].
     *
     * @throws CloudSanitizationException when the source cannot be decoded or the re-encoded file
     *   still fails the zero-sensitive-metadata verification. Messages never contain file paths.
     */
    fun createUpload(sourcePath: String): SanitizedUploadHandle {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw CloudSanitizationException("Source image could not be decoded")
        }

        val orientation = readOrientation(sourcePath)
        val sampleSize = UploadBitmapGeometry.previewSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            maxLongEdgePx
        )
        val decoded = decodeSampled(sourcePath, sampleSize)
        var upright = UploadBitmapGeometry.applyOrientation(decoded, orientation)
        upright = UploadBitmapGeometry.scaleToLongEdge(upright, maxLongEdgePx)

        ensureUploadDir()
        val token = UUID.randomUUID().toString()
        val partFile = File(uploadDir, "$token.part")
        val uploadFile = File(uploadDir, "$token.jpg")
        var published = false
        try {
            writeJpeg(upright, partFile)
            verifySanitized(partFile, maxLongEdgePx)
            if (!partFile.renameTo(uploadFile)) {
                throw CloudSanitizationException("Upload staging failed")
            }
            published = true
            return SanitizedUpload(uploadFile, upright.width, upright.height)
        } finally {
            if (!published) {
                partFile.delete()
                uploadFile.delete()
            }
            if (!upright.isRecycled) upright.recycle()
        }
    }

    /**
     * Sanitizes an in-memory frame and returns a handle whose file must be deleted by [close].
     *
     * This is the editor path: the Post-Pass A composite only exists in memory, so it is re-encoded
     * from pixels exactly like a file upload. The caller's bitmap is never recycled; only the
     * internally created scaled copy is.
     *
     * @throws CloudSanitizationException when the frame is unusable or the re-encoded file still
     *   fails the zero-sensitive-metadata verification.
     */
    fun createUpload(source: Bitmap): SanitizedUploadHandle {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            throw CloudSanitizationException("Source image could not be decoded")
        }
        val upright = UploadBitmapGeometry.scaledCopy(source, maxLongEdgePx)
        ensureUploadDir()
        val token = UUID.randomUUID().toString()
        val partFile = File(uploadDir, "$token.part")
        val uploadFile = File(uploadDir, "$token.jpg")
        var published = false
        try {
            writeJpeg(upright, partFile)
            verifySanitized(partFile, maxLongEdgePx)
            if (!partFile.renameTo(uploadFile)) {
                throw CloudSanitizationException("Upload staging failed")
            }
            published = true
            return SanitizedUpload(uploadFile, upright.width, upright.height)
        } finally {
            if (!published) {
                partFile.delete()
                uploadFile.delete()
            }
            if (upright !== source && !upright.isRecycled) upright.recycle()
        }
    }

    /**
     * Runs [block] with a sanitized upload and guarantees the staged file is deleted afterwards,
     * including when [block] throws.
     */
    fun <T> withSanitizedUpload(sourcePath: String, block: (SanitizedUploadHandle) -> T): T {
        val handle = createUpload(sourcePath)
        return try {
            block(handle)
        } finally {
            handle.close()
        }
    }

    private fun readOrientation(sourcePath: String): Int = try {
        ExifInterface(sourcePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: IOException) {
        Timber.w("Could not read the upload source orientation (%s)", e.javaClass.simpleName)
        ExifInterface.ORIENTATION_NORMAL
    } catch (e: RuntimeException) {
        Timber.w("Could not parse the upload source orientation (%s)", e.javaClass.simpleName)
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun decodeSampled(sourcePath: String, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(sourcePath, options)
            ?: throw CloudSanitizationException("Source image could not be decoded")
    }

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw CloudSanitizationException("JPEG encoding failed")
            }
            out.flush()
            out.fd.sync()
        }
    }

    private fun ensureUploadDir() {
        if (!uploadDir.isDirectory && !uploadDir.mkdirs()) {
            throw CloudSanitizationException("Upload staging directory is unavailable")
        }
    }

    /**
     * Reads the re-encoded file back and fails closed if any private metadata survived.
     * The message deliberately omits both tag values and file paths.
     */
    private fun verifySanitized(file: File, maxLongEdge: Int) {
        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (e: IOException) {
            throw CloudSanitizationException("Sanitized upload could not be verified")
        }
        if (exif.latLong != null) {
            throw CloudSanitizationException("Sanitized upload retained location metadata")
        }
        if (SENSITIVE_TAGS.any { exif.getAttribute(it) != null }) {
            throw CloudSanitizationException("Sanitized upload retained sensitive metadata")
        }
        if (exif.hasThumbnail()) {
            throw CloudSanitizationException("Sanitized upload retained an embedded thumbnail")
        }
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
        if (orientation != ExifInterface.ORIENTATION_UNDEFINED &&
            orientation != ExifInterface.ORIENTATION_NORMAL) {
            throw CloudSanitizationException("Sanitized upload retained a rotation flag")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw CloudSanitizationException("Sanitized upload could not be decoded")
        }
        if (maxOf(bounds.outWidth, bounds.outHeight) > maxLongEdge) {
            throw CloudSanitizationException("Sanitized upload exceeds the long-edge limit")
        }
    }

    private class SanitizedUpload(
        override val file: File,
        override val width: Int,
        override val height: Int
    ) : SanitizedUploadHandle {

        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            if (!file.delete() && file.exists()) {
                Timber.w("Could not delete a sanitized upload after the request finished")
            }
        }
    }

    companion object {
        /** Long edge used for every BYOK upload before re-encoding. */
        const val DEFAULT_MAX_LONG_EDGE_PX = 2048

        /** Upload staging folder name under `cacheDir`. */
        const val UPLOAD_DIR_NAME = "upload"

        private const val JPEG_QUALITY = 90

        private val SENSITIVE_TAGS = arrayOf(
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
