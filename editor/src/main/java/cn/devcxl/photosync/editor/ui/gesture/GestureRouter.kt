package cn.devcxl.photosync.editor.ui.gesture

/**
 * The seven fixed category tabs of the editor drawer.
 */
enum class EditorGroup {
    PRESET,
    GEOMETRY,
    TONE,
    COLOR,
    DETAIL,
    PORTRAIT,
    AI
}

/**
 * The single tool currently bound to the tier-1 control slot and to canvas gestures.
 */
enum class EditorTool {
    NONE,
    CROP,
    STRAIGHTEN,
    ROTATE90,
    FLIP_HORIZONTAL,
    FLIP_VERTICAL,
    PERSPECTIVE,
    LIQUIFY,
    EXPOSURE,
    BRIGHTNESS,
    CONTRAST,
    HIGHLIGHTS,
    SHADOWS,
    WHITES,
    BLACKS,
    GLOW,
    TEMPERATURE,
    TINT,
    SATURATION,
    VIBRANCE,
    CURVE,
    HSL,
    SPLIT_TONING,
    FADE,
    LUT,
    CLARITY,
    TEXTURE,
    DEHAZE,
    SHARPEN,
    NOISE_REDUCTION,
    DISPERSION_RED_CYAN,
    DISPERSION_BLUE_YELLOW,
    DEFRINGE,
    GRAIN,
    VIGNETTE,
    SKIN_SMOOTH,
    FACE_RESHAPE,
    BODY_RESHAPE,
    AI_SUBJECT,
    AI_ERASE,
    AI_SKY,
    AI_CLOUD,
    PRESET
}

/**
 * Who owns a touch gesture on the preview canvas.
 */
enum class GestureRoute {
    /** Pinch/pan the canvas. Always preempts every single-finger tool. */
    CANVAS_TWO_FINGER_PAN_ZOOM,

    /** Drag crop corners/edges, the straighten dial, or the four perspective pins. */
    CROP_PERSPECTIVE_HANDLE,

    /** Paint displacement into the `128 x 128` liquify grid. */
    LIQUIFY_BRUSH_STROKE,

    /** Paint the inpainting removal mask. */
    INPAINT_BRUSH_STROKE,

    /** Pan the canvas with one finger and hold still to compare against the original. */
    CANVAS_SINGLE_PAN_HOLD_COMPARE
}

/**
 * Resolves touch ownership for the editor canvas.
 *
 * The routing order is deliberate: two fingers always win, because a photographer zooming in to
 * inspect a corner must never accidentally move that corner. Only the geometry tools that are
 * driven by on-canvas handles claim single-finger drags; every other tool leaves the canvas to
 * pan and to press-and-hold comparison.
 */
object GestureRouter {

    /**
     * Cumulative movement below which a liquify stroke is treated as an accidental touch when a
     * second finger lands, expressed in density-independent pixels.
     */
    const val LIQUIFY_PREEMPTION_SLOP_DP = 8.0f

    private val CANVAS_HANDLE_TOOLS = setOf(
        EditorTool.CROP,
        EditorTool.STRAIGHTEN,
        EditorTool.PERSPECTIVE
    )

    /**
     * Returns the route that must own the current gesture.
     *
     * The order is the contract: two fingers always win, then a geometry handle, then a brush. The
     * inpainting brush follows the liquify rule — a single finger paints, and a second finger
     * discards the unfinished dab and takes over zoom/pan, so "pinch, don't smear" holds for both
     * brush tools.
     *
     * @param group Currently selected category tab.
     * @param tool Currently selected sub-tool.
     * @param pointerCount Number of pointers currently down on the canvas.
     */
    fun resolveGestureRoute(
        group: EditorGroup,
        tool: EditorTool,
        pointerCount: Int
    ): GestureRoute {
        if (pointerCount >= TWO_FINGERS) return GestureRoute.CANVAS_TWO_FINGER_PAN_ZOOM
        if (group == EditorGroup.GEOMETRY && tool in CANVAS_HANDLE_TOOLS) {
            return GestureRoute.CROP_PERSPECTIVE_HANDLE
        }
        if (group == EditorGroup.GEOMETRY && tool == EditorTool.LIQUIFY) {
            return GestureRoute.LIQUIFY_BRUSH_STROKE
        }
        if (group == EditorGroup.AI && tool == EditorTool.AI_ERASE) {
            return GestureRoute.INPAINT_BRUSH_STROKE
        }
        return GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE
    }

    /**
     * Converts [LIQUIFY_PREEMPTION_SLOP_DP] into pixels for [density], the value the
     * [LiquifyStrokeArbiter] compares against.
     */
    fun preemptionSlopPx(density: Float): Float =
        LIQUIFY_PREEMPTION_SLOP_DP * (density.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f)

    private const val TWO_FINGERS = 2
}
