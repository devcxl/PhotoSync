package cn.devcxl.photosync.editor.settings

import java.util.Locale

import android.content.Context
import android.content.SharedPreferences

import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.OrtSessionRuntimeConfig

/** Editor preview long edge presets, with the sky preview edge derived from the same choice. */
enum class PreviewMaxEdge(val maxEdgePx: Int, val skyPreviewMaxEdgePx: Int) {

    /** Sharpest preview; the historical default. */
    EDGE_2048(2048, 1024),

    /** Middle ground for slower devices. */
    EDGE_1536(1536, 768),

    /** Fastest preview, matching the drag proxy resolution. */
    EDGE_1024(1024, 512);

    companion object {

        /** Resolves a persisted pixel value, or `null` when it is not a known preset. */
        fun fromMaxEdgePx(maxEdgePx: Int): PreviewMaxEdge? =
            entries.firstOrNull { it.maxEdgePx == maxEdgePx }
    }
}

/** User override of the [DeviceMemoryTier] inferred from physical RAM. */
enum class AiTierOverride {

    /** Use the tier detected from total RAM. */
    AUTO,

    /** Always run the low-tier policy. */
    LOW,

    /** Always run the standard-tier policy. */
    STANDARD
}

/** Idle lifetime of a warm ORT session. */
enum class AiIdleRelease(val timeoutMs: Long?) {

    /** Release after 10 seconds idle. */
    TEN_SECONDS(10_000L),

    /** Release after 30 seconds idle; the historical default. */
    THIRTY_SECONDS(30_000L),

    /** Release after 60 seconds idle. */
    SIXTY_SECONDS(60_000L),

    /** Release as soon as inference finishes. */
    IMMEDIATE(null);

    /** True when the session must be closed right after each inference run. */
    val isImmediate: Boolean get() = timeoutMs == null
}

/** Default quality mode of the AI inpaint tool. */
enum class InpaintQualityMode {

    /** Overlapping cores, better detail, slower. */
    FINE,

    /** Non-overlapping fast cores, about half the tiles. */
    FAST
}

/** Effective editor/AI settings, read from [EditorSettings] for diagnostics and read-only display. */
data class EditorSettingsSnapshot(
    val previewMaxEdge: PreviewMaxEdge,
    val exportJpegQuality: Int,
    val previewCacheJpegQuality: Int,
    val undoDepth: Int,
    val tierOverride: AiTierOverride,
    val intraOpThreads: Int,
    val idleRelease: AiIdleRelease,
    val inpaintDefaultMode: InpaintQualityMode,
    val inpaintBrushPx: Int
)

/**
 * Typed facade over the editor and AI half of [SettingsStorage.FILE_NAME].
 *
 * Every key declares its type, default and legal range here; getters clamp values that were written
 * by an older build, and setters clamp before persisting. These settings only choose runtime defaults
 * and policies: they never change `EditGraph` semantics or the pixels a bake produces.
 *
 * The `:app` side `AppSettings` owns a disjoint key set in the same file; [ALL_KEYS] is exported so a
 * test can prove the two sets never overlap.
 */
class EditorSettings(private val preferences: SharedPreferences) {

    /** Opens the shared settings file. */
    constructor(context: Context) : this(SettingsStorage.open(context))

    /**
     * Preview long edge for the editor and the sky layer.
     *
     * Key [KEY_PREVIEW_MAX_EDGE]. Enum [PreviewMaxEdge]; default [PreviewMaxEdge.EDGE_2048]. The sky
     * preview edge follows at 1024/768/512. Applies the next time a photo is opened.
     */
    var previewMaxEdge: PreviewMaxEdge
        get() = PreviewMaxEdge.fromMaxEdgePx(
            preferences.getInt(KEY_PREVIEW_MAX_EDGE, DEFAULT_PREVIEW_MAX_EDGE.maxEdgePx)
        ) ?: DEFAULT_PREVIEW_MAX_EDGE
        set(value) {
            preferences.edit().putInt(KEY_PREVIEW_MAX_EDGE, value.maxEdgePx).apply()
        }

