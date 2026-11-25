package cn.devcxl.photosync.editor.export

import android.net.Uri

import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Encoded container of a bake.
 *
 * @property JPEG Baseline JPEG, used by every opaque edit.
 * @property PNG_RGBA Lossless 8-bit RGBA PNG, used when a transparent cut-out must keep its alpha.
 */
enum class BakeOutputFormat {
    JPEG,
    PNG_RGBA
}

/**
 * Inputs of one full-resolution non-destructive bake.
 *
 * @property sourcePath Absolute path of the original JPEG or RAW file.
 * @property displayName File name to publish in the system gallery.
 * @property isRaw True when [sourcePath] is a RAW file decoded through `:libraw`.
 * @property graph The edit graph to render; an identity graph still re-renders the pixels.
 * @property outputFormat Container to encode; defaults to [BakeOutputFormat.JPEG] so existing
 *   opaque exports keep their behaviour.
 * @property photoId Photo row id used to register the bake in
 *   [cn.devcxl.photosync.editor.domain.ActivePhotoEditRegistry] so an offline AI asset sweep never
 *   deletes payloads this bake is still streaming. `null` disables the registration.
 */
data class BakeRequest(
    val sourcePath: String,
    val displayName: String,
    val isRaw: Boolean,
    val graph: EditGraph,
    val outputFormat: BakeOutputFormat = BakeOutputFormat.JPEG,
    val photoId: Long? = null
)

/**
 * Outcome of a [BakeRequest].
 */
sealed interface BakeResult {

    /**
     * The bake published successfully.
     *
     * @property uri Gallery entry, or `null` when the sink only wrote bytes.
     * @property width Exported width in pixels.
     * @property height Exported height in pixels.
     * @property displayName Name recorded in the gallery.
     */
    data class Success(
        val uri: Uri?,
        val width: Int,
        val height: Int,
        val displayName: String
    ) : BakeResult

    /**
     * The bake could not finish; the gallery entry (if any) was removed by the sink.
     *
     * @property reason Short diagnostic failure message for logging (UI displays localized messages).
     * @property cause Underlying error for logging, when available.
     */
    data class Failure(val reason: String, val cause: Throwable? = null) : BakeResult
}
