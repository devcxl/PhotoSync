package cn.devcxl.photosync.adapter

import android.graphics.Bitmap

/**
 * Pure render decisions for the pager.
 *
 * These types and functions contain no view access so the adapter, the tiled binder and the JVM
 * tests all share one source of truth for what a bind must do (which stage to show, when to
 * re-apply an image, whether the loading pill is visible).
 */

/**
 * Immutable identity of the content a [PhotoRenderState] describes.
 *
 * The holder compares the version of consecutive binds of the same path. Equal versions describe
 * the same pixels, so a thumbnail may still be upgraded to the full preview in place. A different
 * version means the content itself changed — a new edit was saved, the edit was reset, or the
 * preview file went missing — so every bitmap and deferred frame produced for the earlier version
 * is stale and must be dropped, even while the PhotoView is zoomed.
 *
 * Values come from the edit record and the resolved preview source at bind time (see
 * `resolvePhotoContentVersion` in the activity layer). The shape set is closed and carries no
 * counter, registry or timestamp, so the possible versions stay bounded by the records that
 * actually exist and two binds of the same record always compare equal.
 */
sealed interface PhotoContentVersion {
    /** No edit record exists: the original file is the intended content. */
    data object Original : PhotoContentVersion

    /**
     * The rendered edit preview at [previewPath] is the intended content.
     *
     * The editor writes every rendered preview to a fresh unique file, so the exact path is itself
     * the content identity; there is no need to hash the graph or the pixels.
     */
    data class Edited(val previewPath: String) : PhotoContentVersion

    /** The record exists but its preview cannot represent the edit (missing or unreadable file). */
    data object PreviewUnavailable : PhotoContentVersion

    /**
     * The provider tracks no content identity.
     *
     * Every bind of the same path is then treated as the same content, which is the pre-version
     * behaviour. Only a provider that knows its record should send a real version.
     */
    data object Unspecified : PhotoContentVersion
}

/**
 * Bitmaps a bind may show, plus the "edit record exists but its preview is gone" signal.
 *
 * While [isPreviewUnavailable] is true the bitmaps are ignored: the page must clear whatever it
 * showed before and display the recovery message, never pixels decoded from the original file.
 */
data class PhotoRenderState(
    val thumbnail: Bitmap?,
    val full: Bitmap?,
    val isPreviewUnavailable: Boolean = false,
    /**
     * Identity of the content [thumbnail] and [full] were decoded from. A bind of the same path
     * with a different version must drop the previous drawable and any deferred full frame before
     * showing what the new state offers.
     */
    val contentVersion: PhotoContentVersion = PhotoContentVersion.Unspecified
)

data class JpegSourceInfo(
    val width: Int,
    val height: Int
)

enum class PhotoViewerMode {
    PHOTO_VIEW,
    JPEG_TILED
}

internal enum class JpegTiledSourceMode {
    BITMAP_ONLY,
    TILED_WITH_PREVIEW,
    TILED_ORIGINAL_ONLY
}

internal enum class PhotoRenderStage {
    EMPTY,
    THUMBNAIL,
    FULL,

    /** The edit record cannot supply a preview; the holder shows the recovery message. */
    UNAVAILABLE
}

internal data class PhotoRenderDecision(
    val stage: PhotoRenderStage,
    val shouldApplyImmediately: Boolean
)

internal data class JpegTiledBindDecision(
    val stage: PhotoRenderStage,
    val shouldSetImage: Boolean
)

internal fun resolveJpegPreviewStage(renderState: PhotoRenderState): PhotoRenderStage {
    return when {
        renderState.isPreviewUnavailable -> PhotoRenderStage.UNAVAILABLE
        renderState.full != null -> PhotoRenderStage.FULL
        renderState.thumbnail != null -> PhotoRenderStage.THUMBNAIL
        else -> PhotoRenderStage.EMPTY
    }
}