    /**
     * JPEG quality for the final bake/export.
     *
     * Key [KEY_EXPORT_JPEG_QUALITY]. Int [MIN_EXPORT_JPEG_QUALITY]..[MAX_EXPORT_JPEG_QUALITY];
     * default [DEFAULT_EXPORT_JPEG_QUALITY]. Applies to the next export.
     */
    var exportJpegQuality: Int
        get() = clampedInt(
            KEY_EXPORT_JPEG_QUALITY,
            DEFAULT_EXPORT_JPEG_QUALITY,
            MIN_EXPORT_JPEG_QUALITY,
            MAX_EXPORT_JPEG_QUALITY
        )
        set(value) {
            writeClampedInt(
                KEY_EXPORT_JPEG_QUALITY,
                value,
                MIN_EXPORT_JPEG_QUALITY,
                MAX_EXPORT_JPEG_QUALITY
            )
        }

    /**
     * JPEG quality of the cached editor preview.
     *
     * Key [KEY_PREVIEW_CACHE_JPEG_QUALITY]. Int [MIN_PREVIEW_CACHE_JPEG_QUALITY]..
     * [MAX_PREVIEW_CACHE_JPEG_QUALITY]; default [DEFAULT_PREVIEW_CACHE_JPEG_QUALITY]. Applies the
     * next time a preview is written.
     */
    var previewCacheJpegQuality: Int
        get() = clampedInt(
            KEY_PREVIEW_CACHE_JPEG_QUALITY,
            DEFAULT_PREVIEW_CACHE_JPEG_QUALITY,
            MIN_PREVIEW_CACHE_JPEG_QUALITY,
            MAX_PREVIEW_CACHE_JPEG_QUALITY
        )
        set(value) {
            writeClampedInt(
                KEY_PREVIEW_CACHE_JPEG_QUALITY,
                value,
                MIN_PREVIEW_CACHE_JPEG_QUALITY,
                MAX_PREVIEW_CACHE_JPEG_QUALITY
            )
        }

    /**
     * Maximum number of undo steps kept per photo.
     *
     * Key [KEY_EDITOR_UNDO_DEPTH]. Int [MIN_UNDO_DEPTH]..[MAX_UNDO_DEPTH]; default
     * [DEFAULT_UNDO_DEPTH]. Applies the next time the editor opens.
     */
    var undoDepth: Int
        get() = clampedInt(KEY_EDITOR_UNDO_DEPTH, DEFAULT_UNDO_DEPTH, MIN_UNDO_DEPTH, MAX_UNDO_DEPTH)
        set(value) {
            writeClampedInt(KEY_EDITOR_UNDO_DEPTH, value, MIN_UNDO_DEPTH, MAX_UNDO_DEPTH)
        }

    /**
     * Device tier override for AI runtime policy.
     *
     * Key [KEY_AI_TIER_OVERRIDE]. Enum [AiTierOverride]; default [AiTierOverride.AUTO]. Resolve it
     * through [resolveMemoryTier] so every call site shares one rule.
     */
    var tierOverride: AiTierOverride
        get() = readEnum(KEY_AI_TIER_OVERRIDE, AiTierOverride.AUTO)
        set(value) {
            preferences.edit().putString(KEY_AI_TIER_OVERRIDE, value.name).apply()
        }

    /**
     * ORT intra-op thread count.
     *
     * Key [KEY_AI_INTRA_OP_THREADS]. Int [MIN_INTRA_OP_THREADS]..[MAX_INTRA_OP_THREADS]; default
     * [DEFAULT_INTRA_OP_THREADS]. A new session picks it up the next time one is created.
     */
    var intraOpThreads: Int
        get() = clampedInt(
            KEY_AI_INTRA_OP_THREADS,
            DEFAULT_INTRA_OP_THREADS,
            MIN_INTRA_OP_THREADS,
            MAX_INTRA_OP_THREADS
        )
        set(value) {
            writeClampedInt(
                KEY_AI_INTRA_OP_THREADS,
                value,
                MIN_INTRA_OP_THREADS,
                MAX_INTRA_OP_THREADS
            )
        }

    /**
     * Idle release policy of a warm ORT session.
     *
     * Key [KEY_AI_IDLE_RELEASE]. Enum [AiIdleRelease]; default [AiIdleRelease.THIRTY_SECONDS].
     */
    var idleRelease: AiIdleRelease
        get() = readEnum(KEY_AI_IDLE_RELEASE, AiIdleRelease.THIRTY_SECONDS)
        set(value) {
            preferences.edit().putString(KEY_AI_IDLE_RELEASE, value.name).apply()
        }

