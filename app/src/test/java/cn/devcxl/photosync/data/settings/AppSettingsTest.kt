package cn.devcxl.photosync.data.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.settings.SettingsStorage

/**
 * Seam under test: [AppSettings] — defaults, the idempotent `delete_on_camera` migration with its
 * dual write, and the strict key-set disjointness with [EditorSettings].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppSettingsTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var legacyPreferences: SharedPreferences
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = SettingsStorage.open(context)
        preferences.edit().clear().commit()
        legacyPreferences = context.getSharedPreferences(
            AppSettings.LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        legacyPreferences.edit().clear().commit()
        settings = AppSettings(preferences, legacyPreferences)
    }

    @Test
    fun shouldUseDocumentedDefaults_whenNothingPersisted() {
        assertTrue(settings.autoFollowEnabled)
        assertFalse(settings.deleteOnCamera)
        assertTrue(settings.keepScreenOn)
        assertTrue(settings.livePresetEnabled)
        assertTrue(settings.livePresetApplyAi)
        assertFalse(preferences.contains(AppSettings.KEY_DELETE_ON_CAMERA))
    }

    @Test
    fun shouldPersistEveryValue_whenWritten() {
        settings.autoFollowEnabled = false
        settings.deleteOnCamera = true
        settings.keepScreenOn = false
        settings.livePresetEnabled = false
        settings.livePresetApplyAi = false

        val reopened = AppSettings(SettingsStorage.open(context), legacyPreferences)
        assertFalse(reopened.autoFollowEnabled)
        assertTrue(reopened.deleteOnCamera)
        assertFalse(reopened.keepScreenOn)
        assertFalse(reopened.livePresetEnabled)
        assertFalse(reopened.livePresetApplyAi)
    }

    @Test
    fun shouldCopyLegacyValueOnce_whenNewKeyIsAbsent() {
        legacyPreferences.edit()
            .putBoolean(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA, true)
            .commit()

        assertTrue(settings.deleteOnCamera)
        assertTrue(preferences.getBoolean(AppSettings.KEY_DELETE_ON_CAMERA, false))

        legacyPreferences.edit()
            .putBoolean(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA, false)
            .commit()
        assertTrue(
            "a second read must not overwrite the already migrated value",
            settings.deleteOnCamera
        )
    }

    @Test
    fun shouldNotMigrate_whenNewKeyAlreadyExists() {
        preferences.edit().putBoolean(AppSettings.KEY_DELETE_ON_CAMERA, false).commit()
        legacyPreferences.edit()
            .putBoolean(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA, true)
            .commit()

        assertFalse(settings.deleteOnCamera)
        assertFalse(preferences.getBoolean(AppSettings.KEY_DELETE_ON_CAMERA, true))
    }

    @Test
    fun shouldRemainIdempotent_whenMigrationRunsRepeatedly() {
        legacyPreferences.edit()
            .putBoolean(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA, true)
            .commit()

        settings.migrateLegacyDeleteOnCameraIfNeeded()
        settings.migrateLegacyDeleteOnCameraIfNeeded()
        settings.migrateLegacyDeleteOnCameraIfNeeded()

        assertTrue(preferences.getBoolean(AppSettings.KEY_DELETE_ON_CAMERA, false))
        assertTrue(legacyPreferences.getBoolean(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA, false))
    }

    @Test
    fun shouldMirrorToLegacyKey_whenDeleteOnCameraIsWritten() {
        settings.deleteOnCamera = true
        assertTrue(legacyPreferences.getBoolean(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA, false))

        settings.deleteOnCamera = false
        assertFalse(legacyPreferences.getBoolean(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA, true))
    }

    @Test
    fun shouldLeaveOtherLegacyKeysAlone_whenMigrating() {
        legacyPreferences.edit()
            .putString("pref_live_tether_preset_id", "preset-7")
            .commit()

        settings.deleteOnCamera = false

        assertEquals(
            "preset-7",
            legacyPreferences.getString("pref_live_tether_preset_id", null)
        )
    }

    @Test
    fun shouldRestoreDefaults_whenResetAll() {
        settings.autoFollowEnabled = false
        settings.deleteOnCamera = true
        settings.keepScreenOn = false
        settings.livePresetEnabled = false
        settings.livePresetApplyAi = false

        settings.resetAll()

        assertTrue(settings.autoFollowEnabled)
        assertFalse(
            "reset must not re-migrate the legacy value it just cleared",
            settings.deleteOnCamera
        )
        assertTrue(settings.keepScreenOn)
        assertTrue(settings.livePresetEnabled)
        assertTrue(settings.livePresetApplyAi)
        assertFalse(legacyPreferences.contains(AppSettings.LEGACY_KEY_DELETE_ON_CAMERA))
        assertTrue(preferences.all.keys.none { it in AppSettings.ALL_KEYS })
    }

    @Test
    fun shouldReportEffectiveValues_whenSnapshotting() {
        settings.autoFollowEnabled = false
        settings.deleteOnCamera = true
        settings.keepScreenOn = false
        settings.livePresetEnabled = false
        settings.livePresetApplyAi = false

        val snapshot = settings.snapshot()
        assertFalse(snapshot.autoFollowEnabled)
        assertTrue(snapshot.deleteOnCamera)
        assertFalse(snapshot.keepScreenOn)
        assertFalse(snapshot.livePresetEnabled)
        assertFalse(snapshot.livePresetApplyAi)
    }

    @Test
    fun shouldNeverOverlapEditorKeys_whenBothFacadesShareTheFile() {
        val overlap = EditorSettings.ALL_KEYS intersect AppSettings.ALL_KEYS
        assertTrue("facades must not share a key: $overlap", overlap.isEmpty())
        assertTrue(
            "measured tile keys must stay inside the editor namespace",
            AppSettings.ALL_KEYS.none { it.startsWith(EditorSettings.MEASURED_TILE_MS_KEY_PREFIX) }
        )
        assertTrue(
            AppSettings.ALL_KEYS.none { EditorSettings.isMeasuredTileMsKey(it) }
        )
    }

    @Test
    fun shouldShareTheScreenOnKeyName_whenTheEditorReadsItDirectly() {
        assertEquals(
            AppSettings.KEY_KEEP_SCREEN_ON,
            cn.devcxl.photosync.editor.settings.DisplaySettings.KEY_KEEP_SCREEN_ON
        )
        assertEquals(
            AppSettings.DEFAULT_KEEP_SCREEN_ON,
            cn.devcxl.photosync.editor.settings.DisplaySettings.DEFAULT_KEEP_SCREEN_ON
        )
    }
}
