package cn.devcxl.photosync.editor.render

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.export.BakeConcurrencyGate

/**
 * Single-entry cache for the decode of the most recently opened photo's [EditorSource].
 *
 * The gallery preloads the newest photo through [preload] and the editor acquires the same source
 * when it opens. A cache hit hands out a new [EditorSourceLease] over the shared [EditorSource];
 * [EditorSource.BitmapSource] pixels are recycled only when the last lease closes *and* the cache
 * has dropped its own reference, so an evicted entry that is still on screen stays intact.
 *
 * Keys cover path, size, mtime, RAW flag and decode options (see [EditorSourceKey]); touching or
 * re-exporting the file forces a fresh decode.
 *
 * ### Concurrency
 * Lock order is [loadMutex] → [BakeConcurrencyGate.rawBakeMutex] (RAW decodes only) → the state
 * monitor. The state monitor is never held while decoding or waiting on another lock, so
 * [onMemoryPressure] never stalls behind a LibRaw open. Callers must not call [acquire] while
 * holding [BakeConcurrencyGate.rawBakeMutex]: mutexes are not reentrant and that inverts the order.
 *
 * ### Ownership
 * [decode] must return a source this cache uniquely owns; [EditorSourceLoader] allocates fresh
 * bitmaps/buffers per call, which the cache relies on. A cancelled caller releases the source it
 * was decoding instead of installing it. A lease created inside `withContext` is also closed when
 * cancellation wins the handoff back to the caller's dispatcher, so it cannot leak a reference.
 *
 * ### Freshness
 * The file identity (size + mtime) is re-read after the load lock is acquired and again after the
 * decode finishes. A file that changed while a decode was queued or running is never cached under
 * stale metadata; the decode is retried once, then abandoned.
 */
