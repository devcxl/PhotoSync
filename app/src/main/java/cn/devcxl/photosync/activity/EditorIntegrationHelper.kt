package cn.devcxl.photosync.activity

import java.io.File
import java.io.IOException

import cn.devcxl.photosync.adapter.PhotoContentVersion
import cn.devcxl.photosync.adapter.PhotoViewerMode
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.contract.PhotoEditRecord

/**
 * Pure decision helpers bridging non-destructive [PhotoEditRecord] state into the main
 * gallery viewer and filmstrip.
 */

/** What the main gallery must show for a photo, derived from its edit record only. */
internal enum class PreviewDisplaySource {
    /** No edit record: the original file is the intended content. */
    ORIGINAL,

    /** An edit record exists and points at a readable regular preview file. */
    EDITED,

    /**
     * An edit record exists, but its preview cannot represent the edit: the path is missing,
     * blank, points at the original file (including a symlink or lexical alias), is not a readable
     * regular file, or no longer exists. The page must not fall back to the original pixels.
     */
    UNAVAILABLE
}

/**
 * Resolved content for one photo.
 *
 * [previewPath] is non-null exactly when [source] is [PreviewDisplaySource.EDITED]. Callers must
 * treat [PreviewDisplaySource.UNAVAILABLE] as "show the recovery message", never as "read
 * [PhotoEntity.path]".
 */
internal data class PreviewDisplayDecision(
    val source: PreviewDisplaySource,
    val previewPath: String?
)

/**
 * Decides which content the gallery may show for [entity].
 *
 * The record's presence is what makes the photo edited. A preview is usable only when
 * `previewCachePath` is non-blank, identifies a readable regular file, and resolves to a different
 * file than the original. The last check rejects symlinks and lexical aliases (`./`, `..`,
 * relative paths) that point back at the original.
 *
 * @param isReadableFile probes "readable regular file". The default uses [File.isFile] and
 *   [File.canRead]; an [IOException] or [SecurityException] from the probe is treated as unreadable.
 * @param resolvesToSameFile compares two paths after canonicalization. `true` and `null` both mean
 *   the preview cannot be trusted; only `false` permits [PreviewDisplaySource.EDITED].
 */
internal fun resolvePreviewDisplayDecision(
    entity: PhotoEntity,
    editedMap: Map<Long, PhotoEditRecord>,
    isReadableFile: (String) -> Boolean = { path -> isReadableRegularFile(path) },
    resolvesToSameFile: (String, String) -> Boolean? = { first, second ->
        resolveSameCanonicalFile(first, second)
    }
): PreviewDisplayDecision {
    val record = editedMap[entity.id]
        ?: return PreviewDisplayDecision(PreviewDisplaySource.ORIGINAL, previewPath = null)
    val previewPath = record.previewCachePath?.takeUnless { it.isBlank() }
        ?: return PreviewDisplayDecision(PreviewDisplaySource.UNAVAILABLE, previewPath = null)
    if (previewPath == entity.path) {
        return PreviewDisplayDecision(PreviewDisplaySource.UNAVAILABLE, previewPath = null)
    }
    val readable = try {
        isReadableFile(previewPath)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
    if (!readable) {
        return PreviewDisplayDecision(PreviewDisplaySource.UNAVAILABLE, previewPath = null)
    }
    val sameFile = try {
        resolvesToSameFile(previewPath, entity.path)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
    if (sameFile != false) {
        return PreviewDisplayDecision(PreviewDisplaySource.UNAVAILABLE, previewPath = null)
    }
    return PreviewDisplayDecision(PreviewDisplaySource.EDITED, previewPath)
}

/**
 * The content version the pager must key a page's PhotoView state on.
 *
 * Derived from the same decision the decoders use, so the version changes exactly when the intended
 * content changes: a newly rendered edit preview path, an edit reset back to the original, or a
 * preview that can no longer represent the edit (missing, blank, unreadable, or aliasing the
 * original). It never guesses from write timestamps and keeps no per-path registry, so the set of
 * possible versions stays bounded by the edit records that exist.
 */
internal fun resolvePhotoContentVersion(decision: PreviewDisplayDecision): PhotoContentVersion {
    return when (decision.source) {
        PreviewDisplaySource.ORIGINAL -> PhotoContentVersion.Original
        PreviewDisplaySource.UNAVAILABLE -> PhotoContentVersion.PreviewUnavailable
        PreviewDisplaySource.EDITED -> PhotoContentVersion.Edited(
            previewPath = requireNotNull(decision.previewPath) {
                "an EDITED decision must carry its preview path"
            }
        )
    }
}

/**
 * The path a preview decode may read: the rendered preview for an edited photo, otherwise the
 * original. Preserved for the compare controller, which only asks whether an edit preview exists.
 */
internal fun resolveEffectivePreviewPath(
    entity: PhotoEntity,
    editedMap: Map<Long, PhotoEditRecord>,
    isReadableFile: (String) -> Boolean = { path -> isReadableRegularFile(path) },
    resolvesToSameFile: (String, String) -> Boolean? = { first, second ->
        resolveSameCanonicalFile(first, second)
    }
): String {
    val decision = resolvePreviewDisplayDecision(
        entity = entity,
        editedMap = editedMap,
        isReadableFile = isReadableFile,
        resolvesToSameFile = resolvesToSameFile
    )
    return decision.previewPath ?: entity.path
}

/** True only for a regular file the process is allowed to read. */
private fun isReadableRegularFile(path: String): Boolean {
    return try {
        val file = File(path)
        file.isFile && file.canRead()
    } catch (_: SecurityException) {
        false
    }
}

/**
 * Whether both paths identify the same file, or `null` when canonicalization failed.
 *
 * `null` is deliberately distinct from `false`: callers must treat an unverifiable preview as
 * unusable rather than risk showing the original.
 */
private fun resolveSameCanonicalFile(firstPath: String, secondPath: String): Boolean? {
    return try {
        File(firstPath).canonicalFile == File(secondPath).canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/**
 * Viewer mode for the main pager.
 *
 * The edit record's presence decides this, not whether its preview file happens to exist: a JPEG
 * with a recorded edit must use the bitmap surface, where an unavailable preview can show the
 * recovery message, instead of the URI-backed tiled viewer that would reveal the original.
 */
internal fun resolveGalleryViewerMode(
    entity: PhotoEntity,
    editedMap: Map<Long, PhotoEditRecord>,
    isJpeg: Boolean
): PhotoViewerMode {
    return if (isJpeg && !editedMap.containsKey(entity.id)) {
        PhotoViewerMode.JPEG_TILED
    } else {
        PhotoViewerMode.PHOTO_VIEW
    }
}

internal fun isPhotoMarkedEdited(
    entity: PhotoEntity?,
    editedMap: Map<Long, PhotoEditRecord>
): Boolean {
    return entity != null && editedMap.containsKey(entity.id)
}

internal fun clearPhotoEditCacheDir(cacheDir: File, photoId: Long) {
    if (photoId <= 0L) return
    val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
    if (editDir.exists()) {
        editDir.deleteRecursively()
    }
}
