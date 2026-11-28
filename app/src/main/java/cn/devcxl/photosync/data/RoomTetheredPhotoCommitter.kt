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
 * Signals that a staged `SubjectMask` payload could not be persisted.
 *
 * Thrown inside the commit transaction so the photo row rolls back and [TetheredPhotoIngestor] can
 * re-stage the traditional-grade preview before retrying the commit.
 */
internal class SubjectMaskUnavailableException :
    Exception("The subject mask asset could not be stored")

/** Signals that a staged `SkyMask` payload could not be persisted; the transaction rolls back. */
internal class SkyMaskUnavailableException :
    Exception("The sky mask asset could not be stored")

/**
 * Room-backed [TetheredPhotoCommitter]: one transaction inserts the photo, publishes the staged
 * `LiveTetherPreset` preview under `cacheDir/edits/<photoId>/`, moves the staged subject mask into
 * `filesDir/edits/<photoId>/` and inserts the edit row whose `maskRef` points at it.
 *
 * The staging directory lives under [cacheDir] on the same filesystem, so `renameTo` is an O(1)
 * rename; if the Room transaction rolls back after the rename, the moved preview file is deleted in
 * the catch block so no orphaned preview remains. A mask that cannot be written aborts the
 * transaction with [SubjectMaskUnavailableException], so the caller can fall back to the traditional
 * grade instead of publishing a row whose pixels it cannot reproduce.
 */
class RoomTetheredPhotoCommitter(
    private val database: AppDatabase,
    private val cacheDir: File,
    private val filesDir: File
) : TetheredPhotoCommitter {

    override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long =
        commitWithAi(photo, stagedPreview, graphJson, subjectMaskPayload = null, skyMaskPayload = null)

    override suspend fun commitWithSubject(
        photo: PhotoEntity,
        stagedPreview: File?,
        graphJson: String?,
        subjectMaskPayload: ByteArray?
    ): Long = commitWithAi(photo, stagedPreview, graphJson, subjectMaskPayload, skyMaskPayload = null)

    override suspend fun commitWithAi(
        photo: PhotoEntity,
        stagedPreview: File?,
        graphJson: String?,
        subjectMaskPayload: ByteArray?,
        skyMaskPayload: ByteArray?
    ): Long {
        var publishedFile: File? = null
        return try {
            database.withTransaction {
                val photoId = database.photoDao().insert(photo)
                if (photoId <= 0L) {
                    photoId
                } else {
                    val boundGraphJson = if (stagedPreview != null && stagedPreview.isFile) {
                        bindAiMasks(photoId, graphJson, subjectMaskPayload, skyMaskPayload)
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
     * Persists every staged mask under the new photo id and rewrites the matching `maskRef`s.
     *
     * @return The final graph JSON, or `null` when [graphJson] is absent or undecodable.
     * @throws SubjectMaskUnavailableException or [SkyMaskUnavailableException] when a payload exists
     *   but its asset write failed; the transaction then rolls back and the ingest degrades.
     */
    private fun bindAiMasks(
        photoId: Long,
        graphJson: String?,
        subjectMaskPayload: ByteArray?,
        skyMaskPayload: ByteArray?
    ): String? {
        if (graphJson == null) return null
        var graph = EditGraphJsonCodec.decode(graphJson) ?: return graphJson
        val store = AiAssetStore(filesDir)
        if (subjectMaskPayload != null) {
            val reference = store.write(photoId, AiAssetKind.SUBJECT_MASK, subjectMaskPayload)
                ?: throw SubjectMaskUnavailableException()
            graph = graph.copy(subject = graph.subject.copy(maskRef = reference.relativePath))
        }
        if (skyMaskPayload != null) {
            val reference = store.write(photoId, AiAssetKind.SKY_MASK, skyMaskPayload)
                ?: throw SkyMaskUnavailableException()
            graph = graph.copy(sky = graph.sky.copy(maskRef = reference.relativePath))
        }
        return EditGraphJsonCodec.encode(graph)
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
