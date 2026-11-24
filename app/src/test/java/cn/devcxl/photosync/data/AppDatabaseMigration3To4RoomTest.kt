package cn.devcxl.photosync.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase

import androidx.room.Room

import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Seam under test: [AppDatabase.MIGRATION_3_4] opened through Room.
 *
 * The instrumented `AppDatabaseMigration3To4Test` only proves the SQL runs; this test also makes Room
 * validate the migrated tables against the current entities. A hand-written `DEFAULT` that the
 * entities do not declare (or a missing foreign key / index) fails here instead of on a user's
 * device after the upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppDatabaseMigration3To4RoomTest {

    @Test
    fun shouldOpenWithRoomSchemaValidation_whenMigratingFromV3() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration_3_4_room_test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()

        // Pre-create the exact v3 schema (sync_device + photos) and stamp user_version = 3.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_device` (
                    `device_uuid` TEXT NOT NULL,
                    `device_name` TEXT,
                    `product_name` TEXT,
                    `manufacturer_name` TEXT,
                    `vendor_id` INTEGER,
                    `device_id` INTEGER,
                    `product_id` INTEGER,
                    `serial_number` TEXT,
                    `version` TEXT,
                    `sync_idlist_json` TEXT,
                    `is_syncing` INTEGER NOT NULL,
                    `sync_at` INTEGER,
                    `updated_at` INTEGER,
                    `created_at` INTEGER,
                    PRIMARY KEY(`device_uuid`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `photos` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `path` TEXT NOT NULL,
                    `name` TEXT,
                    `uri_string` TEXT,
                    `object_handle` INTEGER,
                    `created_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_path` ON `photos` (`path`)")
            db.version = 3
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                // Touching the schema makes Room run the migration and validate every table,
                // foreign key and index against the current entities.
                database.openHelper.writableDatabase
                assertNotNull(database.userPresetDao().getAll())
                assertNull(database.photoEditDao().getByPhotoId(1L))
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
