package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.export.SurfacelessEglContext

/**
 * Everything one offscreen preview render needs.
 *
 * The source pixels are only valid while the owner of [source] keeps them alive; the queued API
 * ([OffscreenPreviewRenderer.render]) therefore takes an `EditorSourceLease` and keeps it for the
 * whole job, while the synchronous API ([OffscreenPreviewRenderer.renderNow]) runs on the caller's
 * thread and returns only after it stopped sampling the source.
 *
 * @property source Decoded source pixels.
 * @property graph Edit graph the preview must reproduce.
 * @property warpField Composed `uWarpTex` grid (face MLS + body + liquify), or `null` for none.
 * @property skinMask `256 x 256` portrait mask, or `null` when the portrait group is unused.
 * @property lut Imported 3D LUT samples referenced by [graph], or `null` when none.
 * @property outDir Directory that receives one new, uniquely named JPEG.
 */
internal data class OffscreenPreviewRequest(
    val source: EditorSource,
    val graph: EditGraph,
    val warpField: WarpDisplacementField? = null,
    val skinMask: FloatArray? = null,
    val lut: Lut3dSamples? = null,
    val outDir: File,
    /** Source-space subject composite of `graph.subject`, or `null` when the stage is inactive. */
    val subject: SubjectComposite? = null
)

/**
 * GPU-accelerated preview cache renderer with CPU fallback semantics.
 *
 * ## Two entry points
 * - [renderNow] runs on the calling thread inside its own headless context. It is used by the editor
 *   save path, where blocking until the frame is written is the existing contract and where the
 *   session owns the source.
 * - [render] hands the work to a dedicated serial worker thread and **takes ownership of the caller's
 *   source lease**. The job keeps the pixels alive until it is truly finished, so a timeout or a
 *   coroutine cancellation can return to the caller (which then closes its own resources) without the
 *   worker ever touching recycled pixels.
 *
 * ## Lifetime
 * The EGL context exists for exactly one render: a lost context, a driver reset, a `GLSurfaceView`
 * terminating the shared display or a surface loss can never leave stale GL objects behind, and every
 * doubt is answered with `null` (CPU fallback) instead of a partially corrected frame.
 *
 * ## Backpressure
 * At most [PreviewJobQueue.DEFAULT_CAPACITY] requests wait behind the running render; a request that
 * cannot be queued is rejected immediately. Only one worker thread can exist at a time — a new one is
 * created after the previous thread terminated, never next to it.
 *
 * ## Circuit breaker
 * Only a render that was *attempted* and failed (or that overran the timeout after it started) counts
 * as a GPU defect. Queue congestion, a rejected offer, a shutdown during memory pressure and a
 * cancelled caller are not capability signals and leave the breaker untouched.
 *
 * ## Known limitation (unverified on device)
 * A driver that never returns from a GL call cannot be interrupted from Java. That wedges the single
 * worker thread for the rest of the process — bounded to one thread, one context and the pixels of
 * the job it leased, never more — while callers time out and use the CPU renderer; three such
 * overruns trip the breaker and stop the GPU path. [renderNow] has no timeout at all: it blocks the
 * editor save until the driver returns, exactly like the CPU renderer it replaces.
 */
internal object OffscreenPreviewRenderer {

    /** How long an idle worker keeps its thread alive before it exits. */
    private const val IDLE_TIMEOUT_MS = 15_000L

    /** Upper bound on how long a caller waits for one render before using the CPU path. */
    private const val RENDER_TIMEOUT_MS = 20_000L

    private const val THREAD_NAME = "offscreen-preview"

    private val breaker = OffscreenPreviewBreaker()
    private val workerLock = Any()

    private var worker: PreviewWorker? = null

    /** True while the GPU path is still worth attempting. */
    val isEnabled: Boolean
        get() = breaker.isEnabled

