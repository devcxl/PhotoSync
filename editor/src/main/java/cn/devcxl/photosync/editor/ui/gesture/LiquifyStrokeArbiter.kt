package cn.devcxl.photosync.editor.ui.gesture

/**
 * What must happen to the single-finger liquify stroke already in flight when a second finger
 * lands on the canvas.
 */
internal enum class LiquifyPreemption {
    /** The stroke never left the slop circle: roll it back and hand the canvas to pinch/pan. */
    ROLL_BACK_UNCOMMITTED_STROKE,

    /** The stroke is a deliberate smear: seal it as an undo step, then pinch/pan. */
    KEEP_STROKE
}

/**
 * Decides whether a liquify stroke survives the arrival of a second finger.
 *
 * The rule from the spec is "two fingers always zoom", but a photographer who has already dragged
 * the brush deliberately expects to keep that stroke. So the arbiter compares the cumulative
 * movement since `ACTION_DOWN` against the 8dp slop: below it the gesture is still ambiguous and is
 * rolled back, at or above it the stroke is committed and only then does the pinch take over.
 */
internal class LiquifyStrokeArbiter(private val slopPx: Float) {

    /** Cumulative travel of the single touch since `ACTION_DOWN`, in pixels. */
    var travelPx: Float = 0.0f
        private set

    /** True while a stroke is bound to the single finger. */
    var isStrokeActive: Boolean = false
        private set

    /** True when the touch moved at all since `ACTION_DOWN`. */
    val hasMoved: Boolean
        get() = travelPx > 0.0f

    /** True once the touch has travelled far enough to count as a deliberate stroke under 2-finger preemption. */
    val hasMovedPastSlop: Boolean
        get() = travelPx >= slopPx

    /** Binds the arbiter to a new single-finger gesture. */
    fun beginStroke() {
        travelPx = 0.0f
        isStrokeActive = true
    }

    /** Accumulates one movement step, in pixels. */
    fun onMove(stepPx: Float) {
        if (!isStrokeActive || !stepPx.isFinite() || stepPx <= 0.0f) return
        travelPx += stepPx
    }

    /**
     * Resolves the two-finger preemption rule and closes the stroke either way.
     *
     * @return [LiquifyPreemption.ROLL_BACK_UNCOMMITTED_STROKE] when the uncommitted increment must
     *   be discarded, or [LiquifyPreemption.KEEP_STROKE] when it must be committed first.
     */
    fun onSecondPointerDown(): LiquifyPreemption {
        val preemption = if (hasMovedPastSlop) {
            LiquifyPreemption.KEEP_STROKE
        } else {
            LiquifyPreemption.ROLL_BACK_UNCOMMITTED_STROKE
        }
        isStrokeActive = false
        return preemption
    }

    /**
     * Ends the stroke when the finger lifts without a second pointer arriving.
     *
     * @return True when the touch moved (`> 0px`) and must be committed as an undo step; false
     *   for a stationary tap or an already-preempted stroke.
     */
    fun endStroke(): Boolean {
        if (!isStrokeActive) return false
        isStrokeActive = false
        return hasMoved
    }

    /** Forgets the stroke, for example when a gesture is cancelled. */
    fun cancel() {
        isStrokeActive = false
        travelPx = 0.0f
    }
}