    /**
     * Initial quality mode of the inpaint panel.
     *
     * Key [KEY_AI_INPAINT_DEFAULT_MODE]. Enum [InpaintQualityMode]; default
     * [InpaintQualityMode.FINE]. Applies when the inpaint panel next opens.
     */
    var inpaintDefaultMode: InpaintQualityMode
        get() = readEnum(KEY_AI_INPAINT_DEFAULT_MODE, InpaintQualityMode.FINE)
        set(value) {
            preferences.edit().putString(KEY_AI_INPAINT_DEFAULT_MODE, value.name).apply()
        }

    /**
     * Initial inpaint brush diameter in source pixels.
     *
     * Key [KEY_AI_INPAINT_BRUSH_PX]. Int [MIN_INPAINT_BRUSH_PX]..[MAX_INPAINT_BRUSH_PX]; default
     * [DEFAULT_INPAINT_BRUSH_PX]. Applies when the inpaint panel next opens.
     */
    var inpaintBrushPx: Int
        get() = clampedInt(
            KEY_AI_INPAINT_BRUSH_PX,
            DEFAULT_INPAINT_BRUSH_PX,
            MIN_INPAINT_BRUSH_PX,
            MAX_INPAINT_BRUSH_PX
        )
        set(value) {
            writeClampedInt(
                KEY_AI_INPAINT_BRUSH_PX,
                value,
                MIN_INPAINT_BRUSH_PX,
                MAX_INPAINT_BRUSH_PX
            )
        }

    /**
     * Resolves [detected] through the user override.
     *
     * `AUTO` returns [detected] unchanged, `LOW`/`STANDARD` pin the corresponding tier. Every AI call
     * site must go through this method instead of reading the override itself.
     */
    fun resolveMemoryTier(detected: DeviceMemoryTier): DeviceMemoryTier = when (tierOverride) {
        AiTierOverride.AUTO -> detected
        AiTierOverride.LOW -> DeviceMemoryTier.LOW
        AiTierOverride.STANDARD -> DeviceMemoryTier.STANDARD
    }

    /** Detects the physical-RAM tier and applies the user override. */
    fun resolveMemoryTier(context: Context): DeviceMemoryTier =
        resolveMemoryTier(DeviceMemoryTier.detect(context))

    /**
     * Measured single-tile inpaint time persisted by the previous run.
     *
     * @return Milliseconds, or `null` when nothing was measured for this tier/variant yet.
     */
    fun getMeasuredInpaintTileMs(tier: DeviceMemoryTier, variantKey: String): Long? {
        if (variantKey.isBlank()) return null
        val key = measuredInpaintTileMsKey(tier, variantKey)
        return preferences.getLong(key, 0L).takeIf { it > 0L }
    }

    /**
     * Stores the measured single-tile inpaint time.
     *
     * Non-positive readings are rejected by removing the entry, so an eta estimate can never read a
     * zero that would silently look like a measurement.
     */
    fun setMeasuredInpaintTileMs(tier: DeviceMemoryTier, variantKey: String, ms: Long) {
        if (variantKey.isBlank()) return
        val editor = preferences.edit()
        val key = measuredInpaintTileMsKey(tier, variantKey)
        if (ms > 0L) editor.putLong(key, ms) else editor.remove(key)
        editor.apply()
    }

    /** Namespaced key of one measured tile time, e.g. `ai_inpaint_tile_ms_standard_PIPELINE_UINT8`. */
    fun measuredInpaintTileMsKey(tier: DeviceMemoryTier, variantKey: String): String =
        MEASURED_TILE_MS_KEY_PREFIX + tier.name.lowercase(Locale.ROOT) + "_" + variantKey.trim()

    /** Current effective values, for the read-only diagnostics summary. */
    fun snapshot(): EditorSettingsSnapshot = EditorSettingsSnapshot(
        previewMaxEdge = previewMaxEdge,
        exportJpegQuality = exportJpegQuality,
        previewCacheJpegQuality = previewCacheJpegQuality,
        undoDepth = undoDepth,
        tierOverride = tierOverride,
        intraOpThreads = intraOpThreads,
        idleRelease = idleRelease,
        inpaintDefaultMode = inpaintDefaultMode,
        inpaintBrushPx = inpaintBrushPx
    )

