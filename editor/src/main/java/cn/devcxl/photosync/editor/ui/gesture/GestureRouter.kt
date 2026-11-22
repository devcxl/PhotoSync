package cn.devcxl.photosync.editor.ui.gesture

/**
 * The six fixed category tabs of the editor drawer.
 */
enum class EditorGroup {
    PRESET,
    GEOMETRY,
    TONE,
    COLOR,
    DETAIL,
    PORTRAIT
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
    EXPOSURE,
    DISPERSION_RED_CYAN,
    DISPERSION_BLUE_YELLOW
}

/**
 * Who owns a touch gesture on the preview canvas.
 */
enum class GestureRoute {
    /** Pinch/pan the canvas. Always preempts every single-finger tool. */
    CANVAS_TWO_FINGER_PAN_ZOOM,

    /** Drag crop corners/edges, the straighten dial, or the four perspective pins. */
    CROP_PERSPECTIVE_HANDLE,

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

    private val CANVAS_HANDLE_TOOLS = setOf(
        EditorTool.CROP,
        EditorTool.STRAIGHTEN,
        EditorTool.PERSPECTIVE
    )

    /**
     * Returns the route that must own the current gesture.
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
        return GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE
    }

    private const val TWO_FINGERS = 2
}