class RecentEditorSourceCache internal constructor(
    private val decode: (sourcePath: String, isRaw: Boolean, preferLinear: Boolean) -> EditorSource? =
        EditorSourceLoader::load,
    private val readMetadata: (sourcePath: String) -> SourceFileMetadata? = ::readSourceFileMetadata,
    private val releaseSource: (EditorSource) -> Unit = { it.release() },
    private val rawDecodeMutex: Mutex = BakeConcurrencyGate.rawBakeMutex,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val stateLock = Any()
    private val loadMutex = Mutex()

    /** Current entry guarded by [stateLock]; `null` when nothing is cached. */
    private var current: Entry? = null

    /** Bumped by [onMemoryPressure] so a decode that started earlier cannot repopulate the cache. */
    private var generation: Long = 0L

    /**
     * Decodes the photo if it is not cached yet and leaves it warm for the next editor launch.
     *
     * Safe to call from a main-thread coroutine: decoding switches to [ioDispatcher]. Cancelling the
     * call cancels the wait for [rawDecodeMutex] and releases a decode that already finished.
     */
    suspend fun preload(sourcePath: String, isRaw: Boolean, preferLinear: Boolean) {
        acquire(sourcePath, isRaw, preferLinear)?.close()
    }

    /**
     * Returns a strong reference to the cached source, decoding it on a miss.
     *
     * The caller owns the returned lease and must close it (idempotently) when the renderer no longer
     * samples the pixels. Blocking decode work runs on [ioDispatcher].
     *
     * @return The lease, or `null` when the file is missing or cannot be decoded.
     */
    internal suspend fun acquire(
        sourcePath: String,
        isRaw: Boolean,
        preferLinear: Boolean
    ): EditorSourceLease? {
        // withContext can complete its block and still throw while resuming on the caller's
        // dispatcher when the coroutine is cancelled ("prompt cancellation"). Without a holder the
        // lease would be dropped without its close ever reaching the reference count, leaking it.
        val handoff = AtomicReference<EditorSourceLease?>(null)
        return try {
            val lease = withContext(ioDispatcher) {
                acquireOnIo(sourcePath, isRaw, preferLinear)?.also(handoff::set)
            }
            handoff.set(null)
            lease
        } catch (failure: Throwable) {
            handoff.getAndSet(null)?.close()
            throw failure
        }
    }

    private suspend fun acquireOnIo(
        sourcePath: String,
        isRaw: Boolean,
        preferLinear: Boolean
    ): EditorSourceLease? {
        if (sourcePath.isBlank()) return null
        val metadata = readMetadata(sourcePath)
        if (metadata == null) {
            forgetPath(sourcePath)
            return null
        }
        takeCached(keyOf(sourcePath, isRaw, preferLinear, metadata))?.let { return leaseOf(it) }
        return loadMutex.withLock {
            // The fast-path metadata above may be stale by now if this caller waited for the lock,
            // so re-read it before building the key the decode will be cached under.
            repeat(MAX_DECODE_ATTEMPTS) {
                val freshMetadata = readMetadata(sourcePath)
                if (freshMetadata == null) {
                    forgetPath(sourcePath)
                    return@withLock null
                }
                val key = keyOf(sourcePath, isRaw, preferLinear, freshMetadata)
                takeCached(key)?.let { return@withLock leaseOf(it) }
                when (val outcome = load(key)) {
                    is LoadOutcome.Loaded -> return@withLock outcome.lease
                    LoadOutcome.IdentityChanged -> Unit
                    LoadOutcome.Failed -> return@withLock null
                }
            }
            Timber.w("Recent editor source kept changing while decoding; leaving it uncached")
            null
        }
    }

    /**
     * Drops the cache's own reference immediately, e.g. from `ComponentCallbacks2.onTrimMemory`.
     *
     * A source still leased by the editor or portrait analysis stays alive until its last lease
     * closes — memory pressure must never recycle pixels that are still being sampled.
     */
    fun onMemoryPressure() {
        var stale: EditorSource? = null
        synchronized(stateLock) {
            generation++
            val entry = current ?: return
            current = null
            entry.references--
            if (entry.references == 0) stale = entry.source
        }
        stale?.let(releaseSource)
    }

    private fun keyOf(
        sourcePath: String,
        isRaw: Boolean,
        preferLinear: Boolean,
        metadata: SourceFileMetadata
    ): EditorSourceKey = EditorSourceKey(
        sourcePath = sourcePath,
        fileSizeBytes = metadata.sizeBytes,
        lastModifiedMillis = metadata.lastModifiedMillis,
        isRaw = isRaw,
        decodeOptions = SourceDecodeOptions(
            // preferLinear only changes the output of a RAW decode; JPEG keys stay stable when the
            // GLES 3.0 capability of the next editor session differs.
            preferLinear = isRaw && preferLinear,
            decodeProfile = EditorSourceLoader.DECODE_PROFILE_ID
        )
    )

    /** Pins [key]'s entry when present and usable, dropping a source someone already recycled. */
    private fun takeCached(key: EditorSourceKey): Entry? {
        var stale: EditorSource? = null
        var hit: Entry? = null
        synchronized(stateLock) {
            val candidate = current
            when {
                candidate == null || candidate.key != key -> Unit
                !candidate.source.isUsable() -> {
                    current = null
                    candidate.references--
                    if (candidate.references == 0) stale = candidate.source
                }
                else -> {
                    candidate.references++
                    hit = candidate
                }
            }
        }
        stale?.let(releaseSource)
        return hit
    }

    private suspend fun load(key: EditorSourceKey): LoadOutcome {
        val generationBefore = synchronized(stateLock) { generation }
        val decoded = when (val outcome = decodeIfCurrent(key)) {
            is DecodeOutcome.Decoded -> outcome.source
            DecodeOutcome.IdentityChanged -> return LoadOutcome.IdentityChanged
            DecodeOutcome.Failed -> return LoadOutcome.Failed
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            releaseSource(decoded)
            throw cancelled
        }
        // The decoder read the file at some point during the call above; if the writer replaced it
        // mid-decode the pixels can be torn, so discard them instead of caching under [key].
        val stillMatches = try {
            fileStillMatches(key)
        } catch (failure: Throwable) {
            releaseSource(decoded)
            throw failure
        }
        if (!stillMatches) {
            releaseSource(decoded)
            return LoadOutcome.IdentityChanged
        }

        var evicted: EditorSource? = null
        val installed = synchronized(stateLock) {
            if (generation != generationBefore) {
                null
            } else {
                current?.let { previous ->
                    previous.references--
                    if (previous.references == 0) evicted = previous.source
                }
                // Two references: the cache's own plus the lease handed back below.
                Entry(key = key, source = decoded, references = 2).also { current = it }
            }
        }
        evicted?.let(releaseSource)
        val lease = if (installed != null) {
            leaseOf(installed)
        } else {
            // Memory pressure dropped the cache while this decode ran. Hand the pixels to the
            // caller without repopulating the cache, so the pressure signal is not undone.
            EditorSourceLease(decoded, key) { releaseSource(decoded) }
        }
        return LoadOutcome.Loaded(lease)
    }

    private suspend fun decodeIfCurrent(key: EditorSourceKey): DecodeOutcome {
        if (!key.isRaw) {
            return decodeSafely(key, isRaw = false)
                ?.let(DecodeOutcome::Decoded)
                ?: DecodeOutcome.Failed
        }
        return rawDecodeMutex.withLock {
            // The raw gate can wait seconds behind a bake; verify the file again once we own it so
            // a rewrite that landed while queued does not get decoded against stale metadata.
            if (!fileStillMatches(key)) {
                DecodeOutcome.IdentityChanged
            } else {
                decodeSafely(key, isRaw = true)
                    ?.let(DecodeOutcome::Decoded)
                    ?: DecodeOutcome.Failed
            }
        }
    }

    private fun decodeSafely(key: EditorSourceKey, isRaw: Boolean): EditorSource? = try {
        decode(key.sourcePath, isRaw, key.decodeOptions.preferLinear)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "OOM while decoding the recent editor source")
        null
    } catch (failure: RuntimeException) {
        Timber.w(failure, "Recent editor source decode failed")
        null
    }

    private fun fileStillMatches(key: EditorSourceKey): Boolean {
        val metadata = readMetadata(key.sourcePath) ?: return false
        return key.fileSizeBytes == metadata.sizeBytes &&
            key.lastModifiedMillis == metadata.lastModifiedMillis
    }

    private fun leaseOf(entry: Entry): EditorSourceLease =
        EditorSourceLease(entry.source, entry.key) { releaseReference(entry) }

    private fun releaseReference(entry: Entry) {
        var stale: EditorSource? = null
        synchronized(stateLock) {
            entry.references--
            if (entry.references == 0) stale = entry.source
        }
        stale?.let(releaseSource)
    }

    private fun forgetPath(sourcePath: String) {
        var stale: EditorSource? = null
        synchronized(stateLock) {
            val entry = current ?: return
            if (entry.key.sourcePath != sourcePath) return
            current = null
            entry.references--
            if (entry.references == 0) stale = entry.source
        }
        stale?.let(releaseSource)
    }

    private class Entry(
        val key: EditorSourceKey,
        val source: EditorSource,
        var references: Int
    )

    companion object {
        /** One retry is enough for a writer that re-exported the file while it was queued. */
        private const val MAX_DECODE_ATTEMPTS = 2

        /**
         * Process-wide cache shared by the gallery preloader and the editor activity.
         */
        val shared: RecentEditorSourceCache = RecentEditorSourceCache()
    }
}

