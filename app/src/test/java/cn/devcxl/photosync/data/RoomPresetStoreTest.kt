package cn.devcxl.photosync.data

import android.content.SharedPreferences

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.domain.BuiltinPresets

/**
 * Seam under test: [RoomEditStore] preset CRUD, built-in read-only guards and `LiveTetherPreset`
 * preference persistence.
 */
class RoomPresetStoreTest {

    private fun store(
        presetDao: FakeUserPresetDao = FakeUserPresetDao(),
        prefs: SharedPreferences = RecordingPrefs()
    ): RoomEditStore = RoomEditStore(
        photoEditDao = FakePhotoEditDao(),
        userPresetDao = presetDao,
        liveTetherPrefs = prefs,
        onClearPhotoEditCache = {},
        builtinNameResolver = { id -> "name_$id" }
    )

    @Test
    fun shouldSeedFiveBuiltinsOnce_whenSeedingRepeatedly() = runBlocking {
        val store = store()

        store.seedBuiltinPresets()
        store.seedBuiltinPresets()

        val builtins = store.listPresets().filter { it.isBuiltin }
        assertEquals(BuiltinPresets.specs().size, builtins.size)
        assertEquals(5, builtins.size)
        assertTrue(builtins.all { it.graphSubsetJson.contains("\"schema\":1") })
    }

    @Test
    fun shouldPersistUserPreset_whenSavingIt() = runBlocking {
        val store = store()
        store.seedBuiltinPresets()

        store.savePreset(
            UserPresetRecord(
                id = "user_1",
                name = " 我的胶片 ",
                graphSubsetJson = "{\"schema\":1}",
                lutFilePath = null,
                isBuiltin = false
            )
        )

        val fetched = store.getPreset("user_1")
        assertNotNull(fetched)
        assertEquals("我的胶片", fetched?.name)
        assertFalse(fetched?.isBuiltin ?: true)
        assertEquals(6, store.listPresets().size)
    }

    @Test
    fun shouldRefuseOverwritingBuiltin_whenSavingWithBuiltinId() = runBlocking {
        val store = store()
        store.seedBuiltinPresets()

        store.savePreset(
            UserPresetRecord(
                id = BuiltinPresets.CLEAN_PORTRAIT_ID,
                name = "Hijacked",
                graphSubsetJson = "{\"schema\":1}",
                isBuiltin = false
            )
        )

        val preset = store.getPreset(BuiltinPresets.CLEAN_PORTRAIT_ID)
        assertTrue(preset?.isBuiltin == true)
        assertEquals("name_${BuiltinPresets.CLEAN_PORTRAIT_ID}", preset?.name)
    }

    @Test
    fun shouldRenameUserPresetButNotBuiltinPreset_whenCallingRename() = runBlocking {
        val store = store()
        store.seedBuiltinPresets()
        store.savePreset(
            UserPresetRecord(id = "user_1", name = "旧名字", graphSubsetJson = "{\"schema\":1}")
        )

        store.renamePreset("user_1", "新名字")
        store.renamePreset(BuiltinPresets.CLEAN_PORTRAIT_ID, "偷改内置")

        assertEquals("新名字", store.getPreset("user_1")?.name)
        assertEquals(
            "name_${BuiltinPresets.CLEAN_PORTRAIT_ID}",
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
        val store = store()
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
}
