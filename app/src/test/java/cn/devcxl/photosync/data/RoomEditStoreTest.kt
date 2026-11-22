package cn.devcxl.photosync.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ToneOps

class RoomEditStoreTest {

    private class FakePhotoEditDao : PhotoEditDao {
        val editsByPhotoId = mutableMapOf<Long, PhotoEditEntity>()
        private val flow = MutableStateFlow<List<PhotoEditEntity>>(emptyList())

        override suspend fun getByPhotoId(photoId: Long): PhotoEditEntity? = editsByPhotoId[photoId]

        override fun observeAllEditsFlow(): Flow<List<PhotoEditEntity>> = flow

        override suspend fun upsert(entity: PhotoEditEntity): Long {
            val existing = editsByPhotoId[entity.photoId]
            val id = existing?.id?.takeIf { it != 0L } ?: (editsByPhotoId.size + 1L)
            val saved = entity.copy(id = id)
            editsByPhotoId[entity.photoId] = saved
            flow.value = editsByPhotoId.values.toList()
            return id
        }

        override suspend fun deleteByPhotoId(photoId: Long) {
            editsByPhotoId.remove(photoId)
            flow.value = editsByPhotoId.values.toList()
        }
    }

    @Test
    fun shouldPersistEditRecord_whenGraphIsNonIdentity() = runBlocking {
        val dao = FakePhotoEditDao()
        val cleanedPhotoIds = mutableListOf<Long>()
        val store = RoomEditStore(
            photoEditDao = dao,
            onClearPhotoEditCache = { id -> cleanedPhotoIds += id }
        )

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
        val dao = FakePhotoEditDao()
        val cleanedPhotoIds = mutableListOf<Long>()
        val store = RoomEditStore(
            photoEditDao = dao,
            onClearPhotoEditCache = { id -> cleanedPhotoIds += id }
        )

        store.saveEditForPhoto(
            photoId = 42L,
            graph = EditGraph(tone = ToneOps(exposure = 0.75f)),
            previewCachePath = "/cache/edits/42/preview.jpg"
        )
        assertNotNull(store.getEditForPhoto(42L))

        // Resetting back to all-zero identity must delete the row and wipe cache
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
        assertTrue(
            statements[2].contains(
                "CREATE TABLE IF NOT EXISTS `user_presets`"
            )
        )
    }
}
