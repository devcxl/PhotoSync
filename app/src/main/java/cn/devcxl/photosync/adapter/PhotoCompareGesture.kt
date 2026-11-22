package cn.devcxl.photosync.adapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent

/**
 * Pure state machine behind the main gallery's press-and-hold `Before` comparison.
 *
 * The gallery has to distinguish "the user is holding the photo to see the original" from "the user is
 * panning the photo", and it must never trigger while a second finger is zooming. Keeping the rules
 * here — free of `Handler` and `MotionEvent` — means they are unit-testable:
 *
 * - a hold is *armed* on `DOWN` and fires after [holdDelayMs];
 * - moving further than `touchSlop` before the timer fires cancels it (that is a pan);
 * - a second pointer landing before the timer fires cancels it (that is a pinch);
 * - `UP`/`CANCEL` always releases an already-fired hold.
 */
internal class HoldGestureState(private val touchSlopPx: Float) {

    private var downX: Float = 0.0f
    private var downY: Float = 0.0f

    /** True between `DOWN` and the hold timer or a cancellation. */
    var armed: Boolean = false
        private set

    /** True while the original is being shown. */
    var holding: Boolean = false
        private set

    /**
     * Records a `DOWN` and arms the hold.
     *
     * @return True when the caller must now schedule the hold timer.
     */
    fun onDown(x: Float, y: Float): Boolean {
        downX = x
        downY = y
        armed = true
        holding = false
        return true
    }

    /**
     * Records movement.
     *
     * @return True when the movement exceeded the slop and the armed hold must be cancelled.
     */
    fun onMove(x: Float, y: Float): Boolean {
        if (!armed || !exceedsSlop(x, y)) return false
        armed = false
        return true
    }

    /**
     * Records a second pointer landing.
     *
     * @return True when an armed hold must be cancelled.
     */
    fun onPointerDown(): Boolean {
        if (!armed) return false
        armed = false
        return true
    }

    /**
     * Called when the hold timer elapses.
     *
     * @return True when the hold actually starts, i.e. the gesture was still armed.
     */
    fun onHoldTimerElapsed(): Boolean {
        if (!armed) return false
        armed = false
        holding = true
        return true
    }

    /**
     * Records `UP`/`CANCEL`.
     *
     * @return True when an active hold must be reported as ended.
     */
    fun onUpOrCancel(): Boolean {
        armed = false
        if (!holding) return false
        holding = false
        return true
    }

    private fun exceedsSlop(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > touchSlopPx * touchSlopPx
    }
}

/**
 * Adapts [HoldGestureState] to the Android main looper.
 *
 * The state machine decides; this class only owns the timer and the callbacks, so the decisions can be
 * tested without a runtime.
 */
internal class PressAndHoldDetector(
    touchSlopPx: Float,
    private val holdDelayMs: Long = HOLD_DELAY_MS,
    private val onHoldStarted: () -> Unit,
    private val onHoldEnded: () -> Unit
) {

    private val state = HoldGestureState(touchSlopPx)
    private val handler = Handler(Looper.getMainLooper())

    private val holdRunnable = Runnable {
        if (state.onHoldTimerElapsed()) onHoldStarted()
    }

    /** True while the original is being shown by this detector. */
    val isHolding: Boolean
        get() = state.holding

    /**
     * Feeds one gesture event and reports whether this detector owns the gesture.
     *
     * @return True while a hold is active, so the caller can suppress a tap or a pan.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.onDown(event.x, event.y)
                handler.postDelayed(holdRunnable, holdDelayMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelIfNeeded(state.onPointerDown())
            MotionEvent.ACTION_MOVE -> cancelIfNeeded(state.onMove(event.x, event.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            else -> Unit
        }
        return state.holding
    }

    /** Drops every pending callback and ends an active hold. */
    fun release() {
        handler.removeCallbacks(holdRunnable)
        if (state.onUpOrCancel()) onHoldEnded()
    }

    private fun cancelIfNeeded(cancelled: Boolean) {
        if (cancelled) handler.removeCallbacks(holdRunnable)
    }

    companion object {
        /** Hold duration after which the original is shown (`> 250ms` per the spec). */
        const val HOLD_DELAY_MS = 260L
    }
}

/**
 * True when the photo at [path] has an edited preview to compare against.
 *
 * Comparing is only meaningful for a photo the darkroom has actually graded: an unedited frame would
 * show itself, and a RAW without a preset has no preview cache at all.
 */
fun canComparePhoto(
    path: String,
    hasEditPreview: Boolean,
    previewOverridePath: String?
): Boolean = hasEditPreview && previewOverridePath != null && previewOverridePath != path

/**
 * Wraps the pager's page container so a press-and-hold can be observed before the child views claim
 * the touch.
 *
 * Only `onInterceptTouchEvent` is overridden: it runs before the child dispatch on every event of a
 * gesture, while `onTouchEvent` would not be reached at all once `PhotoView` consumes the drag for its
 * own matrix. Not overriding `onTouchEvent` also keeps the view free of click handling, so it needs no
 * `performClick`.
 */
internal class CompareAwareFrameLayout(context: Context) : android.widget.FrameLayout(context) {

    /** The detector currently observing touches, or `null` when comparing is unavailable. */
    var detector: PressAndHoldDetector? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        detector?.onTouchEvent(event)
        return false
    }
}
