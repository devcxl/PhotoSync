package cn.devcxl.photosync.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [AppDatabase.MIGRATION_3_4] against a real SQLite database on device/emulator,
 * including `ON DELETE CASCADE` cleanup of `photo_edits` when a parent `photos` row is removed.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration3To4Test {

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DB_NAME)
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
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
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB_NAME)
            .callback(callback)
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @After
    fun tearDown() {
        helper.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DB_NAME)
    }

    @Test
    fun shouldMigrateV3ToV4AndCascadeDeletePhotoEdits_whenParentPhotoIsDeleted() {
        val db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO photos (id, path, name, created_at) VALUES (42, '/cache/a.arw', 'a.arw', 1000)"
        )

        AppDatabase.MIGRATION_3_4.migrate(db)

        db.execSQL(
            "INSERT INTO photo_edits (photo_id, schema_version, graph_json, preview_cache_path, updated_at) " +
                "VALUES (42, 1, '{\"schema\":1}', '/cache/edits/42/p.jpg', 2000)"
        )
        db.query("SELECT COUNT(*) FROM photo_edits WHERE photo_id = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        db.execSQL("DELETE FROM photos WHERE id = 42")

        db.query("SELECT COUNT(*) FROM photo_edits WHERE photo_id = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='user_presets'").use { c ->
            assertTrue(c.moveToFirst())
            assertFalse(c.getString(0).isNullOrBlank())
        }
    }

    companion object {
        private const val TEST_DB_NAME = "migration_3_4_test.db"
    }
}
