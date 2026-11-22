package cn.devcxl.photosync.data

import android.content.SharedPreferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.UserPresetEntity
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.domain.BuiltinPresets
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

    /**
     * In-memory preset table that mirrors the real SQL guards: `rename` and `deleteById` carry the
     * `is_builtin = 0` predicate, so a built-in row can never be rewritten.
     */
    private class FakeUserPresetDao : UserPresetDao {
        val presets = mutableMapOf<String, UserPresetEntity>()
        private val flow = MutableStateFlow<List<UserPresetEntity>>(emptyList())

        override fun observeAllFlow(): Flow<List<UserPresetEntity>> = flow

        override suspend fun getAll(): List<UserPresetEntity> = presets.values.toList()

        override suspend fun getById(id: String): UserPresetEntity? = presets[id]

        override suspend fun upsert(entity: UserPresetEntity) {
            presets[entity.id] = entity
            publish()
        }

        override suspend fun insertAllIgnore(entities: List<UserPresetEntity>) {
            entities.forEach { presets.putIfAbsent(it.id, it) }
            publish()
        }

        override suspend fun rename(id: String, newName: String) {
            val existing = presets[id] ?: return
            if (existing.isBuiltin) return
            presets[id] = existing.copy(name = newName)
            publish()
        }

        override suspend fun deleteById(id: String) {
            val existing = presets[id] ?: return
            if (existing.isBuiltin) return
            presets.remove(id)
            publish()
        }

        override suspend fun deleteAll() {
            presets.clear()
            publish()
        }

        private fun publish() {
            flow.value = presets.values.toList()
        }
    }

    /**
     * Records writes and reads through [values] so persistence behaviour can be asserted without an
     * Android runtime.
     */
    private class RecordingPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private class Editor(private val prefs: RecordingPrefs) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = this

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this

            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = true

            override fun apply() {
                pending.forEach { (key, value) -> prefs.values[key] = value }
            }
        }
    }

    private fun store(
        editDao: FakePhotoEditDao = FakePhotoEditDao(),
        presetDao: FakeUserPresetDao = FakeUserPresetDao(),
        prefs: SharedPreferences = RecordingPrefs(),
        cleaned: MutableList<Long> = mutableListOf()
    ): RoomEditStore = RoomEditStore(
        photoEditDao = editDao,
        userPresetDao = presetDao,
        liveTetherPrefs = prefs,
        onClearPhotoEditCache = { id -> cleaned += id }
    )

    @Test
    fun shouldPersistEditRecord_whenGraphIsNonIdentity() = runBlocking {
        val dao = FakePhotoEditDao()
        val cleanedPhotoIds = mutableListOf<Long>()
        val store = store(editDao = dao, cleaned = cleanedPhotoIds)

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
        val store = store(editDao = dao, cleaned = cleanedPhotoIds)

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

    @Test
    fun shouldSeedFiveBuiltinsOnce_whenSeedingRepeatedly() = runBlocking {
        val presetDao = FakeUserPresetDao()
        val store = store(presetDao = presetDao)

        store.seedBuiltinPresets()
        store.seedBuiltinPresets()

        val builtins = store.listPresets().filter { it.isBuiltin }
        assertEquals(BuiltinPresets.all().size, builtins.size)
        assertEquals(5, builtins.size)
        assertTrue(builtins.all { it.graphSubsetJson.contains("\"schema\":1") })
    }

    @Test
    fun shouldPersistUserPreset_whenSavingIt() = runBlocking {
        val presetDao = FakeUserPresetDao()
        val store = store(presetDao = presetDao)
        store.seedBuiltinPresets()

        val saved = UserPresetRecord(
            id = "user_1",
            name = " 我的胶片 ",
            graphSubsetJson = "{\"schema\":1}",
            lutFilePath = null,
            isBuiltin = false
        )
        store.savePreset(saved)

        val fetched = store.getPreset("user_1")
        assertNotNull(fetched)
        assertEquals("我的胶片", fetched?.name)
        assertFalse(fetched?.isBuiltin ?: true)
        assertEquals(6, store.listPresets().size)
    }

    @Test
    fun shouldRenameUserPresetButNotBuiltinPreset_whenCallingRename() = runBlocking {
        val presetDao = FakeUserPresetDao()
        val store = store(presetDao = presetDao)
        store.seedBuiltinPresets()
        store.savePreset(
            UserPresetRecord(id = "user_1", name = "旧名字", graphSubsetJson = "{\"schema\":1}")
        )

        store.renamePreset("user_1", "新名字")
        store.renamePreset(BuiltinPresets.CLEAN_PORTRAIT_ID, "偷改内置")

        assertEquals("新名字", store.getPreset("user_1")?.name)
        assertEquals(
            "干净人像",
            store.getPreset(BuiltinPresets.CLEAN_PORTRAIT_ID)?.name
        )
    }

    @Test
    fun shouldRejectBlankRename_whenNameIsOnlyWhitespace() = runBlocking {
        val store = store()
        store.savePreset(
            UserPresetRecord(id = "user_1", name = "保留", graphSubsetJson = "{\"schema\":1}")
        )

        store.renamePreset("user_1", "   ")

        assertEquals("保留", store.getPreset("user_1")?.name)
    }

    @Test
    fun shouldDeleteUserPresetButNotBuiltinPreset_whenCallingDelete() = runBlocking {
        val presetDao = FakeUserPresetDao()
        val store = store(presetDao = presetDao)
        store.seedBuiltinPresets()
        store.savePreset(
            UserPresetRecord(id = "user_1", name = "待删", graphSubsetJson = "{\"schema\":1}")
        )

        store.deletePreset("user_1")
        store.deletePreset(BuiltinPresets.MONO_SILVER_ID)

        assertNull(store.getPreset("user_1"))
        assertNotNull(store.getPreset(BuiltinPresets.MONO_SILVER_ID))
        assertEquals(5, store.listPresets().size)
    }

    @Test
    fun shouldClearLiveTetherPreset_whenItsPresetIsDeleted() = runBlocking {
        val store = store()
        store.savePreset(
            UserPresetRecord(id = "user_1", name = "联机", graphSubsetJson = "{\"schema\":1}")
        )
        store.setLiveTetherPresetId("user_1")

        store.deletePreset("user_1")

        assertNull(store.getLiveTetherPresetId())
    }

    @Test
    fun shouldPersistLiveTetherPresetId_whenToggled() {
        val prefs = RecordingPrefs()
        val store = store(prefs = prefs)

        store.setLiveTetherPresetId("builtin_film_chrome")

        assertEquals("builtin_film_chrome", store.getLiveTetherPresetId())
        assertEquals(
            "builtin_film_chrome",
            prefs.values[RoomEditStore.PREF_LIVE_TETHER_PRESET_ID]
        )
    }

    @Test
    fun shouldRestoreLiveTetherPresetIdFromPrefs_whenStoreIsRecreated() {
        val prefs = RecordingPrefs()
        prefs.values[RoomEditStore.PREF_LIVE_TETHER_PRESET_ID] = "builtin_warm_sunset"

        val store = store(prefs = prefs)

        assertEquals("builtin_warm_sunset", store.getLiveTetherPresetId())
    }

    @Test
    fun shouldStoreNullWhenTetheringIsTurnedOff_whenCallingSetWithNull() {
        val prefs = RecordingPrefs()
        val store = store(prefs = prefs)
        store.setLiveTetherPresetId("builtin_cool_nordic")

        store.setLiveTetherPresetId(null)

        assertNull(store.getLiveTetherPresetId())
        assertNull(prefs.values[RoomEditStore.PREF_LIVE_TETHER_PRESET_ID])
    }

    @Test
    fun shouldProtectBuiltinsInSql_whenInspectingThePresetDaoGuards() {
        // The literal the DAO's rename/delete statements append; keep them in sync.
        assertEquals("AND is_builtin = 0", UserPresetDao.BUILTIN_GUARD)
    }
}
