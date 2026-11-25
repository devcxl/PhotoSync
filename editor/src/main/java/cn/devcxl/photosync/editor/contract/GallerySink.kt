package cn.devcxl.photosync.editor.contract

import java.io.OutputStream

import android.net.Uri

/**
 * Minimal write seam over EXIF attributes shared between `:editor` and `:app`.
 */
interface ExifTagWriter {
    fun setAttribute(tag: String, value: String?)
    fun setLatLong(latitude: Double, longitude: Double)
    fun setAltitude(altitude: Double)
}

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

        /** Lossless RGBA container used by transparent cut-out bakes. */
        const val PNG_MIME = "image/png"

        const val GALLERY_DIR_NAME = "PhotoSync"
    }
}
