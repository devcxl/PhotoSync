package cn.devcxl.photosync.editor.contract

import kotlinx.coroutines.flow.Flow

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.vision.OrtSessionManager

/**
 * Self-contained arguments passed when launching the editor activity so `:editor` never needs
 * to query `:app`'s database directly for photo metadata.
 */
data class EditorLaunchArgs(
    val photoId: Long,
    val sourcePath: String,
    val displayName: String?,
    val isRaw: Boolean
)

/**
 * Persisted non-destructive edit record associated with a single photo.
 */
data class PhotoEditRecord(
    val photoId: Long,
    val schemaVersion: Int,
    val graphJson: String,
    val previewCachePath: String?,
    val updatedAt: Long
)

/**
 * A reusable parameter preset (`Preset` in the domain language).
 *
 * @property id Stable primary key. Built-in presets use deterministic string ids such as
 *   `builtin_clean_portrait` so seeding them on every app start is idempotent.
 * @property name Display name shown on the preset chips.
 * @property graphSubsetJson The preset's parameters as an `EditGraph` JSON document. Omitted axes
 *   decode back to their neutral defaults, so a preset never carries a crop or a warp.
 * @property lutFilePath Optional imported `.cube` LUT the preset references, for cleanup bookkeeping.
 * @property isBuiltin True for the curated library shipped with the app; built-ins can be applied but
 *   never renamed or deleted.
 * @property createdAt Creation timestamp, fixed for built-ins.
 */
data class UserPresetRecord(
    val id: String,
    val name: String,
    val graphSubsetJson: String,
    val lutFilePath: String? = null,
    val isBuiltin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Host storage contract implemented by `:app` and consumed by `:editor`.
 */
interface EditStore {
    suspend fun getEditForPhoto(photoId: Long): PhotoEditRecord?

    /**
     * Persists [graph] for [photoId]. If [graph.isIdentity] is true, deletes any existing
     * record and cleans up its preview cache directory instead of storing an identity row.
     */
    suspend fun saveEditForPhoto(
        photoId: Long,
        graph: EditGraph,
        previewCachePath: String?
    )

    suspend fun deleteEditForPhoto(photoId: Long)

    fun observeEditedPhotoMap(): Flow<Map<Long, PhotoEditRecord>>

    /** Emits the whole preset library, built-ins first, whenever a preset is saved or removed. */
    fun observePresets(): Flow<List<UserPresetRecord>>

    /** One-shot read of the preset library, for callers that are not rendering a chip row. */
    suspend fun listPresets(): List<UserPresetRecord>

    /** Returns the preset with [id], or `null` when it does not exist. */
    suspend fun getPreset(id: String): UserPresetRecord?

    /** Inserts or replaces [preset]. Never mutates a built-in row into a user row or vice versa. */
    suspend fun savePreset(preset: UserPresetRecord)

    /** Renames a user preset. Built-in presets are protected by the [EditStore] implementation. */
    suspend fun renamePreset(id: String, newName: String)

    /** Deletes a user preset. Built-in presets are protected by the [EditStore] implementation. */
    suspend fun deletePreset(id: String)

    /** The active `LiveTetherPreset` id, or `null` when tethered auto-apply is off. */
    fun getLiveTetherPresetId(): String?

    /** Enables tethered auto-apply for [presetId], or disables it when `null`. */
    fun setLiveTetherPresetId(presetId: String?)

    /** Emits the active `LiveTetherPreset` id whenever the user toggles tethered auto-apply. */
    fun observeLiveTetherPresetId(): Flow<String?>
}

/**
 * Shared relative directory convention under `cacheDir` for rendered editor previews.
 */
object EditorCachePaths {
    fun photoEditDirName(photoId: Long): String = "edits/$photoId"
}

/**
 * Process-wide registry used by `:app` to wire its [EditStore] and [GallerySink] hosts into
 * `:editor` without introducing a circular module dependency.
 */
object EditorHostRegistry {
    @Volatile
    var editStore: EditStore? = null

    @Volatile
    var gallerySink: GallerySink? = null

    /**
     * Photo ids that finished syncing from the camera while the editor was in the foreground.
     *
     * `:app` owns the flow; `:editor` only observes it so a background import never steals focus from
     * an active slider or liquify gesture. `null` means the host publishes nothing.
     */
    @Volatile
    var tetheredPhotoEvents: Flow<Long>? = null

    /**
     * Process-wide ORT session owner, injected by `:app` during `Application.onCreate`.
     *
     * Every AI tool must share this one manager, otherwise the "at most one native session" contract
     * of [OrtSessionManager] can be bypassed by holding two tool-owned instances at the same time.
     * Constructing the manager loads no model and starts no thread; the first `withSession` call is
     * still the only thing that touches ONNX Runtime.
     *
     * `null` is only expected in JVM tests or in a host that has not wired the registry yet; a tool
     * then falls back to a private manager that it owns and closes itself.
     */
    @Volatile
    var ortSessionManager: OrtSessionManager? = null
}
