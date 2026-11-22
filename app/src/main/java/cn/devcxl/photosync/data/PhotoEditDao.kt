package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

import cn.devcxl.photosync.data.entity.PhotoEditEntity

/**
 * DAO for non-destructive photo edit records.
 */
@Dao
interface PhotoEditDao {

    @Query("SELECT * FROM photo_edits WHERE photo_id = :photoId LIMIT 1")
    suspend fun getByPhotoId(photoId: Long): PhotoEditEntity?

    @Query("SELECT * FROM photo_edits")
    fun observeAllEditsFlow(): Flow<List<PhotoEditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PhotoEditEntity): Long

    /**
     * Atomically updates or inserts the [PhotoEditEntity] for `entity.photoId` inside a
     * single Room transaction so concurrent saves never race on the unique `photo_id` index.
     */
    @Transaction
    suspend fun upsertByPhotoId(entity: PhotoEditEntity): Long {
        val existingId = getByPhotoId(entity.photoId)?.id ?: 0L
        return upsert(entity.copy(id = existingId))
    }

    @Query("DELETE FROM photo_edits WHERE photo_id = :photoId")
    suspend fun deleteByPhotoId(photoId: Long)
}
