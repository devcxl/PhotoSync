package cn.devcxl.photosync.data

import java.io.File
import java.io.IOException

import androidx.room.withTransaction
import timber.log.Timber

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec

/**
 * Room-backed [TetheredPhotoCommitter]: one transaction inserts the photo, publishes the staged
 * `LiveTetherPreset` preview under `cacheDir/edits/<photoId>/`, moves the staged subject mask into
 * `filesDir/edits/<photoId>/` and inserts the edit row whose `maskRef` points at it.
 *
 * The staging directory lives under [cacheDir] on the same filesystem, so `renameTo` is an O(1)
 * rename; if the Room transaction rolls back after the rename, the moved preview file is deleted in
 * the catch block so no orphaned preview remains. A mask that cannot be written is not fatal: the
 * row degrades to the traditional grade (the `subject` group is cleared) instead of losing the photo.
 */
class RoomTetheredPhotoCommitter(
    private val database: AppDatabase,
    private val cacheDir: File,
    private val filesDir: File
) : TetheredPhotoCommitter {

    override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long =
        commitWithSubject(photo, stagedPreview, graphJson, subjectMaskPayload = null)

    override suspend fun commitWithSubject(
        photo: PhotoEntity,
        stagedPreview: File?,
        graphJson: String?,
        subjectMaskPayload: ByteArray?
    ): Long {
        var publishedFile: File? = null
        return try {
            database.withTransaction {
                val photoId = database.photoDao().insert(photo)
                if (photoId <= 0L) {
                    photoId
                } else {
                    val boundGraphJson = if (stagedPreview != null && stagedPreview.isFile) {
                        bindSubjectMask(photoId, graphJson, subjectMaskPayload)
                    } else {
                        null
                    }
                    if (boundGraphJson != null && stagedPreview != null && stagedPreview.isFile) {
                        publishedFile = publishPreview(photoId, stagedPreview)
                        val target = publishedFile
                        if (target != null) {
                            insertEditRow(photoId, target.absolutePath, boundGraphJson)
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

    /**
     * Persists [subjectMaskPayload] under the new photo id and rewrites `subject.maskRef`.
     *
     * @return The final graph JSON, or `null` when [graphJson] is absent, undecodable, or the
     *   staged mask could not be persisted (the caller then omits the edit row entirely).
     */
    private fun bindSubjectMask(
        photoId: Long,
        graphJson: String?,
        subjectMaskPayload: ByteArray?
    ): String? {
        if (graphJson == null) return null
        val graph = EditGraphJsonCodec.decode(graphJson) ?: return graphJson
        if (subjectMaskPayload == null) return graphJson
        val reference = AiAssetStore(filesDir).write(photoId, AiAssetKind.SUBJECT_MASK, subjectMaskPayload)
        if (reference == null) {
            // The staged preview already shows the subject effect; storing a graph that cannot
            // reproduce it would publish a wrong row. Skip the edit and let the ingest report an
            // ungraded import instead.
            Timber.w("LiveTetherPreset subject mask could not be stored; skipping the preset edit")
            return null
        }
        return EditGraphJsonCodec.encode(
            graph.copy(subject = graph.subject.copy(maskRef = reference.relativePath))
        )
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
