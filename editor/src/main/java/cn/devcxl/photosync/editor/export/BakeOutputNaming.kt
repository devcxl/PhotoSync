package cn.devcxl.photosync.editor.export

import java.io.File

import cn.devcxl.photosync.editor.contract.GallerySink
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode

/**
 * Pure naming and format rules for baked gallery entries.
 *
 * Single bakes and the future batch queue share these helpers so an export always lands with the
 * extension and MIME type that match its [BakeOutputFormat], regardless of the source extension.
 */
object BakeOutputNaming {

    /**
     * Picks the container a bake of [graph] must use.
     *
     * A transparent subject cut-out only survives in an alpha-capable container, so
     * [SubjectBackgroundMode.TRANSPARENT] routes to [BakeOutputFormat.PNG_RGBA]; every other graph
     * keeps the smaller baseline [BakeOutputFormat.JPEG].
     *
     * @param graph Sanitized edit graph about to be baked.
     * @return [BakeOutputFormat.PNG_RGBA] for transparent backgrounds, [BakeOutputFormat.JPEG]
     *   otherwise.
     */
    fun outputFormatFor(graph: EditGraph): BakeOutputFormat =
        if (graph.subject.bgMode == SubjectBackgroundMode.TRANSPARENT) {
            BakeOutputFormat.PNG_RGBA
        } else {
            BakeOutputFormat.JPEG
        }

    /**
     * File extension (without the dot) recorded for [format].
     *
     * @param format Container the gallery entry holds.
     * @return `jpg` for [BakeOutputFormat.JPEG], `png` for [BakeOutputFormat.PNG_RGBA].
     */
    fun extension(format: BakeOutputFormat): String = when (format) {
        BakeOutputFormat.JPEG -> "jpg"
        BakeOutputFormat.PNG_RGBA -> "png"
    }

    /**
     * MIME type published to `MediaStore` for [format].
     *
     * @param format Container the gallery entry holds.
     * @return The matching `image` MIME type from [GallerySink].
     */
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
     * @return The display name, for example `DSC04821_edited.jpg` or `DSC04821_edited.png`.
     */
    fun displayName(sourcePath: String, photoId: Long, format: BakeOutputFormat): String {
        val base = File(sourcePath).name.substringBeforeLast('.').ifBlank { "IMG_$photoId" }
        return "${base}_edited.${extension(format)}"
    }
}
