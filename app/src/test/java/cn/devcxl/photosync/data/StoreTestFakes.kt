package cn.devcxl.photosync.data

import android.content.SharedPreferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.UserPresetEntity

/**
 * In-memory test doubles shared by [RoomEditStoreTest] and [RoomPresetStoreTest].
 */
internal class FakePhotoEditDao : PhotoEditDao {
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

internal class FakeUserPresetDao : UserPresetDao {
    val presets = mutableMapOf<String, UserPresetEntity>()
    private val flow = MutableStateFlow<List<UserPresetEntity>>(emptyList())

    override fun observeAllFlow(): Flow<List<UserPresetEntity>> = flow

    override suspend fun getAll(): List<UserPresetEntity> = presets.values.toList()

    override suspend fun getById(id: String): UserPresetEntity? = presets[id]

    override suspend fun insertIgnore(entity: UserPresetEntity): Long {
        if (presets.containsKey(entity.id)) return -1L
        presets[entity.id] = entity
        publish()
        return 1L
    }

    override suspend fun insertAllIgnore(entities: List<UserPresetEntity>) {
        entities.forEach { presets.putIfAbsent(it.id, it) }
        publish()
    }

    override suspend fun updateUserRow(id: String, name: String, graphJson: String, lutPath: String?) {
        val existing = presets[id] ?: return
        if (existing.isBuiltin) return
        presets[id] = existing.copy(name = name, graphSubsetJson = graphJson, lutFilePath = lutPath)
        publish()
    }

    override suspend fun updateBuiltinName(id: String, name: String) {
        val existing = presets[id] ?: return
        if (!existing.isBuiltin) return
        presets[id] = existing.copy(name = name)
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

    private fun publish() {
        flow.value = presets.values.toList()
    }
}

internal class RecordingPrefs : SharedPreferences {
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

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
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
