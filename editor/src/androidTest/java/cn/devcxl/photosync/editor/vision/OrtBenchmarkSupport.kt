package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.IOException

import ai.onnxruntime.OrtSession
import android.os.SystemClock

/**
 * Plumbing for [OrtModelDeviceBenchmarkTest]: process memory from `/proc/self`, and a
 * [OrtSessionFactory] that records how long each native session took to create and when it closed.
 */

/** Resident set size and peak RSS of the current process, in kB, read from `/proc/self/status`. */
internal data class ProcessMemorySample(val rssKb: Long, val highWaterMarkKb: Long)

/** Reader for the two `/proc/self` counters the benchmark reports. */
internal object ProcessMemoryProbe {

    private const val STATUS_PATH = "/proc/self/status"
    private const val CLEAR_REFS_PATH = "/proc/self/clear_refs"

    fun read(): ProcessMemorySample? {
        val lines = try {
            File(STATUS_PATH).readLines()
        } catch (e: IOException) {
            return null
        }
        val rss = lines.vmKb("VmRSS:")
        val peak = lines.vmKb("VmHWM:")
        return if (rss == null || peak == null) null else ProcessMemorySample(rss, peak)
    }

    /**
     * Resets `VmHWM` so a phase reports its own peak instead of the process' lifetime maximum.
     *
     * Writing `5` (`CLEAR_REFS_MM_HIWATER_RSS`, Linux ≥ 4.0) to `clear_refs` is the documented
     * way to do that; a kernel without the feature returns `false` and the caller reports the
     * cumulative high-water mark instead.
     */
    fun clearHighWaterMark(): Boolean = try {
        File(CLEAR_REFS_PATH).writeText("5")
        true
    } catch (e: IOException) {
        false
    }

    private fun List<String>.vmKb(prefix: String): Long? = firstOrNull { it.startsWith(prefix) }
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.getOrNull(1)
        ?.toLongOrNull()
}

/**
 * Delegates to the production [OnnxRuntimeSessionFactory] and records the lifecycle the benchmark
 * asserts on: creation duration, creation order and whether a handle was closed.
 */internal class RecordingSessionFactory(
    private val delegate: OrtSessionFactory = OnnxRuntimeSessionFactory()
) : OrtSessionFactory {

    /** Wall time of the most recent [create]; 0.0 before the first call. */
    var lastCreateMillis: Double = 0.0
        private set

    val handles: MutableList<TrackedHandle> = mutableListOf()

    override fun create(modelFile: File, intraOpThreads: Int): OrtSessionHandle {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        try {
            val handle = TrackedHandle(delegate.create(modelFile, intraOpThreads))
            handles += handle
            return handle
        } finally {
            lastCreateMillis = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLI
        }
    }

    /** [OrtSessionHandle] that exposes the native [OrtSession] and remembers its close. */
    class TrackedHandle(private val delegate: OrtSessionHandle) : OrtSessionHandle {

        var closed: Boolean = false
            private set

        val session: OrtSession
            get() = (delegate as OnnxRuntimeSessionHandle).session

        override val modelPath: String
            get() = delegate.modelPath

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}

/**
 * Polls [OrtSessionManager.activeSessionCount] until it reaches zero or [timeoutMs] elapses.
 *
 * @return `true` when the manager holds no session any more.
 */
internal fun OrtSessionManager.awaitIdleRelease(timeoutMs: Long): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (activeSessionCount == 0) return true
        Thread.sleep(IDLE_POLL_INTERVAL_MS)
    }
    return activeSessionCount == 0
}

private const val IDLE_POLL_INTERVAL_MS = 500L
