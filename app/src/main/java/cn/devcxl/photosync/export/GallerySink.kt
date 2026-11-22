package cn.devcxl.photosync.export

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

/**
 * Contract for publishing an exported or baked image into the system gallery under
 * `Pictures/PhotoSync`, optionally writing normalized shooting EXIF attributes before the
 * entry becomes visible to other apps.
 */
interface GallerySink {

    /**
     * Writes an image stream into the system gallery and optionally applies EXIF tags.
     *
     * @param displayName File name to publish in the gallery.
     * @param mime MIME type to record in MediaStore.
     * @param write Callback that writes the encoded image bytes to the [OutputStream].
     * @param writeExif Optional callback that populates EXIF tags after [write] closes its
     *   stream and before the image is published (`IS_PENDING = 0` on API 29+, or before
     *   atomic temp-file rename on API 24–28).
     * @return The [Uri] of the published gallery entry, or null when saving failed.
     */
    fun save(
        displayName: String,
        mime: String = JPEG_MIME,
        write: (OutputStream) -> Unit,
        writeExif: ((ExifTagWriter) -> Unit)? = null
    ): Uri?

    companion object {
        const val JPEG_MIME = "image/jpeg"
        const val GALLERY_DIR_NAME = "PhotoSync"
    }
}

/**
 * Seam for API 29+ scoped MediaStore operations (`IS_PENDING = 1` → `"w"` stream → `"rw"`
 * seekable descriptor for [ExifInterface.saveAttributes] → `IS_PENDING = 0`).
 */
internal interface ScopedMediaStoreBackend<H : Any> {
    fun insertPending(displayName: String, mime: String, relativePath: String): H?
    fun openWriteStream(handle: H): OutputStream?
    fun openRwAndSaveExif(handle: H, block: (ExifTagWriter) -> Unit)
    fun publishPending(handle: H)
    fun deleteEntry(handle: H)
}

internal fun <H : Any> saveViaScopedMediaStore(
    backend: ScopedMediaStoreBackend<H>,
    displayName: String,
    mime: String,
    relativePath: String,
    write: (OutputStream) -> Unit,
    writeExif: ((ExifTagWriter) -> Unit)?
): H? {
    val handle = try {
        backend.insertPending(displayName, mime, relativePath)
    } catch (e: Exception) {
        Timber.e(e, "insertPending failed for %s", displayName)
        null
    } ?: return null

    return try {
        val stream = backend.openWriteStream(handle)
            ?: throw IOException("openWriteStream returned null")
        stream.use { out ->
            write(out)
            out.flush()
        }
        if (writeExif != null) {
            backend.openRwAndSaveExif(handle, writeExif)
        }
        backend.publishPending(handle)
        handle
    } catch (e: Exception) {
        try {
            backend.deleteEntry(handle)
        } catch (cleanupError: Exception) {
            Timber.w(cleanupError, "Failed to clean up pending gallery entry for %s", displayName)
        }
        Timber.e(e, "saveViaScopedMediaStore failed for %s", displayName)
        null
    }
}

internal fun <H : Any> saveViaLegacyDirectory(
    targetDir: File,
    displayName: String,
    write: (OutputStream) -> Unit,
    applyExifToFile: (File, (ExifTagWriter) -> Unit) -> Unit,
    writeExif: ((ExifTagWriter) -> Unit)?,
    announceFile: (File) -> H?
): H? {
    if (!targetDir.exists() && !targetDir.mkdirs()) {
        Timber.e("Failed to create gallery directory for %s", displayName)
        return null
    }
    val tmpFile = File(targetDir, "$displayName.tmp")
    val finalFile = File(targetDir, displayName)
    return try {
        FileOutputStream(tmpFile).use { out ->
            write(out)
            out.flush()
        }
        if (writeExif != null) {
            applyExifToFile(tmpFile, writeExif)
        }
        if (finalFile.exists() && !finalFile.delete()) {
            throw IOException("Could not replace existing file for $displayName")
        }
        if (!tmpFile.renameTo(finalFile)) {
            throw IOException("Atomic rename from temp file failed for $displayName")
        }
        announceFile(finalFile)
    } catch (e: Exception) {
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        Timber.e(e, "saveViaLegacyDirectory failed for %s", displayName)
        null
    }
}

/**
 * Production [GallerySink] backed by Android [ContentResolver] and [MediaStore].
 */
class MediaStoreGallerySink(
    private val contentResolver: ContentResolver
) : GallerySink {

    private val scopedBackend = object : ScopedMediaStoreBackend<Uri> {
        override fun insertPending(displayName: String, mime: String, relativePath: String): Uri? {
            if (Build.VERSION.SDK_INT < 29) return null
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }

        override fun openWriteStream(handle: Uri): OutputStream? {
            return contentResolver.openOutputStream(handle, "w")
        }

        override fun openRwAndSaveExif(handle: Uri, block: (ExifTagWriter) -> Unit) {
            val pfd = contentResolver.openFileDescriptor(handle, "rw")
                ?: throw IOException("openFileDescriptor(rw) returned null")
            pfd.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                block(AndroidExifTagAdapter(exif))
                exif.saveAttributes()
            }
        }

        override fun publishPending(handle: Uri) {
            if (Build.VERSION.SDK_INT < 29) return
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            contentResolver.update(handle, values, null, null)
        }

        override fun deleteEntry(handle: Uri) {
            contentResolver.delete(handle, null, null)
        }
    }

    @Suppress("DEPRECATION")
    override fun save(
        displayName: String,
        mime: String,
        write: (OutputStream) -> Unit,
        writeExif: ((ExifTagWriter) -> Unit)?
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            val relativePath =
                Environment.DIRECTORY_PICTURES + "/" + GallerySink.GALLERY_DIR_NAME
            saveViaScopedMediaStore(
                backend = scopedBackend,
                displayName = displayName,
                mime = mime,
                relativePath = relativePath,
                write = write,
                writeExif = writeExif
            )
        } else {
            val pictures = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val dir = File(pictures, GallerySink.GALLERY_DIR_NAME)
            saveViaLegacyDirectory(
                targetDir = dir,
                displayName = displayName,
                write = write,
                applyExifToFile = { tmpFile, block ->
                    val exif = ExifInterface(tmpFile)
                    block(AndroidExifTagAdapter(exif))
                    exif.saveAttributes()
                },
                writeExif = writeExif,
                announceFile = { finalFile ->
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATA, finalFile.absolutePath)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    }
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                }
            )
        }
    }
}
