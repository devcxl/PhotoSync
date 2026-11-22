package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

import cn.devcxl.photosync.data.entity.PhotoEntity

/**
 * DAO for photo entities.
 */
@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY created_at ASC")
    fun getAllFlow(): Flow<List<PhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id IN (:ids) ORDER BY created_at ASC")
    suspend fun getByIds(ids: List<Long>): List<PhotoEntity>

    @Query("UPDATE photos SET uri_string = :uriString WHERE id = :id")
    suspend fun updateUriString(id: Long, uriString: String)

    /**
     * Deletes a photo entity from database.
     *
     * @param entity The photo entity to delete.
     */
    @Delete
    suspend fun delete(entity: PhotoEntity)
}
