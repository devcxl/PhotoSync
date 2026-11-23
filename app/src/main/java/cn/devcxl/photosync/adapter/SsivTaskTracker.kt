package cn.devcxl.photosync.adapter

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution gate that tracks in-flight SubsamplingScaleImageView (SSIV) decode tasks until their
 * `AsyncTask` result messages have been delivered on the main thread.
 *
 * ## Why this exists
 *
 * SSIV starts an `AsyncTask` per image and applies its result from a main-thread result message.
 * Recycling or rebinding the view while such a task is still in flight lets that stale result
 * message mutate the view afterwards. This tracker wraps each task:
 *
 * 1. [execute] counts the task synchronously, then hands exactly one wrapper to
 *    [backgroundExecutor]. Nothing is buffered here: the injected executor owns queueing and
 *    backpressure, so no unbounded internal queue is created.
 * 2. The worker runs the original task and only afterwards posts a plain main-thread sentinel via
 *    [postToMain]. The task's own `AsyncTask` result message is posted by the same worker before
 *    the task call returns, so it is already ahead of the sentinel in the main looper queue.
 * 3. The sentinel decrements the count. Once a retired tracker reaches zero, [onDrained] runs
 *    exactly once so the owner can recycle resources while SSIV is known to be quiescent.
 *
 * A successfully posted sentinel strongly references this tracker, so a retired tracker with
 * pending sentinels stays reachable until the drain callback has run. When posting fails there is
 * no sentinel and therefore no GC root: the tracker only records the failure, keeps the count
 * unchanged, and never mis-sequences cleanup on the worker. [onDrained] itself must keep alive
 * whatever it releases.
 *
 * ## Contract
 *
 * - Create, [execute] and [retire] on the main thread. Counting and retirement are still
 *   thread-safe, so re-entrant submissions are counted correctly, but the lifecycle calls are
 *   expected to be main-thread serialized.
 * - [backgroundExecutor] must either accept the wrapper exactly once and run it, or reject it
 *   without running it. Rejections are propagated to the caller after the synchronous count is
 *   rolled back.
 * - [postToMain] must enqueue the sentinel onto the main looper and return `true` (for example
 *   `handler::post`). Returning `false` or throwing means the sentinel could not be enqueued; the
 *   failure is recorded in [postFailure], reported to [onPostFailure], the task stays counted, and
 *   the tracker never drains. It must never enqueue a sentinel and then report failure.
 * - [onPostFailure] runs on the worker that failed to post, so the owner can log or surface the
 *   failure (for example with `Timber.e`). An exception thrown by the observer is attached as
 *   suppressed to the post failure and never masks the original task error. The tracker itself has
 *   no Android or Timber dependency.
 * - [retire] never skips or cancels queued work: queued tasks still run so `AsyncTask.onCancelled`
 *   semantics are not violated. While the tracker is retired but not yet drained, [execute] keeps
 *   accepting tasks because SSIV can derive follow-up tile tasks from callbacks such as
 *   `onTilesInited` (for example when `maxTileSize` is set) before the queued sentinels drain.
 *   Only a drained tracker rejects new work.
 * - A task failure is rethrown on the worker after the sentinel is posted, so it stays visible
 *   instead of being swallowed. If the sentinel post also failed with a distinct `Throwable`, that
 *   failure is recorded and attached as suppressed to the task failure; the same instance is never
 *   self-suppressed.
 *
 * @param backgroundExecutor executor that owns queueing and backpressure for wrapped tasks.
 * @param postToMain main-looper poster; returns whether the sentinel was enqueued.
 * @param onPostFailure observer for sentinel delivery failures; runs on the failing worker and may
 *   throw, in which case the observer failure is contained.
 * @param onDrained one-shot cleanup once a retired tracker is quiescent and healthy.
 */
