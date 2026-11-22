package cn.devcxl.photosync.data

import android.content.SharedPreferences

import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber

import cn.devcxl.photosync.data.entity.PhotoEditEntity
import cn.devcxl.photosync.data.entity.UserPresetEntity
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.domain.BuiltinPresets
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.EditValidator

/**
 * Room-backed implementation of [EditStore].
 *
 * When a saved [EditGraph] is identity (all sliders at neutral defaults), any existing row
 * for that photo is deleted and [onClearPhotoEditCache] is invoked so no identity rows or
 * stale preview files remain.
 *
 * The active `LiveTetherPreset` lives in [liveTetherPrefs] rather than a table: it is a single
 * process-wide choice, and reading it must be synchronous because the USB download callback decides
 * whether to render a preview before it commits a row.
 */
class RoomEditStore(
    private val photoEditDao: PhotoEditDao,
    private val userPresetDao: UserPresetDao,
    private val liveTetherPrefs: SharedPreferences,
    private val onClearPhotoEditCache: (Long) -> Unit,
    private val builtinNameResolver: (String) -> String = { it }
) : EditStore {

    private val liveTetherPresetId = MutableStateFlow(readLiveTetherPresetId())

    override suspend fun getEditForPhoto(photoId: Long): PhotoEditRecord? {
        return photoEditDao.getByPhotoId(photoId)?.toRecord()
    }

    override suspend fun saveEditForPhoto(
        photoId: Long,
        graph: EditGraph,
        previewCachePath: String?
    ) {
        val sanitized = EditValidator.sanitize(graph)
        if (sanitized.isIdentity()) {
            deleteEditForPhoto(photoId)
            return
        }
        val entity = PhotoEditEntity(
            id = 0L,
            photoId = photoId,
            schemaVersion = sanitized.schema,
            graphJson = EditGraphJsonCodec.encode(sanitized),
            previewCachePath = previewCachePath,
            updatedAt = System.currentTimeMillis()
        )
        photoEditDao.upsertByPhotoId(entity)
    }

    override suspend fun deleteEditForPhoto(photoId: Long) {
        photoEditDao.deleteByPhotoId(photoId)
        onClearPhotoEditCache(photoId)
    }

    override fun observeEditedPhotoMap(): Flow<Map<Long, PhotoEditRecord>> {
        return photoEditDao.observeAllEditsFlow().map { list ->
            list.associate { entity -> entity.photoId to entity.toRecord() }
        }
    }

    override fun observePresets(): Flow<List<UserPresetRecord>> =
        userPresetDao.observeAllFlow().map { list -> list.map { it.toRecord() } }

    override suspend fun listPresets(): List<UserPresetRecord> =
        userPresetDao.getAll().map { it.toRecord() }

    override suspend fun getPreset(id: String): UserPresetRecord? =
        userPresetDao.getById(id)?.toRecord()

    override suspend fun savePreset(preset: UserPresetRecord) {
        val name = preset.name.trim()
        if (preset.id.isBlank() || name.isEmpty()) {
            Timber.w("Refusing to save a preset without an id or a name")
            return
        }
        if (preset.isBuiltin || userPresetDao.getById(preset.id)?.isBuiltin == true) {
            Timber.w("Refusing to overwrite a built-in preset")
            return
        }
        userPresetDao.upsertUserPreset(preset.copy(name = name).toEntity())
    }

    override suspend fun renamePreset(id: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) {
            Timber.w("Refusing to rename a preset to a blank name")
            return
        }
        val existing = userPresetDao.getById(id)
        if (existing == null) {
            Timber.w("Preset rename skipped: unknown preset")
            return
        }
        if (existing.isBuiltin) {
            Timber.w("Preset rename skipped: built-in presets are read-only")
            return
        }
        userPresetDao.rename(id, name)
    }

    override suspend fun deletePreset(id: String) {
        val existing = userPresetDao.getById(id)
        if (existing == null) {
            Timber.w("Preset delete skipped: unknown preset")
            return
        }
        if (existing.isBuiltin) {
            Timber.w("Preset delete skipped: built-in presets are read-only")
            return
        }
        userPresetDao.deleteById(id)
        if (liveTetherPresetId.value == id) {
            setLiveTetherPresetId(null)
        }
    }

    override fun getLiveTetherPresetId(): String? = liveTetherPresetId.value

    override fun setLiveTetherPresetId(presetId: String?) {
        val sanitized = presetId?.takeIf { it.isNotBlank() }
        liveTetherPrefs.edit { putString(PREF_LIVE_TETHER_PRESET_ID, sanitized) }
        liveTetherPresetId.value = sanitized
    }

    override fun observeLiveTetherPresetId(): Flow<String?> = liveTetherPresetId.asStateFlow()

    /**
     * Seeds the curated [BuiltinPresets] library and refreshes localized names on existing rows.
     */
    suspend fun seedBuiltinPresets() {
        val entities = BuiltinPresets.specs().map { spec ->
            UserPresetEntity(
                id = spec.id,
                name = builtinNameResolver(spec.id),
                graphSubsetJson = spec.graphSubsetJson,
                lutFilePath = null,
                isBuiltin = true,
                createdAt = spec.createdAt
            )
        }
        userPresetDao.insertAllIgnore(entities)
        entities.forEach { userPresetDao.updateBuiltinName(it.id, it.name) }
    }

    private fun readLiveTetherPresetId(): String? =
        liveTetherPrefs.getString(PREF_LIVE_TETHER_PRESET_ID, null)?.takeIf { it.isNotBlank() }

    private fun PhotoEditEntity.toRecord(): PhotoEditRecord = PhotoEditRecord(
        photoId = photoId,
        schemaVersion = schemaVersion,
        graphJson = graphJson,
        previewCachePath = previewCachePath,
        updatedAt = updatedAt
    )

    private fun UserPresetEntity.toRecord(): UserPresetRecord = UserPresetRecord(
        id = id,
        name = name,
        graphSubsetJson = graphSubsetJson,
        lutFilePath = lutFilePath,
        isBuiltin = isBuiltin,
        createdAt = createdAt
    )

    private fun UserPresetRecord.toEntity(): UserPresetEntity = UserPresetEntity(
        id = id,
        name = name,
        graphSubsetJson = graphSubsetJson,
        lutFilePath = lutFilePath,
        isBuiltin = isBuiltin,
        createdAt = createdAt
    )

    companion object {
        /** `SharedPreferences` key for the active `LiveTetherPreset` id (`String?`). */
        const val PREF_LIVE_TETHER_PRESET_ID = "pref_live_tether_preset_id"
    }
}
