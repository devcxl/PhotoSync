package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.IOException

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import cn.devcxl.photosync.editor.domain.BodyOps
import cn.devcxl.photosync.editor.domain.BodyWarpSolver
import cn.devcxl.photosync.editor.domain.FaceLandmarks
import cn.devcxl.photosync.editor.domain.PortraitAnalysis

/**
 * Identity of the optional downloaded six-class portrait model.
 *
 * Only presence, size and last-modified time are hashed: the file is 16 MB, so re-reading its
 * SHA-256 on every preview would cost more than the analysis the cache protects. The
 * [ModelDownloadManager] renames a verified download atomically and deletes a cleared one, so a
 * completed, replaced or removed model always changes this identity.
 *
 * @property path Absolute path of the model file, so two model directories can never alias one
 *   entry.
 * @property sizeBytes Size at key time, or [ABSENT_SIZE] when the file was not on disk.
 * @property lastModifiedMillis Last-modified time at key time, or [ABSENT_SIZE] when absent.
 */
internal data class PortraitModelIdentity(
    val path: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
) {

    /** True when the model existed when the key was taken. */
    val isPresent: Boolean
        get() = sizeBytes != ABSENT_SIZE

    companion object {
        /** Sentinel stored in [sizeBytes] / [lastModifiedMillis] when the model is absent. */
        const val ABSENT_SIZE = -1L

        /** Snapshots the on-disk identity of [file] without throwing when it is missing. */
        fun of(file: File): PortraitModelIdentity = if (file.isFile) {
            PortraitModelIdentity(
                path = file.absolutePath,
                sizeBytes = file.length(),
                lastModifiedMillis = file.lastModified()
            )
        } else {
            PortraitModelIdentity(file.absolutePath, ABSENT_SIZE, ABSENT_SIZE)
        }
    }
}

/**
 * Analysis inputs that are neither the source file nor the model but still change the result.
 *
 * @property analysisMaxEdgePx Proxy long edge handed to the segmentation pass.
 * @property delegate MediaPipe delegate the engine tries first (`GPU` or `CPU`); the two delegates
 *   do not produce bit-identical masks, and a device that just blacklisted the GPU delegate must not
 *   read a mask that was computed on it.
 * @property pipelineProfile Fingerprint of the decode/analyze pipeline; bump it whenever the proxy
 *   decode, the bundled assets or the mask post-processing change.
 */
internal data class PortraitAnalysisEnvironment(
    val analysisMaxEdgePx: Int,
    val delegate: String,
    val pipelineProfile: String
)

/**
 * Identity of one cached portrait analysis.
 *
 * Deliberately contains nothing from [cn.devcxl.photosync.editor.domain.EditGraph]: the cached
 * value is the graph-free inference output, so moving a slider never invalidates an entry.
 *
 * @property sourcePath Path exactly as handed to the analysis decoder.
 * @property sourceSizeBytes Size at key time; detects re-exports that keep the mtime.
 * @property sourceLastModifiedMillis Last-modified time at key time.
 * @property isRaw True when the proxy came from the LibRaw path.
 * @property model Identity of the optional downloaded model; a finished download invalidates.
 * @property environment Delegate and pipeline fingerprint.
 */
internal data class PortraitAnalysisKey(
    val sourcePath: String,
    val sourceSizeBytes: Long,
    val sourceLastModifiedMillis: Long,
    val isRaw: Boolean,
    val model: PortraitModelIdentity,
    val environment: PortraitAnalysisEnvironment
)

/**
 * The graph-independent result of one MediaPipe portrait pass.
 *
 * The cache stores this instead of a raw [PortraitAnalysis] because that class also carries the
 * body [cn.devcxl.photosync.editor.domain.BodyGuides], which depend on the persisted
 * `portrait.body` overrides and therefore on the graph. Guides are cheap to re-derive, so
 * [toAnalysis] rebuilds them for the caller's values while the expensive masks are shared.
 *
 * The arrays are read-only for every consumer (they are copied into GL textures or sampled by the
 * CPU passes), which is what makes sharing one snapshot across renders safe.
 */
