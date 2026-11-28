package cn.devcxl.photosync.editor.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.vision.OrtSessionRuntimeConfig

/**
 * Seam under test: [EditorRenderSettings] — the one coherent snapshot every editor session builds
 * from [EditorSettings], plus the read-only [DisplaySettings] bridge for the app-owned screen-on key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorRenderSettingsTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var settings: EditorSettings

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = SettingsStorage.open(context)
        preferences.edit().clear().commit()
        settings = EditorSettings(preferences)
    }

    @Test
    fun shouldUseCodeDefaults_whenSnapshottedFromCleanSettings() {
        val snapshot = EditorRenderSettings.from(settings)

        assertEquals(PreviewMaxEdge.EDGE_2048, snapshot.previewMaxEdge)
        assertEquals(2048, snapshot.previewMaxEdgePx)
        assertEquals(1024, snapshot.proxyMaxEdgePx)
        assertEquals(1024, snapshot.skyPreviewMaxEdgePx)
        assertEquals(EditorSettings.DEFAULT_EXPORT_JPEG_QUALITY, snapshot.exportJpegQuality)
        assertEquals(EditorSettings.DEFAULT_PREVIEW_CACHE_JPEG_QUALITY, snapshot.previewCacheJpegQuality)
        assertEquals(EditorSettings.DEFAULT_UNDO_DEPTH, snapshot.undoDepth)
    }

    @Test
    fun shouldFollowTheSelectedPreviewEdge_whenSnapshotted() {
        settings.previewMaxEdge = PreviewMaxEdge.EDGE_1536
        settings.undoDepth = 120
        settings.exportJpegQuality = 90
        settings.previewCacheJpegQuality = 85

        val snapshot = EditorRenderSettings.from(settings)

        assertEquals(1536, snapshot.previewMaxEdgePx)
        assertEquals(768, snapshot.skyPreviewMaxEdgePx)
        assertEquals(
            "the proxy never exceeds the full preview",
            1024,
            snapshot.proxyMaxEdgePx
        )
        assertEquals(120, snapshot.undoDepth)
        assertEquals(90, snapshot.exportJpegQuality)
        assertEquals(85, snapshot.previewCacheJpegQuality)

        settings.previewMaxEdge = PreviewMaxEdge.EDGE_1024
        val smallest = EditorRenderSettings.from(settings)
        assertEquals(1024, smallest.previewMaxEdgePx)
        assertEquals(1024, smallest.proxyMaxEdgePx)
        assertEquals(512, smallest.skyPreviewMaxEdgePx)
    }

    @Test
    fun shouldClampOutOfRangeValues_whenSnapshottedFromAPreviousBuild() {
        preferences.edit()
            .putInt(EditorSettings.KEY_EXPORT_JPEG_QUALITY, 10)
            .putInt(EditorSettings.KEY_PREVIEW_CACHE_JPEG_QUALITY, 200)
            .putInt(EditorSettings.KEY_EDITOR_UNDO_DEPTH, 1)
            .putInt(EditorSettings.KEY_PREVIEW_MAX_EDGE, 4096)
            .commit()

        val snapshot = EditorRenderSettings.from(settings)

        assertEquals(EditorSettings.MIN_EXPORT_JPEG_QUALITY, snapshot.exportJpegQuality)
        assertEquals(EditorSettings.MAX_PREVIEW_CACHE_JPEG_QUALITY, snapshot.previewCacheJpegQuality)
        assertEquals(EditorSettings.MIN_UNDO_DEPTH, snapshot.undoDepth)
        assertEquals(PreviewMaxEdge.EDGE_2048, snapshot.previewMaxEdge)
    }

    @Test
    fun shouldReadTheAppOwnedScreenOnKey_whenUsingDisplaySettings() {
        assertTrueDefault()
        preferences.edit().putBoolean(DisplaySettings.KEY_KEEP_SCREEN_ON, false).commit()
        assertFalse(DisplaySettings.isKeepScreenOn(context))
    }

    @Test
    fun shouldStayOutsideTheEditorKeySet_whenDeclaringTheScreenOnKey() {
        assertFalse(DisplaySettings.KEY_KEEP_SCREEN_ON in EditorSettings.ALL_KEYS)
        assertFalse(EditorSettings.isMeasuredTileMsKey(DisplaySettings.KEY_KEEP_SCREEN_ON))
    }

    @Test
    fun shouldExposeTheSameNumericDefaultsAsTheOrtRuntimeConfig() {
        assertEquals(OrtSessionRuntimeConfig.MIN_INTRA_OP_THREADS, EditorSettings.MIN_INTRA_OP_THREADS)
        assertEquals(OrtSessionRuntimeConfig.MAX_INTRA_OP_THREADS, EditorSettings.MAX_INTRA_OP_THREADS)
        assertEquals(OrtSessionRuntimeConfig.DEFAULT_INTRA_OP_THREADS, EditorSettings.DEFAULT_INTRA_OP_THREADS)
    }

    private fun assertTrueDefault() {
        assertEquals(DisplaySettings.DEFAULT_KEEP_SCREEN_ON, DisplaySettings.isKeepScreenOn(context))
    }
}
