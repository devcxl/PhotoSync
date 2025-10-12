package cn.devcxl.photosync.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import cn.devcxl.photosync.data.entity.PhotoEntity;
import cn.devcxl.photosync.data.entity.SyncDevice;

/**
 * @author devcxl
 */
@Database(entities = {SyncDevice.class, PhotoEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract SyncDeviceDao syncDeviceDao();
    public abstract PhotoDao photoDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "photosync.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