internal class PortraitAnalysisSnapshot(
    val faces: List<FaceLandmarks>,
    val skinMask: FloatArray,
    val personMask: FloatArray?,
    val usesMulticlass: Boolean
) {

    /** Materializes a [PortraitAnalysis] whose guides honour [body]'s waist / hip overrides. */
    fun toAnalysis(body: BodyOps): PortraitAnalysis = PortraitAnalysis(
        faces = faces,
        skinMask = skinMask,
        personMask = personMask,
        guides = BodyWarpSolver.resolveGuides(faces.firstOrNull(), personMask, body),
        usesMulticlass = usesMulticlass
    )

    companion object {

        /** Copies the graph-free fields out of [analysis], dropping its resolved guides. */
        fun from(analysis: PortraitAnalysis): PortraitAnalysisSnapshot = PortraitAnalysisSnapshot(
            faces = analysis.faces,
            skinMask = analysis.skinMask,
            personMask = analysis.personMask,
            usesMulticlass = analysis.usesMulticlass
        )
    }
}

/**
 * Raised when the photo or the portrait model changed while the preview analysis was running.
 *
 * Retrying is the recovery: the pass that just finished no longer describes the bytes on disk, so a
 * render must be abandoned instead of being published with a mask that belongs to a different frame.
 * Callers that can keep showing their current preview simply treat this as a recoverable failure.
 *
 * The message and the flags deliberately carry no path, size or hash, because the renderer logs this
 * exception.
 *
 * @property sourceChanged True when the photo was replaced, touched or deleted, or its RAW flag
 *   changed, during the pass.
 * @property modelChanged True when the downloaded model was added, replaced or removed during the
 *   pass.
 * @property environmentChanged True when the delegate, the analysis proxy size or the pipeline
 *   profile changed during the pass.
 */
class PortraitIdentityDriftException internal constructor(
    val sourceChanged: Boolean,
    val modelChanged: Boolean,
    val environmentChanged: Boolean
) : IOException(DRIFT_MESSAGE)

private const val DRIFT_MESSAGE = "Portrait source or model changed during the preview analysis"

/**
 * Single-entry, suspending cache of the [PortraitAnalysisSnapshot] the background preview reuses.
 *
 * ## What is cached
 * At most one analysis, keyed by [PortraitAnalysisKey]: the source identity (path, size, mtime, RAW
 * flag), the downloaded-model identity and the analysis environment. The derived face / body /
 * liquify warps are *not* cached — the caller rebuilds them from the snapshot and the current graph
 * on every call.
 *
 * ## Concurrency and cancellation
 * - One coroutine [Mutex] is held across the cache access **and** the single analysis it guards, so
 *   callers of any key take turns and never run two MediaPipe passes at once. Waiting for the mutex
 *   is a suspension point, so a cancelled caller leaves immediately with
 *   [kotlinx.coroutines.CancellationException] instead of blocking until the running pass finishes.
 * - A pass that is already running cannot observe cancellation (MediaPipe is blocking); the
 *   coroutine context is captured before the lock is taken and re-checked right after the pass, so a
 *   cancelled caller neither returns nor publishes the result.
 * - [clear] never takes the mutex — memory pressure must not queue behind a 20 s inference — it only
 *   bumps [generation] and drops the entry under [stateLock]. A pass that started before the bump
 *   therefore cannot repopulate the cache.
 *
 * ## Lock order
 * `BakeConcurrencyGate.rawBakeMutex` (held by the RAW preview path before it calls in) → [mutex] →
 * [stateLock]. [stateLock] is a leaf monitor: it is only taken for the entry and the generation,
 * never around a suspension, I/O or the analysis, and nothing inside it acquires another lock.
 *
 * ## Failure and identity drift
 * A failed pass (`produce` returns `null`) is never stored and yields `null`, so the caller can fall
 * back to the mask-less result. An identity change is **not** a failure: the key is derived from
 * `keyOf` under the mutex immediately before the pass and once more after it, and a difference
 * throws [PortraitIdentityDriftException] — the stale pass is neither returned nor stored, and the
 * caller is expected to abandon the render.
 */
internal class PortraitAnalysisCache {

    /** Serializes the cache access and the analysis that follows a miss. */
    private val mutex = Mutex()

    /** Guards [entry] and [generation]; never held while suspending or analysing. */
    private val stateLock = Any()

    private var generation: Long = 0L

    private var entry: Entry? = null