    /**
     * Renders [request] on the calling thread with its own headless EGL context.
     *
     * Blocking; the caller keeps ownership of `request.source` and gets control back only after every
     * GL object of this render was released.
     *
     * @return The written file, or `null` when the graph must be rendered on the CPU instead.
     */
    fun renderNow(request: OffscreenPreviewRequest): File? {
        if (!breaker.isEnabled) return null
        var written: File? = null
        val chain = OffscreenPreviewChain()
        try {
            val context = SurfacelessEglContext.create()
            if (context == null) {
                Timber.i("Offscreen preview: no headless GL context; using the CPU renderer")
            } else {
                try {
                    if (chain.init(context.glMaxTextureSize)) {
                        written = chain.render(
                            request = request,
                            guard = PreviewRenderGuard { context.isCurrentOnThisThread() }
                        )
                    }
                } finally {
                    // Order matters: the chain deletes GL objects, which needs the context that
                    // created them to still be current on this thread.
                    chain.close()
                    context.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "Offscreen preview ran out of memory; using the CPU renderer")
        } catch (io: IOException) {
            // `PreviewFileWriter` can fail on a full or evicted cache directory; that is a preview
            // failure, never an uncaught exception on the editor's save thread.
            Timber.e(io, "Offscreen preview could not write its file; using the CPU renderer")
        } catch (error: RuntimeException) {
            Timber.e(error, "Offscreen preview failed; using the CPU renderer")
        }
        if (written != null) breaker.onSuccess() else breaker.onFailure()
        return written
    }

    /**
     * Renders [request] on the shared worker thread and takes ownership of [lease].
     *
     * The lease is released exactly once by the [PreviewJob] this function creates before it suspends:
     * either the worker finishes the job, the queue rejects it, or this function finishes it because
     * the request never reached the worker. That is what makes returning early (timeout, cancellation)
     * safe.
     *
     * @return The written file, or `null` on failure, cancellation, timeout or congestion.
     * @throws CancellationException when the calling coroutine is cancelled; the job is abandoned and
     *   the worker finishes it (or drops it) without publishing.
     */
    suspend fun render(request: OffscreenPreviewRequest, lease: EditorSourceLease): File? {
        if (!breaker.isEnabled) {
            lease.close()
            return null
        }
        val activeWorker = obtainWorker()
        if (activeWorker == null) {
            Timber.i("Offscreen preview: no GPU worker; using the CPU renderer")
            lease.close()
            return null
        }

        // The job exists before the first suspension, so ownership of the lease is decided exactly
        // once even when the coroutine is already cancelled and the block below never runs.
        val continuationRef = AtomicReference<CancellableContinuation<File?>?>(null)
        val offered = AtomicBoolean(false)
        // Keeps a file that was written in the instant the timeout fired from being dropped: the
        // deadline discards the value `withTimeoutOrNull` would have returned, and `settle` then owns
        // (and deletes) whatever never reached the caller.
        val resultHolder = PreviewResultHolder()
        val job = PreviewJob(
            request = request,
            releaseSource = { lease.close() },
            onCompleted = { file ->
                resultHolder.park(file)
                handOffPreviewFile(file, continuationRef.get()) { resultHolder.discardParked() }
            }
        )

        var timedOut = false
        val written = try {
            val result = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                dispatchPreviewJob(job, activeWorker, continuationRef) { offered.set(true) }
            }
            timedOut = result == null && !job.isCompleted
            result
        } catch (cancelled: CancellationException) {
            // The coroutine was cancelled before the dispatch block ran (or before the job was
            // handed over): the job still owns the lease.
            job.cancel()
            if (!offered.get()) job.complete(null)
            resultHolder.settle(null)
            throw cancelled
        } catch (error: RuntimeException) {
            Timber.e(error, "Offscreen preview could not be dispatched; using the CPU renderer")
            job.cancel()
            if (!offered.get()) job.complete(null)
            resultHolder.settle(null)
            null
        }

        val delivered = resultHolder.settle(written)
        recordOutcome(job, delivered, timedOut)
        return delivered
    }

    /** Stops the worker thread and completes queued requests with `null`. */
    fun shutdown() {
        releaseWorker()
        breaker.reset()
    }

