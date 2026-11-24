package cn.devcxl.photosync.activity

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Drops the thumbnail/full preview artifacts for [path]: in-flight decode ownership and cached
 * bitmaps.
 *
 * Revoking the tasks is what keeps a decode that started while the page was still allowed to read
 * the original from publishing after the edit record turned unavailable. Without it, a late
 * original bitmap could sit in the cache and be mistaken for the graded preview once the preview
 * file becomes readable again. Clearing the caches removes images decoded before the change.
 */
internal fun discardPreviewArtifacts(
    path: String,
    thumbnailDecodes: PreviewTaskRegistry,
    fullPreviewDecodes: PreviewTaskRegistry,
    thumbnailCache: LruCache<String, Bitmap>,
    fullCache: LruCache<String, Bitmap>
) {
    // Revoke first: no in-flight task may publish between the termination of its ownership and
    // the cache drops.
    thumbnailDecodes.invalidate(path)
    fullPreviewDecodes.invalidate(path)
    thumbnailCache.remove(path)
    fullCache.remove(path)
}
