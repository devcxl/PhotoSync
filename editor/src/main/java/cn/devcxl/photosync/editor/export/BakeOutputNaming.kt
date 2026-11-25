package cn.devcxl.photosync.editor.export

import java.io.File

import cn.devcxl.photosync.editor.contract.GallerySink

/**
 * Pure naming rules for baked gallery entries.
 *
 * Single bakes and the future batch queue share these helpers so an export always lands with the
 * extension and MIME type that match its [BakeOutputFormat], regardless of the source extension.
 */
object BakeOutputNaming {

    /** File extension (without the dot) recorded for [format]. */
    fun extension(format: BakeOutputFormat): String = when (format) {
        BakeOutputFormat.JPEG -> "jpg"
        BakeOutputFormat.PNG_RGBA -> "png"
    }

    /** MIME type published to `MediaStore` for [format]. */
    fun mimeType(format: BakeOutputFormat): String = when (format) {
        BakeOutputFormat.JPEG -> GallerySink.JPEG_MIME
        BakeOutputFormat.PNG_RGBA -> GallerySink.PNG_MIME
    }

    /**
     * Builds the `_edited` gallery name for [sourcePath].
     *
     * @param sourcePath Original JPEG/RAW path; only its file name is used.
     * @param photoId Photo id used when the source name is blank.
     * @param format Output format whose extension is appended.
     */
    fun displayName(sourcePath: String, photoId: Long, format: BakeOutputFormat): String {
        val base = File(sourcePath).name.substringBeforeLast('.').ifBlank { "IMG_$photoId" }
        return "${base}_edited.${extension(format)}"
    }
}
