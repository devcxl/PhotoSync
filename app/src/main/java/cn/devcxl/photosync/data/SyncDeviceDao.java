package cn.devcxl.photosync.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import cn.devcxl.photosync.data.entity.SyncDevice;

import java.util.List;

@Dao
public interface SyncDeviceDao {
    @Query("SELECT * FROM sync_device WHERE device_uuid = :uuid LIMIT 1")
    SyncDevice getByUuid(String uuid);

    @Query("SELECT * FROM sync_device")
    List<SyncDevice> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(SyncDevice device);

    @Update
    int update(SyncDevice device);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long[] upsertAll(List<SyncDevice> devices);

    @Query("DELETE FROM sync_device WHERE device_uuid = :uuid")
    int deleteByUuid(String uuid);

    @Delete
    int delete(SyncDevice device);
}

