package cn.devcxl.photosync.data

import java.io.File
import java.io.IOException

import androidx.room.withTransaction
import timber.log.Timber

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Room-backed [TetheredPhotoCommitter]: one transaction inserts the photo, publishes the staged
 * `LiveTetherPreset` preview under `cacheDir/edits/<photoId>/` and inserts the edit row.
 *
 * The staging directory lives under [cacheDir] on the same filesystem, so `renameTo` is an O(1)
 * rename; if the Room transaction rolls back after the rename, the moved preview file is deleted in
 * the catch block so no orphaned preview remains.
 */
class RoomTetheredPhotoCommitter(
    private val database: AppDatabase,
    private val cacheDir: File
) : TetheredPhotoCommitter {

    override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long {
        var publishedFile: File? = null
        return try {
            database.withTransaction {
                val photoId = database.photoDao().insert(photo)
                if (photoId <= 0L) {
                    photoId
                } else {
                    if (graphJson != null && stagedPreview != null && stagedPreview.isFile) {
                        publishedFile = publishPreview(photoId, stagedPreview)
                        val target = publishedFile
                        if (target != null) {
                            insertEditRow(photoId, target.absolutePath, graphJson)
                        }
                    }
                    photoId
                }
            }
        } catch (error: RuntimeException) {
            publishedFile?.delete()
            throw error
        }
    }

    private suspend fun insertEditRow(photoId: Long, previewPath: String, graphJson: String) {
        database.photoEditDao().upsertByPhotoId(
            PhotoEditEntity(
                id = 0L,
                photoId = photoId,
                schemaVersion = SCHEMA_VERSION,
                graphJson = graphJson,
                previewCachePath = previewPath,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun publishPreview(photoId: Long, stagedPreview: File): File? {
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) {
            Timber.e("LiveTetherPreset preview directory could not be created")
            stagedPreview.delete()
            return null
        }
        val target = File(editDir, stagedPreview.name)
        if (stagedPreview.renameTo(target)) return target
        return try {
            stagedPreview.copyTo(target, overwrite = true)
            stagedPreview.delete()
            target
        } catch (io: IOException) {
            Timber.e(io, "LiveTetherPreset preview could not be published")
            stagedPreview.delete()
            null
        }
    }

    private companion object {
        /** Rows written here carry the same graph schema the editor persists. */
        const val SCHEMA_VERSION = EditGraph.CURRENT_SCHEMA_VERSION
    }
}