    /**
     * Releases the worker thread on a low-memory signal without changing the GPU policy.
     *
     * No GL object outlives a single render (the EGL context is created and destroyed per preview),
     * so stopping the thread and completing queued requests with `null` is the complete response
     * here. Callers fall back to the CPU; the next preview starts a fresh worker lazily.
     */
    fun onMemoryPressure() {
        releaseWorker()
    }

    private fun releaseWorker() {
        // The reference is kept on purpose: `obtainWorker` must never start a second thread while the
        // old one is still inside a render.
        val current = synchronized(workerLock) { worker }
        current?.shutdown()
    }

    private fun recordOutcome(
        job: PreviewJob<OffscreenPreviewRequest>,
        written: File?,
        timedOut: Boolean
    ) {
        when {
            written != null -> breaker.onSuccess()
            // Started and overran the deadline: the driver is not completing previews in time.
            timedOut && job.hasStarted -> breaker.onFailure()
            // The worker attempted the render and produced nothing.
            job.isCompleted && job.isFailed -> breaker.onFailure()
            // Rejected offer, shutdown, queue congestion or a cancelled caller: not a GPU defect.
            else -> Unit
        }
    }

    private fun obtainWorker(): PreviewWorker? {
        synchronized(workerLock) {
            val existing = worker
            if (existing != null) {
                if (!existing.isTerminated) return existing
                worker = null
            }
            return try {
                PreviewWorker().also { worker = it }
            } catch (error: RuntimeException) {
                Timber.e(error, "Offscreen preview worker could not be started")
                null
            }
        }
    }

    /**
     * Serial worker that owns the EGL context of every render it runs.
     *
     * The thread exits after [IDLE_TIMEOUT_MS] without work, so a rare preview does not keep a thread
     * resident. While it is stopping it rejects new jobs instead of accepting work it may never run.
     */
    private class PreviewWorker : PreviewJobSink {

        private val queue = PreviewJobQueue<PreviewJob<OffscreenPreviewRequest>>()

        private val thread = Thread({ runLoop() }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }

        val isTerminated: Boolean
            get() = !thread.isAlive

        /** Queues [job], or returns false when the worker is stopping or the backlog is full. */
        override fun offer(job: PreviewJob<OffscreenPreviewRequest>): Boolean = queue.offer(job)

        /** Removes [job] when it never started. */
        override fun remove(job: PreviewJob<OffscreenPreviewRequest>): Boolean = queue.remove(job)

        /** Stops the thread and completes every queued job with `null`. */
        fun shutdown() {
            queue.stop().forEach { it.complete(null) }
            thread.interrupt()
        }

        private fun runLoop() {
            try {
                while (true) {
                    val job = queue.take(IDLE_TIMEOUT_MS) ?: break
                    execute(job)
                }
            } finally {
                queue.stop().forEach { it.complete(null) }
            }
        }

        private fun execute(job: PreviewJob<OffscreenPreviewRequest>) {
            if (job.isCancelled) {
                job.complete(null)
                return
            }
            job.markStarted()
            var written: File? = null
            val chain = OffscreenPreviewChain()
            try {
                val context = SurfacelessEglContext.create()
                if (context == null) {
                    Timber.i("Offscreen preview: no headless GL context; using the CPU renderer")
                } else {
                    try {
                        if (chain.init(context.glMaxTextureSize)) {
                            written = chain.render(
                                request = job.request,
                                guard = PreviewRenderGuard {
                                    !job.isCancelled && context.isCurrentOnThisThread()
                                }
                            )
                        }
                    } finally {
                        // Order matters: the chain deletes GL objects, which needs the context that
                        // created them to still be current on this thread.
                        chain.close()
                        context.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                // Nothing here can observe a coroutine cancellation; treat it as an abandoned render
                // so the caller falls back to the CPU instead of publishing a partial frame.
                Timber.w("Offscreen preview: render was abandoned")
            } catch (oom: OutOfMemoryError) {
                Timber.e(oom, "Offscreen preview ran out of memory; using the CPU renderer")
            } catch (io: IOException) {
                // An uncaught exception on this bare thread would kill the process.
                Timber.e(io, "Offscreen preview could not write its file; using the CPU renderer")
            } catch (error: RuntimeException) {
                Timber.e(error, "Offscreen preview failed; using the CPU renderer")
            } finally {
                // A cancelled job produced nothing on purpose; that is not a GPU capability failure.
                if (written == null && !job.isCancelled) job.markFailed()
                job.complete(written)
            }
        }
    }
}

/**
 * The two queue operations a dispatch needs.
 *
 * Narrowing the worker to this interface keeps the hand-over contract — reject, cancel, deliver — unit
 * testable without an EGL context or a worker thread.
 */
internal interface PreviewJobSink {