    /**
     * Returns the cached snapshot for [key], or runs [produce] and stores its result.
     *
     * [produce] is a plain blocking lambda because the analysis it wraps is a blocking MediaPipe
     * pass; it runs while this call owns [mutex], so at most one analysis is alive at a time and
     * callers must not re-enter [get] from inside it (use a dedicated dispatcher for it, never the
     * main thread).
     *
     * Cancellation of a caller that is waiting for the mutex propagates immediately. Cancellation of
     * the caller that owns the mutex is re-checked before and after the blocking pass, so the
     * result of a cancelled pass is neither returned nor stored.
     *
     * @param keyOf Re-derives the identity from the source file and the model. Called under the
     *   mutex right before the pass and again right after it; returning `null` means the source has
     *   no stable identity (missing or empty file) and nothing is analysed or cached.
     * @param produce Expensive work that returns `null` when the source cannot be analysed.
     * @return The snapshot, or `null` when the identity is unavailable or [produce] failed.
     * @throws PortraitIdentityDriftException when the photo, the model or the analysis environment
     *   changed while the pass was running. The stale result is discarded, so retrying analyses the
     *   current bytes; the exception never contains the path.
     */
    suspend fun get(
        keyOf: () -> PortraitAnalysisKey?,
        produce: () -> PortraitAnalysisSnapshot?
    ): PortraitAnalysisSnapshot? {
        val context = currentCoroutineContext()
        return mutex.withLock {
            val key = keyOf() ?: return@withLock null
            val cached = synchronized(stateLock) { entry?.takeIf { it.key == key } }
            if (cached != null) return@withLock cached.snapshot

            context.ensureActive()
            val generationAtStart = synchronized(stateLock) { generation }
            val snapshot = produce() ?: return@withLock null

            // A cancelled caller must not receive, nor publish, a pass that finished after the
            // cancellation landed.
            context.ensureActive()
            // Re-derived after the pass. A different identity means this result no longer describes
            // the bytes on disk, so publishing it would attach another frame's mask to the photo:
            // discard it and fail explicitly instead of falling back to a mask-less render.
            val current = keyOf()
            if (current != key) {
                discardEntry(key)
                throw driftBetween(key, current)
            }
            synchronized(stateLock) {
                if (generation == generationAtStart) entry = Entry(key, snapshot)
            }
            snapshot
        }
    }

    /** Drops an entry that no longer describes the bytes on disk; the caller holds [mutex]. */
    private fun discardEntry(key: PortraitAnalysisKey) {
        synchronized(stateLock) {
            if (entry?.key == key) entry = null
        }
    }

    /** Classifies which part of the identity moved, without ever describing the file itself. */
    private fun driftBetween(
        expected: PortraitAnalysisKey,
        actual: PortraitAnalysisKey?
    ): PortraitIdentityDriftException {
        if (actual == null) {
            return PortraitIdentityDriftException(
                sourceChanged = true,
                modelChanged = false,
                environmentChanged = false
            )
        }
        return PortraitIdentityDriftException(
            sourceChanged = expected.sourcePath != actual.sourcePath ||
                expected.sourceSizeBytes != actual.sourceSizeBytes ||
                expected.sourceLastModifiedMillis != actual.sourceLastModifiedMillis ||
                expected.isRaw != actual.isRaw,
            modelChanged = expected.model != actual.model,
            environmentChanged = expected.environment != actual.environment
        )
    }

    /**
     * Drops the entry and invalidates every pass that is already running.
     *
     * Safe to call from any thread, including under memory pressure while an analysis is in flight:
     * it never waits for [mutex].
     */
    fun clear() {
        synchronized(stateLock) {
            generation++
            entry = null
        }
    }

    private class Entry(val key: PortraitAnalysisKey, val snapshot: PortraitAnalysisSnapshot)
}

/**
 * Process-wide facade over the background-preview portrait analysis cache.
 *
 * [cn.devcxl.photosync.editor.export.BakePortraitSupport.resolvePreview] is the only reader; the
 * export path keeps using the synchronous, uncached `resolve`, so a bake never shares state with a
 * preview.
 *
 * Wiring hook for the host application: call [clear] from
 * `ComponentCallbacks2.onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` or higher, and from `onLowMemory()`.
 */
object PortraitPreviewCache {

    private val cache = PortraitAnalysisCache()

    internal suspend fun analysis(
        keyOf: () -> PortraitAnalysisKey?,
        produce: () -> PortraitAnalysisSnapshot?
    ): PortraitAnalysisSnapshot? = cache.get(keyOf, produce)

    /** Drops the cached analysis; a pass already running finishes but is not stored. */
    fun clear() {
        cache.clear()
    }
}
