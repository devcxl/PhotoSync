package cn.devcxl.photosync.editor.vision

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import ai.onnxruntime.OrtException
import kotlin.concurrent.withLock
import timber.log.Timber

/**
 * Why [OrtSessionManager] returned a recoverable failure.
 *
 * Every reason is retryable: the manager has already closed the broken session and the caller can
 * show a hint and let the user try again.
 */
enum class OrtSessionFailureReason {

    /** The model file is missing or unreadable; download/import it first. */
    MODEL_MISSING,

    /** The native runtime or the model could not be loaded because memory ran out. */
    OUT_OF_MEMORY,

    /** ONNX Runtime rejected the model or the inference threw [OrtException]. */
    RUNTIME_ERROR
}

/**
 * Recoverable failure raised by [OrtSessionManager].
 *
 * @property reason Machine-readable cause the UI maps to a user-facing hint.
 */
class OrtSessionUnavailableException(
    val reason: OrtSessionFailureReason,
    cause: Throwable? = null
) : Exception("ORT session unavailable: $reason", cause)

/**
 * Runtime policy of the ORT session owner, re-read before every inference.
 *
 * The settings page may change any of these while the process is alive; reading them per call is what
 * lets a thread-count or idle-policy change apply to the next AI action without restarting the app.
 *
 * @property tier Effective memory tier; [DeviceMemoryTier.LOW] always releases after inference.
 * @property intraOpThreads ORT/XNNPACK intra-op thread count, clamped to
 *   [MIN_INTRA_OP_THREADS]..[MAX_INTRA_OP_THREADS].
 * @property idleTimeoutMs Idle lifetime of a warm session, or `null` to release right after inference.
 */
