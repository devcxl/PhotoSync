package cn.devcxl.photosync.data

import java.io.File
import java.io.IOException

import androidx.room.withTransaction
import timber.log.Timber

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditorCachePaths

/**
 * Room-backed [TetheredPhotoCommitter]: one transaction inserts the photo, publishes the staged
 * `LiveTetherPreset` preview under `cacheDir/edits/<photoId>/` and inserts the edit row.
 *
 * The preview move happens *inside* the transaction because the destination directory is named after
 * the auto-generated photo id, which only exists once the insert returned. Doing it afterwards would
 * leave a window where `MainActivity` observes the photo without the preview.
 */
class RoomTetheredPhotoCommitter(
    private val database: AppDatabase,
    private val cacheDir: File
) : TetheredPhotoCommitter {

    override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long =
        database.withTransaction {
            val photoId = database.photoDao().insert(photo)
            if (photoId <= 0L) {
                photoId
            } else {
                if (graphJson != null) {
                    publishEdit(photoId, stagedPreview, graphJson)
                }
                photoId
            }
        }

    private suspend fun publishEdit(photoId: Long, stagedPreview: File?, graphJson: String) {
        val previewPath = publishPreview(photoId, stagedPreview)
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

    /**
     * Moves the staged JPEG into the edit cache directory of [photoId].
     *
     * @return The final absolute path, or `null` when no staged file exists or the move failed.
     */
    private fun publishPreview(photoId: Long, stagedPreview: File?): String? {
        if (stagedPreview == null || !stagedPreview.isFile) return null
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) {
            Timber.e("LiveTetherPreset preview directory could not be created")
            stagedPreview.delete()
            return null
        }
        val target = File(editDir, stagedPreview.name)
        if (stagedPreview.renameTo(target)) return target.absolutePath
        // A rename can fail across mount points; fall back to a copy so the preview is not lost.
        return try {
            stagedPreview.copyTo(target, overwrite = true)
            stagedPreview.delete()
            target.absolutePath
        } catch (io: IOException) {
            Timber.e(io, "LiveTetherPreset preview could not be published")
            stagedPreview.delete()
            null
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
