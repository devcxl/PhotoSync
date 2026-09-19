package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.devcxl.photosync.data.entity.SyncDevice

@Dao
interface SyncDeviceDao {
    @Query("SELECT * FROM sync_device WHERE device_uuid = :uuid LIMIT 1")
    fun getByUuid(uuid: String): SyncDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(device: SyncDevice): Long

    @Update
    fun update(device: SyncDevice): Int
}