    /** Removes every editor/AI key, including persisted tile timings, so defaults apply again. */
    fun resetAll() {
        val editor = preferences.edit()
        ALL_KEYS.forEach { editor.remove(it) }
        preferences.all.keys
            .filter(::isMeasuredTileMsKey)
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun clampedInt(key: String, default: Int, min: Int, max: Int): Int =
        preferences.getInt(key, default).coerceIn(min, max)

    private fun writeClampedInt(key: String, value: Int, min: Int, max: Int) {
        preferences.edit().putInt(key, value.coerceIn(min, max)).apply()
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val stored = preferences.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }

    companion object {

        /** Shared file name, re-exported for the `:app` facade and tests. */
        const val FILE_NAME: String = SettingsStorage.FILE_NAME

        const val KEY_PREVIEW_MAX_EDGE = "editor_preview_max_edge"
        const val KEY_EXPORT_JPEG_QUALITY = "export_jpeg_quality"
        const val KEY_PREVIEW_CACHE_JPEG_QUALITY = "preview_cache_jpeg_quality"
        const val KEY_EDITOR_UNDO_DEPTH = "editor_undo_depth"
        const val KEY_AI_TIER_OVERRIDE = "ai_tier_override"
        const val KEY_AI_INTRA_OP_THREADS = "ai_intra_op_threads"
        const val KEY_AI_IDLE_RELEASE = "ai_idle_release"
        const val KEY_AI_INPAINT_DEFAULT_MODE = "ai_inpaint_default_mode"
        const val KEY_AI_INPAINT_BRUSH_PX = "ai_inpaint_brush_px"

        /** Prefix of the per-tier/per-variant measured tile times. */
        const val MEASURED_TILE_MS_KEY_PREFIX = "ai_inpaint_tile_ms_"

        /** Every statically declared key; dynamic tile-time keys carry [MEASURED_TILE_MS_KEY_PREFIX]. */
        val ALL_KEYS: Set<String> = setOf(
            KEY_PREVIEW_MAX_EDGE,
            KEY_EXPORT_JPEG_QUALITY,
            KEY_PREVIEW_CACHE_JPEG_QUALITY,
            KEY_EDITOR_UNDO_DEPTH,
            KEY_AI_TIER_OVERRIDE,
            KEY_AI_INTRA_OP_THREADS,
            KEY_AI_IDLE_RELEASE,
            KEY_AI_INPAINT_DEFAULT_MODE,
            KEY_AI_INPAINT_BRUSH_PX
        )

        const val MIN_EXPORT_JPEG_QUALITY = 85
        const val MAX_EXPORT_JPEG_QUALITY = 100
        const val DEFAULT_EXPORT_JPEG_QUALITY = 95

        const val MIN_PREVIEW_CACHE_JPEG_QUALITY = 80
        const val MAX_PREVIEW_CACHE_JPEG_QUALITY = 95
        const val DEFAULT_PREVIEW_CACHE_JPEG_QUALITY = 92

        const val MIN_UNDO_DEPTH = 20
        const val MAX_UNDO_DEPTH = 200
        const val DEFAULT_UNDO_DEPTH = 50

        const val MIN_INTRA_OP_THREADS = OrtSessionRuntimeConfig.MIN_INTRA_OP_THREADS
        const val MAX_INTRA_OP_THREADS = OrtSessionRuntimeConfig.MAX_INTRA_OP_THREADS
        const val DEFAULT_INTRA_OP_THREADS = OrtSessionRuntimeConfig.DEFAULT_INTRA_OP_THREADS

        const val MIN_INPAINT_BRUSH_PX = 8
        const val MAX_INPAINT_BRUSH_PX = 1200
        const val DEFAULT_INPAINT_BRUSH_PX = 64

        private val DEFAULT_PREVIEW_MAX_EDGE = PreviewMaxEdge.EDGE_2048

        /** True when [key] is one of the per-tier/per-variant measured tile times. */
        fun isMeasuredTileMsKey(key: String): Boolean = key.startsWith(MEASURED_TILE_MS_KEY_PREFIX)
    }
}
