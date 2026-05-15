package cn.devcxl.photosync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.data.entity.SyncDevice

/**
 * @author devcxl
 */
@Database(entities = [SyncDevice::class, PhotoEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDeviceDao(): SyncDeviceDao

    abstract fun photoDao(): PhotoDao


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "photosync.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
