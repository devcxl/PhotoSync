package cn.devcxl.photosync.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ToneOps

class RoomEditStoreTest {

    private fun store(
        editDao: FakePhotoEditDao = FakePhotoEditDao(),
        cleaned: MutableList<Long> = mutableListOf()
    ): RoomEditStore = RoomEditStore(
        photoEditDao = editDao,
        userPresetDao = FakeUserPresetDao(),
        liveTetherPrefs = RecordingPrefs(),
        onClearPhotoEditCache = { id -> cleaned += id }
    )

    @Test
    fun shouldPersistEditRecord_whenGraphIsNonIdentity() = runBlocking {
        val cleanedPhotoIds = mutableListOf<Long>()
        val store = store(cleaned = cleanedPhotoIds)

        val graph = EditGraph(tone = ToneOps(exposure = 1.25f))
        store.saveEditForPhoto(
            photoId = 42L,
            graph = graph,
            previewCachePath = "/cache/edits/42/preview.jpg"
        )

        val record = store.getEditForPhoto(42L)
            ?: throw AssertionError("Expected persisted PhotoEditRecord for photoId=42")
        assertEquals(42L, record.photoId)
        assertEquals("/cache/edits/42/preview.jpg", record.previewCachePath)
        assertTrue(record.graphJson.contains("\"exposure\":1.25"))
        assertTrue(cleanedPhotoIds.isEmpty())
    }

    @Test
    fun shouldDeleteRecordAndClearCache_whenGraphIsIdentity() = runBlocking {
        val cleanedPhotoIds = mutableListOf<Long>()
        val store = store(cleaned = cleanedPhotoIds)

        store.saveEditForPhoto(
            photoId = 42L,
            graph = EditGraph(tone = ToneOps(exposure = 0.75f)),
            previewCachePath = "/cache/edits/42/preview.jpg"
        )
        assertNotNull(store.getEditForPhoto(42L))

        store.saveEditForPhoto(
            photoId = 42L,
            graph = EditGraph(),
            previewCachePath = null
        )

        assertNull(store.getEditForPhoto(42L))
        assertEquals(listOf(42L), cleanedPhotoIds)
    }

    @Test
    fun shouldDefineForeignKeyCascadeAndUniqueIndex_whenInspectingMigration3To4Sql() {
        val statements = AppDatabase.MIGRATION_3_4_STATEMENTS
        assertEquals(3, statements.size)
        assertTrue(
            statements[0].contains(
                "FOREIGN KEY(`photo_id`) REFERENCES `photos`(`id`) ON DELETE CASCADE"
            )
        )
        assertTrue(
            statements[1].contains(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_photo_edits_photo_id` ON `photo_edits` (`photo_id`)"
            )
        )
        assertTrue(statements[2].contains("CREATE TABLE IF NOT EXISTS `user_presets`"))
    }
}
