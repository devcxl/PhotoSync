package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer

/** Brush mode of the P2 inpainting tool. */
enum class InpaintBrushMode {

    /** Adds coverage to the removal mask. */
    PAINT,

    /** Removes coverage painted earlier. */
    ERASE
}

/** Painted bounding box above this size triggers the amber "split the stroke" advice. */
const val INPAINT_OVERSIZE_LIMIT_PX: Int = 256

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

    /** Width of the source image the strokes are normalized against. */
    var sourceWidth: Int

    /** Height of the source image the strokes are normalized against. */
    var sourceHeight: Int

    /** Committed strokes; an in-flight stroke is deliberately not part of the layer. */
    val strokeLayer: InpaintStrokeLayer

    /** Discards the in-flight single-finger stroke, keeping every committed stroke. */
    fun abortActiveStroke()

    /** Clears every committed stroke. */
    fun clearStrokes()
}

/**
 * Panel operations [cn.devcxl.photosync.editor.ui.panels.InpaintBrushController] drives.
 *
 * Implemented by [cn.devcxl.photosync.editor.ui.widgets.InpaintPanelView]; a fake in tests records
 * which warning and busy state the controller asked for.
 */
interface InpaintPanelHost {

    /** Reflects a mode chosen outside the panel (for example a restored tool state). */
    fun setBrushMode(mode: InpaintBrushMode)

    /** Reflects a brush size chosen outside the panel. */
    fun setBrushRadiusNorm(radiusNorm: Float)

    /** Shows the non-blocking amber advice with the painted size and a split count. */
    fun showOversizeWarning(widthPx: Int, heightPx: Int, suggestedSplits: Int)

    /** Hides the amber advice. */
    fun hideOversizeWarning()

    /** Disables the destructive controls while an inference is running. */
    fun setBusy(busy: Boolean)
}