internal fun resolvePhotoRenderDecision(
    currentPath: String?,
    currentStage: PhotoRenderStage,
    currentContentVersion: PhotoContentVersion,
    nextPath: String,
    nextContentVersion: PhotoContentVersion,
    hasThumbnail: Boolean,
    hasFull: Boolean,
    isZoomed: Boolean,
    hasPreviewUnavailable: Boolean = false
): PhotoRenderDecision {
    if (hasPreviewUnavailable) {
        // Force a clear even when the holder already shows this path: the bitmap on screen may be
        // the original or a pre-edit preview that the new record invalidated.
        return PhotoRenderDecision(PhotoRenderStage.UNAVAILABLE, true)
    }
    val targetStage = when {
        hasFull -> PhotoRenderStage.FULL
        hasThumbnail -> PhotoRenderStage.THUMBNAIL
        else -> PhotoRenderStage.EMPTY
    }
    if (currentPath != nextPath) {
        return PhotoRenderDecision(targetStage, true)
    }
    if (currentContentVersion != nextContentVersion) {
        // Same file, different content: whatever is on screen (including a full frame deferred
        // under the old zoom) belongs to the previous version. Show what the new state offers now
        // instead of keeping stale pixels until the gesture ends.
        return PhotoRenderDecision(targetStage, true)
    }
    if (currentStage == PhotoRenderStage.FULL && targetStage != PhotoRenderStage.FULL) {
        return PhotoRenderDecision(PhotoRenderStage.FULL, false)
    }
    if (currentStage == targetStage) {
        return PhotoRenderDecision(targetStage, false)
    }
    if (currentStage == PhotoRenderStage.THUMBNAIL &&
        targetStage == PhotoRenderStage.FULL &&
        isZoomed
    ) {
        return PhotoRenderDecision(PhotoRenderStage.FULL, false)
    }
    return PhotoRenderDecision(targetStage, true)
}

internal fun resolveJpegTiledBindDecision(
    currentPath: String?,
    currentStage: PhotoRenderStage,
    currentSourceMode: JpegTiledSourceMode?,
    nextPath: String,
    nextStage: PhotoRenderStage,
    nextSourceMode: JpegTiledSourceMode,
    isViewerReady: Boolean
): JpegTiledBindDecision {
    if (currentPath != nextPath) {
        return JpegTiledBindDecision(nextStage, true)
    }
    val resolvedStage = when {
        currentStage == PhotoRenderStage.FULL && nextStage != PhotoRenderStage.FULL -> {
            PhotoRenderStage.FULL
        }
        nextStage.ordinal > currentStage.ordinal -> nextStage
        else -> currentStage
    }
    if (currentSourceMode != nextSourceMode) {
        return JpegTiledBindDecision(resolvedStage, true)
    }
    if (resolvedStage == currentStage) {
        return JpegTiledBindDecision(currentStage, false)
    }
    if (!isViewerReady) {
        return JpegTiledBindDecision(resolvedStage, true)
    }
    return JpegTiledBindDecision(resolvedStage, false)
}

internal fun resolveJpegTiledSourceMode(
    isCurrentPage: Boolean,
    hasPreviewBitmap: Boolean
): JpegTiledSourceMode {
    return when {
        isCurrentPage && hasPreviewBitmap -> JpegTiledSourceMode.TILED_WITH_PREVIEW
        isCurrentPage -> JpegTiledSourceMode.TILED_ORIGINAL_ONLY
        else -> JpegTiledSourceMode.BITMAP_ONLY
    }
}

internal fun resolvePhotoViewerMode(
    isJpeg: Boolean,
    isPreviewUnavailable: Boolean = false
): PhotoViewerMode {
    return if (isJpeg && !isPreviewUnavailable) {
        PhotoViewerMode.JPEG_TILED
    } else {
        PhotoViewerMode.PHOTO_VIEW
    }
}

internal fun resolveLoadingOverlayVisible(
    stage: PhotoRenderStage,
    isJpeg: Boolean,
    isTiledViewerReady: Boolean = false
): Boolean {
    if (stage == PhotoRenderStage.UNAVAILABLE) return false
    return if (isJpeg) {
        stage == PhotoRenderStage.EMPTY && !isTiledViewerReady
    } else {
        stage == PhotoRenderStage.EMPTY
    }
}
