package cn.devcxl.photosync.activity

/**
 * Immutable snapshot of the file a preview decode must read.
 *
 * [MainActivity] resolves the edited-preview override while starting the decode, on the thread
 * that owns the adapter list and the edited-photo map. The decode coroutine only touches this
 * snapshot after it switches to an IO dispatcher, so it never reads those shared structures from
 * a background thread.
 *
 * When [isPreviewUnavailable] is true the edit record exists but has no usable preview. Decode
 * must then produce nothing: reading [path] would surface the original pixels of a photo the
 * user edited.
 */
internal data class PreviewDecodeSource(
    /** Library photo path; also the cache key of the decoded bitmap. */
    val path: String,
    /** Rendered edit preview to decode instead of [path], or `null` to decode the original. */
    val previewOverridePath: String?,
    /** True when the edit record's preview cannot represent the edit; decode nothing. */
    val isPreviewUnavailable: Boolean = false
)
