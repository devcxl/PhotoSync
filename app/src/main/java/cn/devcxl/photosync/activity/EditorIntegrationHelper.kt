package cn.devcxl.photosync.activity

import java.io.File

import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.contract.PhotoEditRecord

/**
 * Pure decision helpers bridging non-destructive [PhotoEditRecord] state into the main
 * gallery viewer and filmstrip.
 */
internal fun resolveEffectivePreviewPath(
    entity: PhotoEntity,
    editedMap: Map<Long, PhotoEditRecord>,
    fileExists: (String) -> Boolean = { path -> File(path).exists() }
): String {
    val previewPath = editedMap[entity.id]?.previewCachePath
    return if (!previewPath.isNullOrBlank() && fileExists(previewPath)) {
        previewPath
    } else {
        entity.path
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
