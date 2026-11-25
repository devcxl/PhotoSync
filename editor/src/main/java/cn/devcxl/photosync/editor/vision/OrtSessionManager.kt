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
     * @throws OrtException when the runtime rejects the model.
     * @throws OutOfMemoryError when the native allocation cannot be satisfied.
     */
    fun create(modelFile: File): OrtSessionHandle
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
 * - **Tiered release**: [DeviceMemoryTier.LOW] closes the session as soon as [withSession] returns;
 *   [DeviceMemoryTier.STANDARD] keeps it for [idleTimeoutMs] and then closes it, and
 *   [onMemoryPressure] closes immediately on both tiers.
 * - **Recoverable failure**: `OutOfMemoryError` and [OrtException] are caught, the session is
 *   closed, and the caller receives a failed [Result] carrying [OrtSessionUnavailableException].
 *
 * The manager uses CPU/XNNPACK only. NNAPI and QNN are deliberately not enabled: NNAPI is
 * deprecated from Android 15 and QNN's license is not open source.
 */
class OrtSessionManager(
    private val tier: DeviceMemoryTier,
    private val sessionFactory: OrtSessionFactory = OnnxRuntimeSessionFactory(),
    private val idleTimeoutMs: Long = STANDARD_IDLE_TIMEOUT_MS,
    private val scheduler: IdleReleaseScheduler = ExecutorIdleReleaseScheduler(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = ReentrantLock()

    private var activeSession: ActiveSession? = null
    private var lastUsedAtMillis: Long = 0L

    /** Number of sessions currently resident; always `0` or `1`. */
    val activeSessionCount: Int
        get() = lock.withLock { if (activeSession == null) 0 else 1 }

    /**
     * Runs [block] against a session for [modelFile], creating or reusing it as the tier allows.
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
        lock.lock()
        try {
            scheduler.cancel()
            val modelPath = modelFile.absolutePath
            if (activeSession?.modelPath != modelPath) {
                closeActiveSessionLocked()
                val handle = try {
                    sessionFactory.create(modelFile)
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
                activeSession = ActiveSession(modelPath, handle)
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
                if (tier == DeviceMemoryTier.LOW) {
                    closeActiveSessionLocked()
                } else {
                    scheduleIdleReleaseLocked()
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

    private fun scheduleIdleReleaseLocked() {
        scheduler.cancel()
        scheduler.schedule(idleTimeoutMs) { releaseIfIdleAfterTimeout() }
    }

    private fun releaseIfIdleAfterTimeout() {
        lock.withLock {
            if (activeSession == null) return
            val idleForMillis = (clock() - lastUsedAtMillis).coerceAtLeast(0L)
            if (idleForMillis >= idleTimeoutMs) {
                Timber.d("Releasing the ORT session after %d ms idle", idleForMillis)
                closeActiveSessionLocked()
            } else {
                scheduler.schedule(idleTimeoutMs - idleForMillis) { releaseIfIdleAfterTimeout() }
            }
        }
    }

    private class ActiveSession(
        val modelPath: String,
        val handle: OrtSessionHandle
    )

    companion object {

        /** Idle lifetime of a warm session on [DeviceMemoryTier.STANDARD] devices. */
        const val STANDARD_IDLE_TIMEOUT_MS: Long = 30_000L
    }
}
