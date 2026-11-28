package cn.devcxl.photosync.editor.ui

import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer
import cn.devcxl.photosync.editor.domain.InpaintTileEta
import cn.devcxl.photosync.editor.settings.InpaintQualityMode

/** Brush mode of the P2 inpainting tool. */
enum class InpaintBrushMode {

    /** Adds coverage to the removal mask. */
    PAINT,

    /** Removes coverage painted earlier. */
    ERASE
}

/**
 * Source-pixel brush diameter helpers shared by the panel, the controller and the canvas.
 *
 * The user picks a **diameter in source pixels** (`8..1200`, default `64`); [InpaintStroke] keeps
 * persisting `radiusNorm` as a fraction of the source width so the projection and rasterization
 * contracts do not change. The conversion happens here and nowhere else.
 */
object InpaintBrushSize {

    /** Smallest diameter the panel exposes, in source pixels. */
    const val MIN_DIAMETER_PX: Int = 8

    /** Largest diameter the panel exposes, in source pixels. */
    const val MAX_DIAMETER_PX: Int = 1200

    /** "小" quick preset, in source pixels. */
    const val SMALL_PRESET_PX: Int = 24

    /** "中" quick preset, in source pixels; the historical default. */
    const val MEDIUM_PRESET_PX: Int = 64

    /** "大" quick preset, in source pixels. */
    const val LARGE_PRESET_PX: Int = 160

    /** Preview width used while the true source size has not been probed yet. */
    const val PREVIEW_FALLBACK_WIDTH_PX: Int = 2048

    private const val MIN_RADIUS_NORM = 1e-6f

    /**
     * Converts a source-pixel diameter into the persisted radius fraction.
     *
     * @param diameterPx Diameter in source pixels; clamped to the panel range first.
     * @param sourceWidthPx Width the stroke will be interpreted against; a non-positive value falls
     *   back to [PREVIEW_FALLBACK_WIDTH_PX], the editor preview long edge.
     */
    fun radiusNormForDiameter(diameterPx: Int, sourceWidthPx: Int): Float {
        val width = sourceWidthPx.takeIf { it > 0 } ?: PREVIEW_FALLBACK_WIDTH_PX
        val diameter = diameterPx.coerceIn(MIN_DIAMETER_PX, MAX_DIAMETER_PX)
        return (diameter.toFloat() / (2.0f * width)).coerceAtLeast(MIN_RADIUS_NORM)
    }

    /**
     * Converts a persisted radius fraction back into a source-pixel diameter.
     *
     * @param radiusNorm Radius as a fraction of the source width.
     * @param sourceWidthPx Width the stroke was interpreted against.
     * @return Diameter clamped to `[MIN_DIAMETER_PX], [MAX_DIAMETER_PX]`.
     */
    fun diameterForRadiusNorm(radiusNorm: Float, sourceWidthPx: Int): Int {
        val width = sourceWidthPx.takeIf { it > 0 } ?: PREVIEW_FALLBACK_WIDTH_PX
        val diameter = (radiusNorm * 2.0f * width).roundToInt()
        return diameter.coerceIn(MIN_DIAMETER_PX, MAX_DIAMETER_PX)
    }
}

/**
 * A localized failure of the erase workflow that the host maps to one toast.
 *
 * The progressive runner cannot build Android copy itself (it must stay JVM-testable), so it
 * reports a resource id plus format arguments and `EditorInpaintController` shows the sentence.
 */
internal class InpaintUserMessageException(
    val messageRes: Int,
    val messageArgs: List<Any> = emptyList(),
    cause: Throwable? = null
) : Exception(cause?.message, cause)

/**
 * Canvas operations [cn.devcxl.photosync.editor.ui.panels.InpaintBrushController] needs from the
 * brush overlay.
 *
 * The seam keeps the controller free of `MotionEvent` handling while
 * [cn.devcxl.photosync.editor.ui.widgets.InpaintBrushOverlayView] stays the only owner of touch
 * state; integration supplies the real view, unit tests supply a fake.
 */
interface InpaintBrushCanvasHost {

    /** Armed brush mode. */
    var brushMode: InpaintBrushMode

    /** Brush radius as a fraction of the source-image width. */
    var brushRadiusNorm: Float

    /** Preview width used as a fallback scale until the true source size is probed. */
    var sourceWidth: Int

    /** Preview height used as a fallback scale until the true source size is probed. */
    var sourceHeight: Int

    /** Committed strokes; an in-flight stroke is deliberately not part of the layer. */
    val strokeLayer: InpaintStrokeLayer

    /** Discards the in-flight single-finger stroke, keeping every committed stroke. */
    fun abortActiveStroke()

    /** Clears every committed stroke. */
    fun clearStrokes()

    /**
     * Replaces the committed stroke list, e.g. after a partial commit removed its coverage.
     *
     * An in-flight stroke is discarded: a progressive run owns the canvas while it is active.
     */
    fun replaceStrokes(strokes: List<InpaintStroke>)
}

/**
 * Panel operations [cn.devcxl.photosync.editor.ui.panels.InpaintBrushController] drives.
 *
 * Implemented by [cn.devcxl.photosync.editor.ui.widgets.InpaintPanelView]; a fake in tests records
 * which label and workload the controller asked for.
 */
interface InpaintPanelHost {

    /** Reflects a mode chosen outside the panel (for example a restored tool state). */
    fun setBrushMode(mode: InpaintBrushMode)

    /** Reflects the source-pixel brush diameter chosen outside the panel. */
    fun setBrushDiameterPx(diameterPx: Int)

    /** Reflects the fine/fast quality mode chosen outside the panel. */
    fun setQualityMode(mode: InpaintQualityMode)

    /** Shows the live workload hint, or hides it when [eta] is `null`/empty. */
    fun setWorkload(eta: InpaintTileEta?)

    /** Disables the destructive controls while an inference is running. */
    fun setBusy(busy: Boolean)
}
