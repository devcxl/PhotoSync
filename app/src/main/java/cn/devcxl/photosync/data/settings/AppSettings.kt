package cn.devcxl.photosync.data.settings

import android.content.Context
import android.content.SharedPreferences

import cn.devcxl.photosync.editor.settings.SettingsStorage

/** Effective tether/gallery settings, read from [AppSettings] for diagnostics and display. */
data class AppSettingsSnapshot(
    val autoFollowEnabled: Boolean,
    val deleteOnCamera: Boolean,
    val keepScreenOn: Boolean,
    val livePresetEnabled: Boolean,
    val livePresetApplyAi: Boolean
)

/**
 * Typed facade over the tether and gallery half of [SettingsStorage.FILE_NAME].
 *
 * The `:editor` side [cn.devcxl.photosync.editor.settings.EditorSettings] owns a disjoint key set in
 * the same file. Every key declares its type and default here; [ALL_KEYS] is exported so a test can
 * prove the two facades never collide.
 *
 * The legacy `photosync_prefs.delete_on_camera` key is migrated lazily and idempotently on the first
 * read: when the new key is absent and the legacy one exists, its value is copied once. Every write
 * then mirrors back to the legacy key so an older build can still be rolled back without losing the
 * user's choice. No other legacy key is migrated.
 */
class AppSettings(
    private val preferences: SharedPreferences,
    private val legacyPreferences: SharedPreferences
) {

    /** Opens the shared settings file plus the legacy per-install preferences. */
    constructor(context: Context) : this(
        SettingsStorage.open(context),
        context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    /**
     * Whether a newly ingested photo auto-advances the viewer.
     *
     * Key [KEY_AUTO_FOLLOW]. Boolean; default [DEFAULT_AUTO_FOLLOW]. Wired by the gallery.
     */
    var autoFollowEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_FOLLOW, DEFAULT_AUTO_FOLLOW)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTO_FOLLOW, value).apply()
        }

    /**
     * Whether deleting a local photo also deletes the camera original.
     *
     * Key [KEY_DELETE_ON_CAMERA]. Boolean; default [DEFAULT_DELETE_ON_CAMERA]. Reading it triggers the
     * one-time legacy migration; writing it dual-writes `photosync_prefs.delete_on_camera` for
     * rollback support.
     */
    var deleteOnCamera: Boolean
        get() {
            migrateLegacyDeleteOnCameraIfNeeded()
            return preferences.getBoolean(KEY_DELETE_ON_CAMERA, DEFAULT_DELETE_ON_CAMERA)
        }
        set(value) {
            preferences.edit().putBoolean(KEY_DELETE_ON_CAMERA, value).apply()
            legacyPreferences.edit().putBoolean(LEGACY_KEY_DELETE_ON_CAMERA, value).apply()
        }

    /**
     * Whether the gallery and editor keep the screen awake while they are visible.
     *
     * Key [KEY_KEEP_SCREEN_ON]. Boolean; default [DEFAULT_KEEP_SCREEN_ON].
     */
    var keepScreenOn: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)
        set(value) {
            preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
        }

    /**
     * Master switch for automatic live-tether preset application.
     *
     * Key [KEY_LIVE_PRESET_ENABLED]. Boolean; default [DEFAULT_LIVE_PRESET_ENABLED]. Disabling it
     * keeps the remembered preset id for later re-enabling.
     */
    var livePresetEnabled: Boolean
        get() = preferences.getBoolean(KEY_LIVE_PRESET_ENABLED, DEFAULT_LIVE_PRESET_ENABLED)
        set(value) {
            preferences.edit().putBoolean(KEY_LIVE_PRESET_ENABLED, value).apply()
        }

    /**
     * Whether live-tether ingest hands subject/sky ops to the background preparer.
     *
     * Key [KEY_LIVE_PRESET_APPLY_AI]. Boolean; default [DEFAULT_LIVE_PRESET_APPLY_AI].
     */
    var livePresetApplyAi: Boolean
        get() = preferences.getBoolean(KEY_LIVE_PRESET_APPLY_AI, DEFAULT_LIVE_PRESET_APPLY_AI)
        set(value) {
            preferences.edit().putBoolean(KEY_LIVE_PRESET_APPLY_AI, value).apply()
        }

    /**
     * Copies the legacy delete-on-camera flag once, when the new key does not exist yet.
     *
     * Idempotent: after the first copy (or after any write through [deleteOnCamera]) the new key
     * exists, so later calls do nothing even if the legacy value changed meanwhile. This keeps a
     * rollback install from silently overwriting a newer choice.
     */
    fun migrateLegacyDeleteOnCameraIfNeeded() {
        if (preferences.contains(KEY_DELETE_ON_CAMERA)) return
        if (!legacyPreferences.contains(LEGACY_KEY_DELETE_ON_CAMERA)) return
        val legacyValue = legacyPreferences.getBoolean(
            LEGACY_KEY_DELETE_ON_CAMERA,
            DEFAULT_DELETE_ON_CAMERA
        )
        preferences.edit().putBoolean(KEY_DELETE_ON_CAMERA, legacyValue).apply()
    }

    /** Current effective values, for the read-only diagnostics summary. */
    fun snapshot(): AppSettingsSnapshot = AppSettingsSnapshot(
        autoFollowEnabled = autoFollowEnabled,
        deleteOnCamera = deleteOnCamera,
        keepScreenOn = keepScreenOn,
        livePresetEnabled = livePresetEnabled,
        livePresetApplyAi = livePresetApplyAi
    )

    /**
     * Restores factory defaults.
     *
     * The legacy delete-on-camera key is removed as well, so the next read cannot re-migrate the
     * value this reset just cleared. An older build then falls back to its own `false` default.
     */
    fun resetAll() {
        val editor = preferences.edit()
        ALL_KEYS.forEach { editor.remove(it) }
        editor.apply()
        legacyPreferences.edit().remove(LEGACY_KEY_DELETE_ON_CAMERA).apply()
    }

    companion object {

        /** Legacy preferences file owned by [cn.devcxl.photosync.activity.MainActivity]. */
        const val LEGACY_PREFERENCES_NAME = "photosync_prefs"

        /** Legacy delete-on-camera key, kept in sync for rollback. */
        const val LEGACY_KEY_DELETE_ON_CAMERA = "delete_on_camera"

        const val KEY_AUTO_FOLLOW = "tether_auto_follow"
        const val KEY_DELETE_ON_CAMERA = "tether_delete_on_camera"
        const val KEY_KEEP_SCREEN_ON = "display_keep_screen_on"
        const val KEY_LIVE_PRESET_ENABLED = "tether_live_preset_enabled"
        const val KEY_LIVE_PRESET_APPLY_AI = "tether_live_preset_apply_ai"

        /** Every key owned by this facade; disjoint from the `:editor` facade. */
        val ALL_KEYS: Set<String> = setOf(
            KEY_AUTO_FOLLOW,
            KEY_DELETE_ON_CAMERA,
            KEY_KEEP_SCREEN_ON,
            KEY_LIVE_PRESET_ENABLED,
            KEY_LIVE_PRESET_APPLY_AI
        )

        const val DEFAULT_AUTO_FOLLOW = true
        const val DEFAULT_DELETE_ON_CAMERA = false
        const val DEFAULT_KEEP_SCREEN_ON = true
        const val DEFAULT_LIVE_PRESET_ENABLED = true
        const val DEFAULT_LIVE_PRESET_APPLY_AI = true
    }
}
