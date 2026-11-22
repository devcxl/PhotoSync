package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM photo_edits WHERE photo_id = :photoId")
    suspend fun deleteByPhotoId(photoId: Long)
}
