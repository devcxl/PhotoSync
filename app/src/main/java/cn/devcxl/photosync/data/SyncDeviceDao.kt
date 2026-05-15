package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.devcxl.photosync.data.entity.SyncDevice

@Dao
interface SyncDeviceDao {
    @Query("SELECT * FROM sync_device WHERE device_uuid = :uuid LIMIT 1")
    fun getByUuid(uuid: String): SyncDevice?

    @Query("SELECT * FROM sync_device")
    fun getAll(): List<SyncDevice>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(device: SyncDevice): Long

    @Update
    fun update(device: SyncDevice): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(devices: List<SyncDevice>): LongArray

    @Query("DELETE FROM sync_device WHERE device_uuid = :uuid")
    fun deleteByUuid(uuid: String): Int

    @Delete
    fun delete(device: SyncDevice): Int
}
