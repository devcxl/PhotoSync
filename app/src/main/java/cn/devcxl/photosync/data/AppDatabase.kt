package cn.devcxl.photosync.data

import android.content.Context

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.data.entity.SyncDevice

/**
 * @author devcxl
 */
@Database(entities = [SyncDevice::class, PhotoEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDeviceDao(): SyncDeviceDao

    abstract fun photoDao(): PhotoDao


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Adds the PTP object handle recorded with each imported photo.
         *
         * Rows imported before this column existed keep a null handle, which simply means
         * they cannot be deleted on the camera again — no library is thrown away for it.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN object_handle INTEGER")
            }
        }

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "photosync.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