    /** Queues [job]; false means the caller still owns it. */
    fun offer(job: PreviewJob<OffscreenPreviewRequest>): Boolean

    /** Removes [job] when it is still waiting; true means it never started. */
    fun remove(job: PreviewJob<OffscreenPreviewRequest>): Boolean
}

/**
 * Hands [job] to [sink] and suspends until it completes.
 *
 * Ownership rules, in order:
 * 1. the cancellation handler is registered first, so a cancellation racing the hand-over either
 *    takes the job back (`remove` + `complete`) or marks it cancelled for the worker;
 * 2. an already cancelled waiter **or job** never occupies a queue slot — the job is finished here,
 *    which releases its source;
 * 3. a rejected offer finishes the job here, which releases its source **and** resumes the waiter
 *    with `null` exactly once (the completion callback owns the resume, never this function);
 * 4. an accepted offer leaves the job to the worker.
 *
 * @param continuationRef Receives the waiter so the completion callback can deliver into it.
 * @param onOffered Invoked after a successful hand-over; the dispatcher uses it to know that the job
 *   is no longer its to finish.
 * @return The delivered file, or `null` when the render produced nothing / was rejected.
 */
internal suspend fun dispatchPreviewJob(
    job: PreviewJob<OffscreenPreviewRequest>,
    sink: PreviewJobSink,
    continuationRef: AtomicReference<CancellableContinuation<File?>?>,
    onOffered: () -> Unit = {}
): File? = suspendCancellableCoroutine { continuation ->
    continuationRef.set(continuation)
    continuation.invokeOnCancellation {
        // Not started yet: take it back and finish it here. Already running: the worker aborts at its
        // next checkpoint and releases the source itself.
        if (sink.remove(job)) job.complete(null) else job.cancel()
    }
    if (!continuation.isActive || job.isCancelled) {
        // The waiter is gone, or the job was abandoned before the hand-over: never enqueue a dead job,
        // but still release its source exactly once.
        job.cancel()
        job.complete(null)
        return@suspendCancellableCoroutine
    }
    if (sink.offer(job)) {
        onOffered()
    } else {
        // Rejected: finishing the job releases the source and resumes this waiter with `null` through
        // the completion callback. Resuming here as well would be a double resume.
        job.complete(null)
    }
}

/**
 * Hands a finished render to the waiting caller.
 *
 * A file is published only when the caller can still receive it:
 * - no continuation yet (the dispatcher finished the job before it suspended) → delete;
 * - the coroutine is already cancelled → delete (a cancelled preview must not leave an orphan);
 * - cancelled between completion and resumption → `resume(value, onCancellation)` deletes it.
 *
 * A file that was actually delivered is never touched, so a caller that owns the path keeps it.
 *
 * @param file The written preview, or `null` when the render produced nothing.
 * @param continuation The waiter, or `null` when it no longer exists.
 * @param discard Removes a file that cannot be delivered; injectable for tests.
 */
internal fun handOffPreviewFile(
    file: File?,
    continuation: CancellableContinuation<File?>?,
    discard: (File) -> Unit = { it.delete() }
) {
    if (continuation == null) {
        file?.let(discard)
        return
    }
    if (!continuation.isActive) {
        file?.let(discard)
        return
    }
    if (file == null) {
        continuation.resume(null)
        return
    }
    continuation.resume(file) { discard(file) }
}
