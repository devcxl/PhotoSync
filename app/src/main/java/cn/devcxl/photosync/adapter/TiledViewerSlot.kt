package cn.devcxl.photosync.adapter

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.util.concurrent.Executor

/**
 * Fails fast when a tiled-viewer lifecycle call runs off the main thread.
 *
 * `SubsamplingScaleImageView` and the holder's view state are main-thread only. Detaching or
 * recycling from a worker thread would corrupt view state instead of failing loudly, so the
 * Android-facing host and the holder entry points check this before touching anything.
 */
internal fun requireMainThreadForViewer() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "Photo viewer lifecycle must run on the main thread"
    }
}

/**
 * Owns the tiled JPEG viewer of one pager holder and keeps stale decode work away from it.
 *
 * SubsamplingScaleImageView 3.10.0 applies every decode result from a main-thread `AsyncTask`
 * callback. Calling `setImage` again or `recycle` while such a task is still in flight lets the
 * late callback mutate a view that may already belong to a different photo. The slot therefore
 * never sets an image on, and never recycles, a view that has tracked work:
 *
 * - [acquireForBind] returns the attached view while it has no tracked work, so ordinary binds
 *   keep reusing a single view exactly as before.
 * - A busy view is detached and handed to its own [SsivTaskTracker]. It is recycled only after
 *   every tracked main-thread callback has run, and a fresh view (with a fresh tracker) takes its
 *   place.
 * - [release] retires the attached view without attaching a replacement.
 *
 * Each tracker belongs to exactly one view instance, and only a replaced or released view is
 * pending drain, so a drain callback can never recycle a view that is still in use. A retired
 * tracker still accepts work until it drains: SSIV derives follow-up tile tasks from
 * `onTilesInited` inside the very callbacks being awaited, so rejecting that work would crash the
 * main thread.
 *
 * If a tracker cannot enqueue its main-thread sentinel it never drains. The slot then cannot know
 * when the detached view is safe to recycle and deliberately leaves it alone: nothing guarantees
 * that view stays reachable, and the failure is reported only to [onTaskPostFailure] for logging.
 * Recycling it there would be worse, because that failure runs on a worker thread.
 *
 * All lifecycle calls and the drain callback run on the main thread. Queueing and backpressure
 * stay with the injected [workerExecutor]; the slot itself buffers nothing.
 */
internal class TiledViewerSlot<T : Any>(
    private val host: Host<T>,
    private val workerExecutor: Executor,
    private val postToMain: (Runnable) -> Boolean,
    private val onTaskPostFailure: (Throwable) -> Unit = {}
) {

    /** View-side operations the slot needs; abstract so the lifecycle stays unit-testable. */
    internal interface Host<T : Any> {
        /** Creates a detached view whose decode work is submitted through [executor]. */
        fun createView(executor: Executor): T

        fun attachView(view: T)

        /** Removes [view] from its parent and detaches every listener it holds. */
        fun detachView(view: T)

        fun recycleView(view: T)
    }

    /** A view a bind may use, and whether it was created for this bind. */
    internal data class Target<T : Any>(val view: T, val isFresh: Boolean)

    private class Entry<T : Any>(val view: T, val tracker: SsivTaskTracker)

    private var entry: Entry<T>? = null

    /** The attached view, or `null` while none is needed or one is being replaced. */
    val currentView: T?
        get() = entry?.view

    /** Whether the attached view still has decode work that will touch it on the main thread. */
    val hasInFlightTasks: Boolean
        get() = (entry?.tracker?.outstandingTaskCount ?: 0) > 0

    /**
     * Returns the view the next bind may use.
     *
     * @param needsNewImage whether the caller is about to call `setImage`. A view with tracked
     *   work is replaced instead of mutated only in that case; without new image content the
     *   in-flight task is already producing the result the caller wants.
     */
    fun acquireForBind(needsNewImage: Boolean): Target<T> {
        val current = entry
        if (current != null && (!needsNewImage || current.tracker.outstandingTaskCount == 0)) {
            return Target(current.view, isFresh = false)
        }
        if (current != null) {
            retire(current)
        }
        val created = createEntry()
        return Target(created.view, isFresh = true)
    }

    /** Drops the attached view: recycled now when idle, otherwise once its callbacks drained. */
    fun release() {
        entry?.let(::retire)
    }

    private fun createEntry(): Entry<T> {
        var view: T? = null
        // The view is assigned as soon as it exists. A drain can only run after a tracked task's
        // main-thread sentinel, and Host.createView must not start a decode (see
        // SubsamplingTiledHost), so the callback never sees a null or half-attached view.
        val tracker = SsivTaskTracker(
            backgroundExecutor = workerExecutor,
            postToMain = postToMain,
            onDrained = { view?.let(::recycleDetached) },
            onPostFailure = onTaskPostFailure
        )
        val createdView = host.createView(tracker)
        view = createdView
        host.attachView(createdView)
        return Entry(createdView, tracker).also { entry = it }
    }

    private fun retire(current: Entry<T>) {
        if (entry === current) {
            entry = null
        }
        host.detachView(current.view)
        current.tracker.retire()
    }

    private fun recycleDetached(view: T) {
        if (entry?.view === view) return
        host.recycleView(view)
    }
}

/**
 * [TiledViewerSlot.Host] for the pager's tiled JPEG viewer.
 *
 * Creation mirrors the previously inline configuration so a replacement view looks and behaves
 * exactly like the original: matching layout params, hidden until its bind makes it visible, and
 * the tracked executor installed before any image can be set.
 */
internal class SubsamplingTiledHost(
    private val container: FrameLayout,
    /** Extra setup for every created view (test decoder factories). Must not start a decode. */
    private val configure: (SubsamplingScaleImageView) -> Unit = {}
) : TiledViewerSlot.Host<SubsamplingScaleImageView> {

    override fun createView(executor: Executor): SubsamplingScaleImageView {
        requireMainThreadForViewer()
        return SubsamplingScaleImageView(container.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setExecutor(executor)
            configure(this)
        }
    }

    override fun attachView(view: SubsamplingScaleImageView) {
        requireMainThreadForViewer()
        // Below the loading pill (last child) and above the PhotoView, matching the old z-order.
        container.addView(view, minOf(1, container.childCount))
    }

    override fun detachView(view: SubsamplingScaleImageView) {
        requireMainThreadForViewer()
        container.removeView(view)
        // Late decode callbacks must not reach the holder that already shows another photo.
        view.setOnImageEventListener(null)
        view.setOnStateChangedListener(null)
        view.setOnClickListener(null)
    }

    override fun recycleView(view: SubsamplingScaleImageView) {
        requireMainThreadForViewer()
        view.recycle()
    }
}