internal class SsivTaskTracker(
    private val backgroundExecutor: Executor,
    private val postToMain: (Runnable) -> Boolean,
    private val onPostFailure: (Throwable) -> Unit = {},
    private val onDrained: () -> Unit
) : Executor {

    private val lock = Any()
    private var outstandingTasks = 0
    private var retired = false
    private var drainDelivered = false
    private var mainPostFailure: Throwable? = null

    /** Number of submitted tasks whose main-thread sentinel has not settled yet. */
    val outstandingTaskCount: Int
        get() = synchronized(lock) { outstandingTasks }

    /** Whether [retire] has been called. Retirement is one-way and idempotent. */
    val isRetired: Boolean
        get() = synchronized(lock) { retired }

    /** Whether [onDrained] has already been delivered. A drained tracker rejects new tasks. */
    val isDrained: Boolean
        get() = synchronized(lock) { drainDelivered }

    /**
     * First failure observed while delivering a main-thread sentinel, or `null` while posting works.
     *
     * When set, the tracker can no longer prove that SSIV's result messages have been handled on
     * the main thread. It keeps every outstanding task counted, never zeroes the count from the
     * worker and never calls [onDrained]. This path has no posted sentinel, so it creates no GC
     * root: retention of the tracker and the resources behind [onDrained] depends on the owner's
     * references, not on this tracker.
     */
    val postFailure: Throwable?
        get() = synchronized(lock) { mainPostFailure }

    /**
     * Submits [command] to [backgroundExecutor] after counting it synchronously.
     *
     * The wrapper runs [command] first and posts the sentinel afterwards, preserving the
     * `AsyncTask` result-message ordering described in the class documentation.
     *
     * While the tracker is [isRetired] but not yet [isDrained], submissions are still accepted and
     * counted: SSIV can derive follow-up tile tasks from callbacks that run before the queued
     * sentinels drain. Only a drained tracker rejects.
     *
     * @throws RejectedExecutionException if this tracker already drained or the background
     *   executor rejects the wrapper. In both cases no task remains counted.
     */
    override fun execute(command: Runnable) {
        synchronized(lock) {
            if (drainDelivered) {
                throw RejectedExecutionException("SsivTaskTracker has already drained")
            }
            outstandingTasks++
        }

        val started = AtomicBoolean(false)
        val wrapper = Runnable { runTracked(command, started) }
        try {
            backgroundExecutor.execute(wrapper)
        } catch (throwable: Throwable) {
            // An inline/synchronous executor can run the wrapper (and fail) before returning.
            // Only a wrapper that never started is a submission failure that needs a rollback;
            // rolling back a wrapper that already settled would corrupt the count.
            if (!started.get()) {
                rollbackSubmission()
            }
            throw throwable
        }
    }

    /**
     * Retires the tracker. In-flight, queued and derived tasks keep running and counting;
     * [onDrained] is delivered once, on the thread that settles the last counted task, as soon as
     * no tracked task remains and the sentinel path is healthy.
     */
    fun retire() {
        var deliverDrain = false
        synchronized(lock) {
            if (!retired) {
                retired = true
                deliverDrain = markDrainedIfQuiescentLocked()
            }
        }
        if (deliverDrain) {
            onDrained()
        }
    }

    private fun runTracked(command: Runnable, started: AtomicBoolean) {
        started.set(true)
        var taskFailure: Throwable? = null
        try {
            command.run()
        } catch (throwable: Throwable) {
            taskFailure = throwable
        }

        val sentinel = Runnable { settle() }
        val postFailure: Throwable? = try {
            if (postToMain(sentinel)) {
                null
            } else {
                IllegalStateException("Main-thread sentinel was not enqueued")
            }
        } catch (throwable: Throwable) {
            throwable
        }

        if (postFailure != null) {
            reportPostFailure(postFailure)
            // Never settle here. Without a sentinel the tracker cannot know when SSIV's result
            // message has been handled, and draining now would run reclaim on the worker thread
            // and out of order. Keeping the task counted is the conservative behavior.
            val failure = taskFailure
            if (failure == null) {
                return
            }
            if (failure !== postFailure) {
                failure.addSuppressed(postFailure)
            }
            throw failure
        }

        taskFailure?.let { throw it }
    }

    private fun reportPostFailure(failure: Throwable) {
        recordPostFailure(failure)
        try {
            onPostFailure(failure)
        } catch (observerFailure: Throwable) {
            // An observer must never replace or mask the original failure chain.
            if (observerFailure !== failure) {
                failure.addSuppressed(observerFailure)
            }
        }
    }

    private fun recordPostFailure(failure: Throwable) {
        synchronized(lock) {
            val existing = mainPostFailure
            if (existing == null) {
                mainPostFailure = failure
            } else if (existing !== failure) {
                existing.addSuppressed(failure)
            }
        }
    }

    private fun rollbackSubmission() {
        var deliverDrain = false
        synchronized(lock) {
            outstandingTasks--
            deliverDrain = markDrainedIfQuiescentLocked()
        }
        if (deliverDrain) {
            onDrained()
        }
    }

    private fun settle() {
        var deliverDrain = false
        synchronized(lock) {
            check(outstandingTasks > 0) {
                "SsivTaskTracker settled more tasks than it submitted"
            }
            outstandingTasks--
            deliverDrain = markDrainedIfQuiescentLocked()
        }
        if (deliverDrain) {
            onDrained()
        }
    }

    private fun markDrainedIfQuiescentLocked(): Boolean {
        if (retired && outstandingTasks == 0 && mainPostFailure == null && !drainDelivered) {
            drainDelivered = true
            return true
        }
        return false
    }
}