/**
 * A strong reference to a shared [EditorSource] returned by [RecentEditorSourceCache.acquire].
 *
 * Closing is idempotent and only drops this reference: the underlying bitmaps are recycled when the
 * cache has already evicted the entry and no other lease remains.
 */
internal class EditorSourceLease internal constructor(
    val source: EditorSource,
    /**
     * Identity of the decode behind [source] — the file size and mtime the cache read **before** it
     * decoded, plus the path and decode options.
     *
     * A consumer that re-reads the file (portrait analysis, another decode) must compare the current
     * file against this key instead of statting it again: a replacement between the decode and a
     * later stat would otherwise look consistent while the pixels are older than the analysis.
     */
    val key: EditorSourceKey? = null,
    private val onClosed: () -> Unit
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onClosed()
        }
    }
}

/**
 * Identity of one decode result.
 *
 * @property sourcePath Path exactly as handed to the decoder.
 * @property fileSizeBytes Size at decode time; detects re-exports that keep the mtime.
 * @property lastModifiedMillis Last-modified time at decode time.
 * @property isRaw True when the decoder used the LibRaw path.
 * @property decodeOptions Output-affecting options; see [SourceDecodeOptions].
 */
internal data class EditorSourceKey(
    val sourcePath: String,
    val fileSizeBytes: Long,
    val lastModifiedMillis: Long,
    val isRaw: Boolean,
    val decodeOptions: SourceDecodeOptions
)

/**
 * Output-affecting decode options.
 *
 * @property preferLinear True when 16-bit linear RAW texels were requested (always false for JPEG).
 * @property decodeProfile Fingerprint of the hard-wired preview/proxy caps in the loaders.
 */
internal data class SourceDecodeOptions(
    val preferLinear: Boolean,
    val decodeProfile: String
)

/** File identity fields used to decide whether a cached decode is still current. */
internal data class SourceFileMetadata(
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

private fun readSourceFileMetadata(sourcePath: String): SourceFileMetadata? {
    val file = File(sourcePath)
    if (!file.isFile) return null
    val size = file.length()
    if (size <= 0L) return null
    return SourceFileMetadata(sizeBytes = size, lastModifiedMillis = file.lastModified())
}

/** Result of one decode attempt, distinguishing a raced file rewrite from a hard failure. */
private sealed interface DecodeOutcome {
    class Decoded(val source: EditorSource) : DecodeOutcome

    /** The file no longer matches the key this attempt was checked against. */
    object IdentityChanged : DecodeOutcome

    object Failed : DecodeOutcome
}

/** Result of one install attempt under the load lock. */
private sealed interface LoadOutcome {
    class Loaded(val lease: EditorSourceLease) : LoadOutcome

    /** The file changed while queued or decoding; the caller may retry with fresh metadata. */
    object IdentityChanged : LoadOutcome

    object Failed : LoadOutcome
}

/**
 * True while the source still owns live pixels.
 *
 * A cached [EditorSource.BitmapSource] can only be recycled by a caller that bypassed its lease
 * (for example the pre-lease `EditorPreviewSession.release()`), so a hit verifies usability instead
 * of handing out a recycled bitmap. Linear buffers are JVM-owned and always usable.
 */
private fun EditorSource.isUsable(): Boolean = when (this) {
    is EditorSource.BitmapSource -> !full.isRecycled && !proxy.isRecycled
    is EditorSource.LinearRawSource -> true
}
