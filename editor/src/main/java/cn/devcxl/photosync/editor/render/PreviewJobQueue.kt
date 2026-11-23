package cn.devcxl.photosync.editor.render

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded FIFO of preview render jobs with a one-shot stop.
 *
 * The worker is the only consumer and the only thread that can block in [take]; producers never
 * block, because a full queue rejects the job instead of growing without bound. Once [stop] ran (or
 * [take] decided to shut the worker down) every later [offer] is rejected, so no job can be handed
 * to a dying thread — the dispatcher falls back to the CPU renderer immediately instead.
 *
 * Pure Kotlin: no Android, no GL, so the ordering guarantees are unit-testable.
 */
internal class PreviewJobQueue<T : Any>(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()
    private val queue = ArrayBlockingQueue<T>(capacity)

    @Volatile
    private var stopped: Boolean = false

    /** True once the queue refuses new jobs. */
    val isStopped: Boolean
        get() = stopped

    /**
     * Queues [job].
     *
     * @return False when the queue was stopped or already holds [DEFAULT_CAPACITY] jobs. The caller
     *   still owns the job in that case and must finish it itself.
     */
    fun offer(job: T): Boolean = synchronized(lock) {
        if (stopped) false else queue.offer(job)
    }

    /**
     * Removes [job] if it is still waiting.
     *
     * @return True when the job was removed and therefore never started.
     */
    fun remove(job: T): Boolean = synchronized(lock) { queue.remove(job) }

    /**
     * Blocks for the next job.
     *
     * @return The next job, or `null` when the queue is empty and stopped (the worker should exit).
     */
    fun take(timeoutMs: Long): T? {
        queue.poll()?.let { return it }
        val awaited = try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        awaited?.let { return it }
        synchronized(lock) {
            queue.poll()?.let { return it }
            stopped = true
            return null
        }
    }

    /** Marks the queue stopped and returns the jobs that were still waiting. */
    fun stop(): List<T> = synchronized(lock) {
        stopped = true
        val drained = ArrayList<T>(queue.size)
        queue.drainTo(drained)
        drained
    }

    companion object {
        /** Requests that may wait behind the running render. */
        const val DEFAULT_CAPACITY = 2
    }
}

/**
 * One queued render plus the single point where its source is released.
 *
 * The job owns a **strong reference** to the source it renders (through [releaseSource]) for as long
 * as it may be executed. That is what makes a timeout or a coroutine cancellation safe: the caller
 * returns and drops its own reference, while the worker keeps the pixels alive until [complete] runs
 * — the worker can never sample a recycled bitmap.
 *
 * [complete] is idempotent and releases the source exactly once, whether the job produced a file,
 * failed, was cancelled before execution, or was rejected by a full queue.
 *
 * @param R Request payload; kept generic so the lifecycle is testable without an EGL context.
 * @property request Everything the render needs, including the pixels the job keeps alive.
 * @property releaseSource Releases the job's strong reference; must tolerate repeated calls.
 * @property onCompleted Delivers the result to the waiting caller; runs at most once.
 */
internal class PreviewJob<out R : Any>(
    val request: R,
    private val releaseSource: () -> Unit = {},
    private val onCompleted: (File?) -> Unit = {}
) {

    private val finished = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var failed: Boolean = false

    @Volatile
    var isCancelled: Boolean = false
        private set

    /** True once [complete] ran, whatever the result. */
    val isCompleted: Boolean
        get() = finished.get()

    /** True when the worker actually began rendering this job. */
    val hasStarted: Boolean
        get() = started.get()

    /** True when the worker attempted the render and could not produce a frame. */
    val isFailed: Boolean
        get() = failed

    /** Marks the job abandoned; the worker stops at its next checkpoint and writes nothing. */
    fun cancel() {
        isCancelled = true
    }

    /** Records that the worker is now rendering this job. */
    fun markStarted() {
        started.set(true)
    }

    /** Records that the render was attempted and produced nothing. */
    fun markFailed() {
        failed = true
    }

    /**
     * Publishes [file] and releases the source exactly once.
     *
     * A late call (a cancellation racing the render) is ignored, so the source can never be released
     * twice and the caller can never be resumed twice.
     */
    fun complete(file: File?) {
        if (!finished.compareAndSet(false, true)) return
        try {
            onCompleted(file)
        } finally {
            releaseSource()
        }
    }
}

/**
 * Cooperative abort probe for one offscreen render.
 *
 * A render must stop without publishing when the caller went away ([isCancelled]) **or** when the
 * headless context stopped being current on the render thread (a `GLSurfaceView` terminating the
 * shared display). Both conditions are checked between passes; the single method keeps the chain and
 * the renderer decoupled.
 */
internal fun interface PreviewRenderGuard {

    /** Returns false when the render must abandon without writing a file. */
    fun isStillValid(): Boolean

    companion object {
        /** Guard for renders that cannot be cancelled, e.g. the synchronous editor path. */
        val NONE: PreviewRenderGuard = PreviewRenderGuard { true }
    }
}
