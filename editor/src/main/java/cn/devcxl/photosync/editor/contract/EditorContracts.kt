package cn.devcxl.photosync.editor.contract

import kotlinx.coroutines.flow.Flow

import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Self-contained arguments passed when launching the editor activity so `:editor` never needs
 * to query `:app`'s database directly for photo metadata.
 */
data class EditorLaunchArgs(
    val photoId: Long,
    val sourcePath: String,
    val displayName: String?,
    val isRaw: Boolean
)

/**
 * Persisted non-destructive edit record associated with a single photo.
 */
data class PhotoEditRecord(
    val photoId: Long,
    val schemaVersion: Int,
    val graphJson: String,
    val previewCachePath: String?,
    val updatedAt: Long
)

/**
 * Reusable user-defined or built-in parameter preset record.
 */
data class UserPresetRecord(
    val id: String,
    val name: String,
    val graphSubsetJson: String,
    val lutFilePath: String?,
    val isBuiltin: Boolean,
    val createdAt: Long
)

/**
 * Host storage contract implemented by `:app` and consumed by `:editor`.
 */
interface EditStore {
    suspend fun getEditForPhoto(photoId: Long): PhotoEditRecord?

    /**
     * Persists [graph] for [photoId]. If [graph.isIdentity] is true, deletes any existing
     * record and cleans up its preview cache directory instead of storing an identity row.
     */
    suspend fun saveEditForPhoto(
        photoId: Long,
        graph: EditGraph,
        previewCachePath: String?
    )

    suspend fun deleteEditForPhoto(photoId: Long)

    fun observeEditedPhotoMap(): Flow<Map<Long, PhotoEditRecord>>
}

/**
 * Process-wide registry used by `:app` to wire its [EditStore] implementation into `:editor`
 * without introducing a circular module dependency.
 */
object EditorHostRegistry {
    @Volatile
    var editStore: EditStore? = null
}
