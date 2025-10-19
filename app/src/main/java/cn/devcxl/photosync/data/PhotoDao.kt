package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.devcxl.photosync.data.entity.PhotoEntity
import kotlinx.coroutines.flow.Flow

import java.util.List;

/**
 * DAO for photo entities.
 */
@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<PhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PhotoEntity): Long

    @Query("DELETE FROM photos")
    suspend fun clear()
}