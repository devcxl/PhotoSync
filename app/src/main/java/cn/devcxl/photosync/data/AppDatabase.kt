package cn.devcxl.photosync.data

import android.content.Context

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.data.entity.SyncDevice
import cn.devcxl.photosync.data.entity.UserPresetEntity

/**
 * @author devcxl
 */
@Database(
    entities = [
        SyncDevice::class,
        PhotoEntity::class,
        PhotoEditEntity::class,
        UserPresetEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDeviceDao(): SyncDeviceDao

    abstract fun photoDao(): PhotoDao

    abstract fun photoEditDao(): PhotoEditDao

    abstract fun userPresetDao(): UserPresetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Adds the PTP object handle recorded with each imported photo.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN object_handle INTEGER")
            }
        }

        internal val MIGRATION_3_4_STATEMENTS = listOf(
            """
            CREATE TABLE IF NOT EXISTS `photo_edits` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `photo_id` INTEGER NOT NULL,
                `schema_version` INTEGER NOT NULL,
                `graph_json` TEXT NOT NULL,
                `preview_cache_path` TEXT,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`photo_id`) REFERENCES `photos`(`id`) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_photo_edits_photo_id` ON `photo_edits` (`photo_id`)",
            """
            CREATE TABLE IF NOT EXISTS `user_presets` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `name` TEXT NOT NULL,
                `graph_subset_json` TEXT NOT NULL,
                `lut_file_path` TEXT,
                `is_builtin` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        /**
         * Adds non-destructive `photo_edits` (with cascade delete on `photos.id`) and
         * `user_presets` tables.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_STATEMENTS.forEach { sql -> db.execSQL(sql) }
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
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
