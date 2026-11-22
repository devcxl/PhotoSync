package cn.devcxl.photosync.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.EditValidator

/**
 * Room-backed implementation of [EditStore].
 *
 * When a saved [EditGraph] is identity (all sliders at neutral defaults), any existing row
 * for that photo is deleted and [onClearPhotoEditCache] is invoked so no identity rows or
 * stale preview files remain.
 */
class RoomEditStore(
    private val photoEditDao: PhotoEditDao,
    private val onClearPhotoEditCache: (Long) -> Unit
) : EditStore {

    override suspend fun getEditForPhoto(photoId: Long): PhotoEditRecord? {
        return photoEditDao.getByPhotoId(photoId)?.toRecord()
    }

    override suspend fun saveEditForPhoto(
        photoId: Long,
        graph: EditGraph,
        previewCachePath: String?
    ) {
        val sanitized = EditValidator.sanitize(graph)
        if (sanitized.isIdentity()) {
            deleteEditForPhoto(photoId)
            return
        }
        val existingId = photoEditDao.getByPhotoId(photoId)?.id ?: 0L
        val entity = PhotoEditEntity(
            id = existingId,
            photoId = photoId,
            schemaVersion = sanitized.schema,
            graphJson = EditGraphJsonCodec.encode(sanitized),
            previewCachePath = previewCachePath,
            updatedAt = System.currentTimeMillis()
        )
        photoEditDao.upsert(entity)
    }

    override suspend fun deleteEditForPhoto(photoId: Long) {
        photoEditDao.deleteByPhotoId(photoId)
        onClearPhotoEditCache(photoId)
    }

    override fun observeEditedPhotoMap(): Flow<Map<Long, PhotoEditRecord>> {
        return photoEditDao.observeAllEditsFlow().map { list ->
            list.associate { entity -> entity.photoId to entity.toRecord() }
        }
    }

    private fun PhotoEditEntity.toRecord(): PhotoEditRecord {
        return PhotoEditRecord(
            photoId = photoId,
            schemaVersion = schemaVersion,
            graphJson = graphJson,
            previewCachePath = previewCachePath,
            updatedAt = updatedAt
        )
    }
}
