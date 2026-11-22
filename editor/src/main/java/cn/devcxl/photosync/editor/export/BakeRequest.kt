package cn.devcxl.photosync.editor.export

import android.net.Uri

import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Inputs of one full-resolution non-destructive bake.
 *
 * @property sourcePath Absolute path of the original JPEG or RAW file.
 * @property displayName File name to publish in the system gallery.
 * @property isRaw True when [sourcePath] is a RAW file decoded through `:libraw`.
 * @property graph The edit graph to render; an identity graph still re-renders the pixels.
 */
data class BakeRequest(
    val sourcePath: String,
    val displayName: String,
    val isRaw: Boolean,
    val graph: EditGraph
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
     * @property reason Short, user-presentable failure reason.
     * @property cause Underlying error for logging, when available.
     */
    data class Failure(val reason: String, val cause: Throwable? = null) : BakeResult
}
