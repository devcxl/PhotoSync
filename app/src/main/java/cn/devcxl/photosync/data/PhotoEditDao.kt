package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.UserPresetEntity

/**
 * DAO for non-destructive photo edit records and user presets.
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

    @Query("SELECT * FROM user_presets ORDER BY created_at ASC")
    suspend fun getAllPresets(): List<UserPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: UserPresetEntity)

    @Query("DELETE FROM user_presets WHERE id = :id")
    suspend fun deletePresetById(id: String)
}
