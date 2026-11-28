package cn.devcxl.photosync.editor.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Seam under test: [EditorSettings] — documented defaults, clamping, tier-override resolution and the
 * per-tier/per-variant measured tile time used by the eta estimate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorSettingsTest {

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
    fun shouldUseDocumentedDefaults_whenNothingPersisted() {
        assertEquals(PreviewMaxEdge.EDGE_2048, settings.previewMaxEdge)
        assertEquals(2048, settings.previewMaxEdge.maxEdgePx)
        assertEquals(1024, settings.previewMaxEdge.skyPreviewMaxEdgePx)
        assertEquals(95, settings.exportJpegQuality)
        assertEquals(92, settings.previewCacheJpegQuality)
        assertEquals(50, settings.undoDepth)
        assertEquals(AiTierOverride.AUTO, settings.tierOverride)
        assertEquals(2, settings.intraOpThreads)
        assertEquals(AiIdleRelease.THIRTY_SECONDS, settings.idleRelease)
        assertEquals(30_000L, settings.idleRelease.timeoutMs)
        assertFalse(settings.idleRelease.isImmediate)
        assertEquals(InpaintQualityMode.FINE, settings.inpaintDefaultMode)
        assertEquals(64, settings.inpaintBrushPx)
    }

    @Test
    fun shouldPersistEveryValue_whenWritten() {
        settings.previewMaxEdge = PreviewMaxEdge.EDGE_1536
        settings.exportJpegQuality = 90
        settings.previewCacheJpegQuality = 85
        settings.undoDepth = 120
        settings.tierOverride = AiTierOverride.LOW
        settings.intraOpThreads = 4
        settings.idleRelease = AiIdleRelease.IMMEDIATE
        settings.inpaintDefaultMode = InpaintQualityMode.FAST
        settings.inpaintBrushPx = 200

        val reopened = EditorSettings(SettingsStorage.open(context))
        assertEquals(PreviewMaxEdge.EDGE_1536, reopened.previewMaxEdge)
        assertEquals(768, reopened.previewMaxEdge.skyPreviewMaxEdgePx)
        assertEquals(90, reopened.exportJpegQuality)
        assertEquals(85, reopened.previewCacheJpegQuality)
        assertEquals(120, reopened.undoDepth)
        assertEquals(AiTierOverride.LOW, reopened.tierOverride)
        assertEquals(4, reopened.intraOpThreads)
        assertEquals(AiIdleRelease.IMMEDIATE, reopened.idleRelease)
        assertTrue(reopened.idleRelease.isImmediate)
        assertNull(reopened.idleRelease.timeoutMs)
        assertEquals(InpaintQualityMode.FAST, reopened.inpaintDefaultMode)
        assertEquals(200, reopened.inpaintBrushPx)
    }

    @Test
    fun shouldClampToLegalRange_whenWritingOutOfRange() {
        settings.exportJpegQuality = 10
        settings.previewCacheJpegQuality = 10
        settings.undoDepth = 5
        settings.intraOpThreads = 9
        settings.inpaintBrushPx = 5

        assertEquals(85, settings.exportJpegQuality)
        assertEquals(80, settings.previewCacheJpegQuality)
        assertEquals(20, settings.undoDepth)
        assertEquals(4, settings.intraOpThreads)
        assertEquals(8, settings.inpaintBrushPx)

        settings.exportJpegQuality = 999
        settings.previewCacheJpegQuality = 999
        settings.undoDepth = 999
        settings.intraOpThreads = 0
        settings.inpaintBrushPx = 99_999

        assertEquals(100, settings.exportJpegQuality)
        assertEquals(95, settings.previewCacheJpegQuality)
        assertEquals(200, settings.undoDepth)
        assertEquals(1, settings.intraOpThreads)
        assertEquals(1200, settings.inpaintBrushPx)
    }

    @Test
    fun shouldClampOutOfRangeRawValues_whenReadingAPreviousBuild() {
        preferences.edit()
            .putInt(EditorSettings.KEY_EXPORT_JPEG_QUALITY, 50)
            .putInt(EditorSettings.KEY_PREVIEW_CACHE_JPEG_QUALITY, 99)
            .putInt(EditorSettings.KEY_EDITOR_UNDO_DEPTH, 1_000)
            .putInt(EditorSettings.KEY_AI_INTRA_OP_THREADS, 8)
            .putInt(EditorSettings.KEY_AI_INPAINT_BRUSH_PX, 1)
            .putInt(EditorSettings.KEY_PREVIEW_MAX_EDGE, 4096)
            .putString(EditorSettings.KEY_AI_TIER_OVERRIDE, "TURBO")
            .putString(EditorSettings.KEY_AI_IDLE_RELEASE, "FOREVER")
            .putString(EditorSettings.KEY_AI_INPAINT_DEFAULT_MODE, "ULTRA")
            .commit()

        assertEquals(85, settings.exportJpegQuality)
        assertEquals(95, settings.previewCacheJpegQuality)
        assertEquals(200, settings.undoDepth)
        assertEquals(4, settings.intraOpThreads)
        assertEquals(8, settings.inpaintBrushPx)
        assertEquals(PreviewMaxEdge.EDGE_2048, settings.previewMaxEdge)
        assertEquals(AiTierOverride.AUTO, settings.tierOverride)
        assertEquals(AiIdleRelease.THIRTY_SECONDS, settings.idleRelease)
        assertEquals(InpaintQualityMode.FINE, settings.inpaintDefaultMode)
    }

    @Test
    fun shouldKeepDetectedTier_whenOverrideIsAuto() {
        assertEquals(DeviceMemoryTier.LOW, settings.resolveMemoryTier(DeviceMemoryTier.LOW))
        assertEquals(
            DeviceMemoryTier.STANDARD, settings.resolveMemoryTier(DeviceMemoryTier.STANDARD)
        )
    }

    @Test
    fun shouldPinTier_whenOverrideIsExplicit() {
        settings.tierOverride = AiTierOverride.LOW
        assertEquals(DeviceMemoryTier.LOW, settings.resolveMemoryTier(DeviceMemoryTier.STANDARD))
        assertEquals(DeviceMemoryTier.LOW, settings.resolveMemoryTier(DeviceMemoryTier.LOW))

        settings.tierOverride = AiTierOverride.STANDARD
        assertEquals(DeviceMemoryTier.STANDARD, settings.resolveMemoryTier(DeviceMemoryTier.LOW))
        assertEquals(
            DeviceMemoryTier.STANDARD, settings.resolveMemoryTier(DeviceMemoryTier.STANDARD)
        )
    }

    @Test
    fun shouldResolveFromContext_whenOverrideIsAuto() {
        val detected = DeviceMemoryTier.detect(context)
        assertEquals(detected, settings.resolveMemoryTier(context))
    }

    @Test
    fun shouldSeparateMeasuredTileTimes_whenTierAndVariantDiffer() {
        assertNull(settings.getMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8"))
        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8", 1_150L)
        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.STANDARD, "PIPELINE_UINT8", 900L)
        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.STANDARD, "GENERATOR_FIXED_512", 1_800L)

        assertEquals(1_150L, settings.getMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8"))
        assertEquals(
            900L,
            settings.getMeasuredInpaintTileMs(DeviceMemoryTier.STANDARD, "PIPELINE_UINT8")
        )
        assertEquals(
            1_800L,
            settings.getMeasuredInpaintTileMs(DeviceMemoryTier.STANDARD, "GENERATOR_FIXED_512")
        )
        assertNull(settings.getMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "GENERATOR_FIXED_512"))
    }

    @Test
    fun shouldRemoveTileTime_whenMeasurementIsNotPositive() {
        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8", 1_150L)
        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8", 0L)
        assertNull(settings.getMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8"))

        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8", -5L)
        assertNull(settings.getMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "PIPELINE_UINT8"))
    }

    @Test
    fun shouldIgnoreBlankVariantKey_whenReadingOrWritingTileTime() {
        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "   ", 1_500L)
        assertNull(settings.getMeasuredInpaintTileMs(DeviceMemoryTier.LOW, "   "))
        assertTrue(preferences.all.keys.none { EditorSettings.isMeasuredTileMsKey(it) })
    }

    @Test
    fun shouldUseNamespacedKey_whenStoringTileTime() {
        val key = settings.measuredInpaintTileMsKey(DeviceMemoryTier.STANDARD, "PIPELINE_UINT8")
        assertEquals("ai_inpaint_tile_ms_standard_PIPELINE_UINT8", key)
        assertTrue(EditorSettings.isMeasuredTileMsKey(key))
        assertFalse(key in EditorSettings.ALL_KEYS)
        assertTrue(EditorSettings.ALL_KEYS.none(EditorSettings::isMeasuredTileMsKey))
    }

    @Test
    fun shouldReportEffectiveValues_whenSnapshotting() {
        settings.previewMaxEdge = PreviewMaxEdge.EDGE_1024
        settings.exportJpegQuality = 100
        settings.previewCacheJpegQuality = 80
        settings.undoDepth = 80
        settings.tierOverride = AiTierOverride.STANDARD
        settings.intraOpThreads = 3
        settings.idleRelease = AiIdleRelease.TEN_SECONDS
        settings.inpaintDefaultMode = InpaintQualityMode.FAST
        settings.inpaintBrushPx = 120

        val snapshot = settings.snapshot()
        assertEquals(PreviewMaxEdge.EDGE_1024, snapshot.previewMaxEdge)
        assertEquals(100, snapshot.exportJpegQuality)
        assertEquals(80, snapshot.previewCacheJpegQuality)
        assertEquals(80, snapshot.undoDepth)
        assertEquals(AiTierOverride.STANDARD, snapshot.tierOverride)
        assertEquals(3, snapshot.intraOpThreads)
        assertEquals(AiIdleRelease.TEN_SECONDS, snapshot.idleRelease)
        assertEquals(InpaintQualityMode.FAST, snapshot.inpaintDefaultMode)
        assertEquals(120, snapshot.inpaintBrushPx)
    }

    @Test
    fun shouldDropEveryEditorKeyAndTileTime_whenResetAll() {
        settings.exportJpegQuality = 88
        settings.setMeasuredInpaintTileMs(DeviceMemoryTier.STANDARD, "PIPELINE_UINT8", 900L)

        settings.resetAll()

        assertEquals(EditorSettings.DEFAULT_EXPORT_JPEG_QUALITY, settings.exportJpegQuality)
        assertNull(settings.getMeasuredInpaintTileMs(DeviceMemoryTier.STANDARD, "PIPELINE_UINT8"))
        assertTrue(
            "resetAll must only touch the editor facade keys",
            preferences.all.keys.none { it in EditorSettings.ALL_KEYS }
        )
    }
}