data class OrtSessionRuntimeConfig(
    val tier: DeviceMemoryTier = DeviceMemoryTier.STANDARD,
    val intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS,
    val idleTimeoutMs: Long? = STANDARD_IDLE_TIMEOUT_MS
) {

    /** True when the session must be closed as soon as [OrtSessionManager.withSession] returns. */
    val releasesImmediately: Boolean
        get() = tier == DeviceMemoryTier.LOW || idleTimeoutMs == null

    companion object {

        const val MIN_INTRA_OP_THREADS = 1
        const val MAX_INTRA_OP_THREADS = 4

        /** Default thread count that keeps one inference from starving the UI thread. */
        const val DEFAULT_INTRA_OP_THREADS = 2

        /** Idle lifetime of a warm session on [DeviceMemoryTier.STANDARD] devices. */
        const val STANDARD_IDLE_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * One loaded native inference session.
 *
 * The interface deliberately hides `ai.onnxruntime.OrtSession` so the lifecycle manager is
 * JVM-testable; [OnnxRuntimeSessionHandle] exposes the native session to the engines that run
 * inference.
 */
interface OrtSessionHandle : AutoCloseable {

    /** Absolute path of the model file this session was created from. */
    val modelPath: String
}

/** Creates one native session; the seam that keeps [OrtSessionManager] unit-testable. */
fun interface OrtSessionFactory {

    /**
     * Loads [modelFile] into an inference session.
     *
     * @param intraOpThreads Thread count the session must use; callers pass the value read from
     *   [OrtSessionRuntimeConfig] so a settings change can rebuild the session.
     * @throws OrtException when the runtime rejects the model.
     * @throws OutOfMemoryError when the native allocation cannot be satisfied.
     */
    fun create(modelFile: File, intraOpThreads: Int): OrtSessionHandle
}

/** Runs a delayed release task; injectable so tests can advance the idle timeout deterministically. */
interface IdleReleaseScheduler : AutoCloseable {

    /** Replaces any pending task with [task], which runs after [delayMillis]. */
    fun schedule(delayMillis: Long, task: Runnable)

    /** Drops the pending task, if any. */
    fun cancel()

    override fun close() {}
}

/**
 * [IdleReleaseScheduler] backed by one daemon thread.
 *
 * The thread is created on demand by [Executors.newSingleThreadScheduledExecutor], so constructing
 * the manager (including from `App`) never starts work or touches ONNX Runtime.
 */
class ExecutorIdleReleaseScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ort-idle-release").apply { isDaemon = true }
    }
) : IdleReleaseScheduler {

    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    override fun schedule(delayMillis: Long, task: Runnable) {
        pending?.cancel(false)
        pending = executor.schedule(task, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun cancel() {
        pending?.cancel(false)
        pending = null
    }

    @Synchronized
    override fun close() {
        pending?.cancel(false)
        pending = null
        executor.shutdownNow()
    }
}

/**
 * Process-wide owner of at most one native ORT session.
 *
 * ## Contract
 * - **No warm-up**: constructing the manager loads nothing; a session is created only when
 *   [withSession] is called by the engine that needs it. `App.onCreate`, the gallery and the preset
 *   strip must never call [withSession].
 * - **Single flight**: one `ReentrantLock` serializes creation and inference, so at most one
 *   native session is resident and two AI tools can never hold sessions at the same time.
 * - **Switch model = close first**: asking for a different model file closes the previous session
 *   before the new one is created.
 * - **Settings-aware rebuild**: the thread count is re-read through [runtimePolicy] before every
 *   call; when it changed since the resident session was created, that session is closed and
 *   rebuilt with the new thread count.
 * - **Tiered release**: [OrtSessionRuntimeConfig.releasesImmediately] (a low tier or `immediate`)
 *   closes the session as soon as [withSession] returns; otherwise it stays warm for the configured
 *   idle timeout. [onMemoryPressure] closes immediately in every policy.
 * - **Recoverable failure**: `OutOfMemoryError` and [OrtException] are caught, the session is
 *   closed, and the caller receives a failed [Result] carrying [OrtSessionUnavailableException].
 *
 * The manager uses CPU/XNNPACK only. NNAPI and QNN are deliberately not enabled: NNAPI is
 * deprecated from Android 15 and QNN's license is not open source. Sessions are created without
 * ORT's cross-run caches (see [OrtSessionCachePolicy]); the manager still keeps a warm session when
 * the policy allows it, but the native buffers behind it are not pre-reserved.
 */
class OrtSessionManager(
    private val tier: DeviceMemoryTier,
    private val sessionFactory: OrtSessionFactory = OnnxRuntimeSessionFactory(),
    private val idleTimeoutMs: Long = OrtSessionRuntimeConfig.STANDARD_IDLE_TIMEOUT_MS,
    private val scheduler: IdleReleaseScheduler = ExecutorIdleReleaseScheduler(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val runtimePolicy: () -> OrtSessionRuntimeConfig = {
        OrtSessionRuntimeConfig(
            tier = tier,
            intraOpThreads = OrtSessionRuntimeConfig.DEFAULT_INTRA_OP_THREADS,
            idleTimeoutMs = idleTimeoutMs
        )
    }
) {

    private val lock = ReentrantLock()

    private var activeSession: ActiveSession? = null
    private var lastUsedAtMillis: Long = 0L

    /** Number of sessions currently resident; always `0` or `1`. */
    val activeSessionCount: Int
        get() = lock.withLock { if (activeSession == null) 0 else 1 }

    /**
     * Runs [block] against a session for [modelFile], creating or reusing it as the policy allows.
     *
     * @param modelFile Verified `.onnx` file; a missing file fails without touching the runtime.
     * @param block Inference body. It runs while the manager lock is held, which is what serializes
     *   concurrent calls; a session cannot be closed underneath it by the idle timer.
     * @return The block value, or a failure carrying [OrtSessionUnavailableException].
     */
    fun <T> withSession(modelFile: File, block: (OrtSessionHandle) -> T): Result<T> {
        if (!modelFile.isFile) {
            return Result.failure(
                OrtSessionUnavailableException(OrtSessionFailureReason.MODEL_MISSING)
            )
        }
        val policy = runtimePolicy()
        val threads = policy.intraOpThreads.coerceIn(
            OrtSessionRuntimeConfig.MIN_INTRA_OP_THREADS,
            OrtSessionRuntimeConfig.MAX_INTRA_OP_THREADS
        )
        lock.lock()
        try {
            scheduler.cancel()
            val modelPath = modelFile.absolutePath
            val resident = activeSession
            if (resident?.modelPath != modelPath || resident.intraOpThreads != threads) {
                closeActiveSessionLocked()
                val handle = try {
                    sessionFactory.create(modelFile, threads)
                } catch (e: OutOfMemoryError) {
                    Timber.e(e, "Not enough memory to load the ORT session")
                    return Result.failure(
                        OrtSessionUnavailableException(OrtSessionFailureReason.OUT_OF_MEMORY, e)
                    )
                } catch (e: OrtException) {
                    Timber.e(e, "ONNX Runtime refused to load the model")
                    return Result.failure(
                        OrtSessionUnavailableException(OrtSessionFailureReason.RUNTIME_ERROR, e)
                    )
                }
                activeSession = ActiveSession(modelPath, threads, handle)
            }
            val session = activeSession
                ?: return Result.failure(
                    OrtSessionUnavailableException(OrtSessionFailureReason.RUNTIME_ERROR)
                )
            val value = block(session.handle)
            lastUsedAtMillis = clock()
            return Result.success(value)
        } catch (e: OutOfMemoryError) {
            closeActiveSessionLocked()
            Timber.e(e, "ORT inference ran out of memory; the session was released")
            return Result.failure(
                OrtSessionUnavailableException(OrtSessionFailureReason.OUT_OF_MEMORY, e)
            )
        } catch (e: OrtException) {
            closeActiveSessionLocked()
            Timber.e(e, "ORT inference failed; the session was released")
            return Result.failure(
                OrtSessionUnavailableException(OrtSessionFailureReason.RUNTIME_ERROR, e)
            )
        } finally {
            if (activeSession != null) {
                if (policy.releasesImmediately) {
                    closeActiveSessionLocked()
                } else {
                    scheduleIdleReleaseLocked(checkNotNull(policy.idleTimeoutMs))
                }
            }
            lock.unlock()
        }
    }

    /** Closes the resident session immediately, e.g. from `Application.onTrimMemory`. */
    fun onMemoryPressure() {
        lock.withLock {
            scheduler.cancel()
            closeActiveSessionLocked()
        }
    }

    /** Releases the session and the scheduler thread; the manager cannot be reused afterwards. */
    fun close() {
        onMemoryPressure()
        scheduler.close()
    }

    private fun closeActiveSessionLocked() {
        val session = activeSession ?: return
        activeSession = null
        try {
            session.handle.close()
        } catch (e: OrtException) {
            Timber.w(e, "Failed to close an ORT session")
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to close an ORT session")
        }
    }

    private fun scheduleIdleReleaseLocked(timeoutMs: Long) {
        scheduler.cancel()
        scheduler.schedule(timeoutMs) { releaseIfIdleAfterTimeout(timeoutMs) }
    }

    private fun releaseIfIdleAfterTimeout(timeoutMs: Long) {
        lock.withLock {
            if (activeSession == null) return
            val idleForMillis = (clock() - lastUsedAtMillis).coerceAtLeast(0L)
            if (idleForMillis >= timeoutMs) {
                Timber.d("Releasing the ORT session after %d ms idle", idleForMillis)
                closeActiveSessionLocked()
            } else {
                scheduler.schedule(timeoutMs - idleForMillis) {
                    releaseIfIdleAfterTimeout(timeoutMs)
                }
            }
        }
    }

    private class ActiveSession(
        val modelPath: String,
        val intraOpThreads: Int,
        val handle: OrtSessionHandle
    )

    companion object {

        /** Idle lifetime of a warm session on [DeviceMemoryTier.STANDARD] devices. */
        const val STANDARD_IDLE_TIMEOUT_MS: Long = OrtSessionRuntimeConfig.STANDARD_IDLE_TIMEOUT_MS
    }
}
