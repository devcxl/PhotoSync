package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.wrapper.LinearRawBuffer

class RecentEditorSourceCacheTest {

    private val jpegPath = "/photos/IMG_0001.jpg"
    private val rawPath = "/photos/IMG_0002.dng"

    private val metadata = mutableMapOf<String, SourceFileMetadata>()
    private val decodeCalls = AtomicInteger()
    private val released: MutableList<EditorSource> = Collections.synchronizedList(mutableListOf())

    private fun newCache(
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        rawDecodeMutex: Mutex = Mutex(),
        readMetadata: ((String) -> SourceFileMetadata?)? = null,
        decode: (String, Boolean, Boolean) -> EditorSource? = { _, _, _ -> linearSource() }
    ): RecentEditorSourceCache = RecentEditorSourceCache(
        decode = { path, isRaw, preferLinear ->
            decodeCalls.incrementAndGet()
            decode(path, isRaw, preferLinear)
        },
        readMetadata = readMetadata ?: { metadata[it] },
        releaseSource = { released += it },
        rawDecodeMutex = rawDecodeMutex,
        ioDispatcher = ioDispatcher
    )

    private fun meta(size: Long = 1024L, modified: Long = 1_000L) =
        SourceFileMetadata(sizeBytes = size, lastModifiedMillis = modified)

    private fun linearSource(): EditorSource.LinearRawSource {
        val buffer = LinearRawBuffer(
            width = 2,
            height = 2,
            autoBrightGain = 1f,
            effectiveIso = 100f,
            rgba16fBuffer = ByteBuffer.allocateDirect(2 * 2 * 8).order(ByteOrder.nativeOrder())
        )
        return EditorSource.LinearRawSource(full = buffer, proxy = buffer)
    }

    @Test
    fun shouldDecodeOnce_whenSameFileIsAcquiredTwice() = runBlocking {
        metadata[jpegPath] = meta()
        val cache = newCache()

        val first = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))
        val second = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))

        assertEquals(1, decodeCalls.get())
        assertSame(first.source, second.source)
        first.close()
        second.close()
        assertEquals(0, released.size)
    }

    @Test
    fun shouldLeaveSourceWarm_whenPreloadCompletes() = runBlocking {
        metadata[jpegPath] = meta()
        val cache = newCache()

        cache.preload(jpegPath, isRaw = true, preferLinear = true)
        val lease = checkNotNull(cache.acquire(jpegPath, isRaw = true, preferLinear = true))

        assertEquals(1, decodeCalls.get())
        lease.close()
        cache.onMemoryPressure()
        assertEquals(1, released.size)
    }

    @Test
    fun shouldDecodeAgain_whenFileSizeChanges() = runBlocking {
        metadata[jpegPath] = meta(size = 1024L)
        val cache = newCache()
        cache.acquire(jpegPath, isRaw = false, preferLinear = true)?.close()

        metadata[jpegPath] = meta(size = 2048L)
        val reloaded = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))

        assertEquals(2, decodeCalls.get())
        assertEquals(1, released.size)
        reloaded.close()
    }

    @Test
    fun shouldDecodeAgain_whenModifiedTimeChanges() = runBlocking {
        metadata[jpegPath] = meta(modified = 1_000L)
        val cache = newCache()
        cache.acquire(jpegPath, isRaw = false, preferLinear = true)?.close()

        metadata[jpegPath] = meta(modified = 2_000L)
        val reloaded = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))

        assertEquals(2, decodeCalls.get())
        assertEquals(1, released.size)
        reloaded.close()
    }

    @Test
    fun shouldDecodeAgain_whenRawFlagChanges() = runBlocking {
        metadata[rawPath] = meta()
        val cache = newCache()
        cache.acquire(rawPath, isRaw = false, preferLinear = true)?.close()

        val reloaded = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))

        assertEquals(2, decodeCalls.get())
        assertEquals(1, released.size)
        reloaded.close()
    }

    @Test
    fun shouldDecodeAgain_whenLinearPreferenceChangesForRaw() = runBlocking {
        metadata[rawPath] = meta()
        val cache = newCache()
        cache.acquire(rawPath, isRaw = true, preferLinear = true)?.close()

        val reloaded = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = false))

        assertEquals(2, decodeCalls.get())
        assertEquals(1, released.size)
        reloaded.close()
    }

    @Test
    fun shouldReuseHit_whenLinearPreferenceChangesForJpeg() = runBlocking {
        metadata[jpegPath] = meta()
        val cache = newCache()
        cache.acquire(jpegPath, isRaw = false, preferLinear = true)?.close()

        val hit = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = false))

        assertEquals(1, decodeCalls.get())
        assertEquals(0, released.size)
        hit.close()
    }

    @Test
    fun shouldEvictUnusedPreviousSource_whenAnotherFileIsAcquired() = runBlocking {
        metadata[jpegPath] = meta()
        metadata[rawPath] = meta()
        val cache = newCache()
        cache.acquire(jpegPath, isRaw = false, preferLinear = true)?.close()

        val replacement = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))

        assertEquals(2, decodeCalls.get())
        assertEquals(1, released.size)
        replacement.close()
    }

    @Test
    fun shouldKeepPinnedSourceAlive_whenAnotherFileEvictsIt() = runBlocking {
        metadata[jpegPath] = meta()
        metadata[rawPath] = meta()
        val cache = newCache()
        val pinned = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))

        val replacement = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))
        assertEquals(0, released.size)

        pinned.close()
        assertEquals(1, released.size)
        assertSame(pinned.source, released.single())
        replacement.close()
    }

    @Test
    fun shouldKeepPinnedSourceAlive_whenMemoryPressureHits() = runBlocking {
        metadata[rawPath] = meta()
        val cache = newCache()
        val pinned = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))

        cache.onMemoryPressure()
        assertEquals(0, released.size)

        pinned.close()
        assertEquals(1, released.size)
        assertSame(pinned.source, released.single())
    }

    @Test
    fun shouldReleaseCachedSource_whenMemoryPressureHits() = runBlocking {
        metadata[rawPath] = meta()
        val cache = newCache()
        cache.acquire(rawPath, isRaw = true, preferLinear = true)?.close()

        cache.onMemoryPressure()

        assertEquals(1, released.size)
    }

    @Test
    fun shouldKeepSourceAlive_whenOneOfTwoLeasesCloses() = runBlocking {
        metadata[rawPath] = meta()
        val cache = newCache()
        val first = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))
        val second = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))

        first.close()
        cache.onMemoryPressure()
        assertEquals(0, released.size)

        second.close()
        assertEquals(1, released.size)
    }

    @Test
    fun shouldReleaseOnlyOnce_whenLeaseIsClosedTwice() = runBlocking {
        metadata[rawPath] = meta()
        val cache = newCache()
        val lease = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))
        cache.onMemoryPressure()

        lease.close()
        lease.close()

        assertEquals(1, released.size)
    }

    @Test
    fun shouldReturnNull_whenFileIsMissing() = runBlocking {
        val cache = newCache()

        assertNull(cache.acquire("/photos/missing.jpg", isRaw = false, preferLinear = true))
        assertEquals(0, decodeCalls.get())
    }

    @Test
    fun shouldForgetCachedSource_whenFileDisappears() = runBlocking {
        metadata[jpegPath] = meta()
        val cache = newCache()
        cache.acquire(jpegPath, isRaw = false, preferLinear = true)?.close()

        metadata.remove(jpegPath)

        assertNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))
        assertEquals(1, released.size)
    }

    @Test
    fun shouldDecodeOnce_whenCallersRaceOnTheSameFile() = runBlocking {
        metadata[jpegPath] = meta()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val cache = newCache(decode = { _, _, _ ->
            started.countDown()
            gate.await()
            linearSource()
        })

        val first = async(Dispatchers.Default) { cache.acquire(jpegPath, isRaw = false, preferLinear = true) }
        assertTrue("the first decode did not start", started.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { cache.acquire(jpegPath, isRaw = false, preferLinear = true) }
        gate.countDown()

        val firstLease = checkNotNull(first.await())
        val secondLease = checkNotNull(second.await())
        assertEquals(1, decodeCalls.get())
        assertSame(firstLease.source, secondLease.source)
        firstLease.close()
        secondLease.close()
    }

    @Test
    fun shouldReleaseDecodedSource_whenCallerIsCancelledDuringDecode() = runBlocking {
        metadata[jpegPath] = meta()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val cache = newCache(decode = { _, _, _ ->
            started.countDown()
            gate.await()
            linearSource()
        })

        val job = launch(Dispatchers.Default) {
            cache.acquire(jpegPath, isRaw = false, preferLinear = true)?.close()
        }
        assertTrue("the decode did not start", started.await(5, TimeUnit.SECONDS))
        job.cancel()
        gate.countDown()
        job.join()

        assertEquals(1, released.size)
        assertEquals(1, decodeCalls.get())

        // Cancelled work must not have populated the cache.
        val lease = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))
        assertEquals(2, decodeCalls.get())
        lease.close()
    }

    @Test
    fun shouldNotRepopulateCache_whenMemoryPressureHitsDuringDecode() = runBlocking {
        metadata[jpegPath] = meta()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val cache = newCache(decode = { _, _, _ ->
            started.countDown()
            gate.await()
            linearSource()
        })

        val pending = async(Dispatchers.Default) {
            cache.acquire(jpegPath, isRaw = false, preferLinear = true)
        }
        assertTrue("the decode did not start", started.await(5, TimeUnit.SECONDS))
        cache.onMemoryPressure()
        gate.countDown()

        val lease = checkNotNull(pending.await())
        assertEquals(0, released.size)
        lease.close()
        assertEquals(1, released.size)

        val again = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))
        assertEquals(2, decodeCalls.get())
        again.close()
    }

    @Test
    fun shouldCloseLease_whenCancelledWhileHandingItBackToTheCaller() {
        metadata[jpegPath] = meta()
        val io = QueuedDispatcher()
        val cache = newCache(ioDispatcher = io)
        val caller = QueuedDispatcher()
        var acquired: EditorSourceLease? = null
        val job = CoroutineScope(caller).launch {
            acquired = cache.acquire(jpegPath, isRaw = false, preferLinear = true)
        }

        caller.runNext()    // acquire suspends into withContext(io)
        io.runNext()        // the IO block decodes and queues its result on the caller dispatcher
        // The IO block has finished and queued its result on the caller dispatcher; cancel before
        // that continuation runs, which is exactly where withContext drops the returned value.
        val resume = caller.takeNext()
        job.cancel()
        resume.run()
        caller.runUntilIdle()

        assertEquals(1, decodeCalls.get())
        assertNull(acquired)
        assertTrue(job.isCancelled)
        // The lease was closed without being received: eviction can now recycle the source instead
        // of keeping a reference count that nobody will ever decrement.
        assertEquals(0, released.size)
        cache.onMemoryPressure()
        assertEquals(1, released.size)
    }

    @Test
    fun shouldDiscardAndRetry_whenFileIdentityChangesDuringDecode() = runBlocking {
        metadata[jpegPath] = meta(size = 1024L)
        val decodedSources = mutableListOf<EditorSource>()
        val cache = newCache(decode = { _, _, _ ->
            val source = linearSource()
            decodedSources += source
            if (decodedSources.size == 1) {
                metadata[jpegPath] = meta(size = 2048L)
            }
            source
        })

        val lease = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))

        assertEquals(2, decodeCalls.get())
        assertEquals(2, decodedSources.size)
        assertSame(decodedSources[1], lease.source)
        assertEquals(1, released.size)
        assertSame(decodedSources[0], released.single())

        lease.close()
        cache.onMemoryPressure()
        assertEquals(2, released.size)
    }

    @Test
    fun shouldRecheckFileIdentity_afterWaitingForRawGate() = runBlocking {
        metadata[rawPath] = meta(size = 1024L)
        val gate = Mutex()
        gate.lock()
        val observableGate = ObservableMutex(gate)
        val cache = newCache(rawDecodeMutex = observableGate)

        val pending = async(Dispatchers.Default) {
            cache.acquire(rawPath, isRaw = true, preferLinear = true)
        }
        assertTrue(
            "the decode never queued behind the raw gate",
            observableGate.lockAttempted.await(5, TimeUnit.SECONDS)
        )
        metadata[rawPath] = meta(size = 2048L)
        gate.unlock()

        val lease = checkNotNull(pending.await())
        // The stale attempt was rejected before decoding; the retry decoded exactly once.
        assertEquals(1, decodeCalls.get())
        lease.close()

        val hit = checkNotNull(cache.acquire(rawPath, isRaw = true, preferLinear = true))
        assertEquals(1, decodeCalls.get())
        hit.close()
    }

    @Test
    fun shouldCacheUnderFreshMetadata_whenFastPathStatIsStale() = runBlocking {
        var statReads = 0
        val cache = newCache(readMetadata = {
            if (statReads++ == 0) meta(size = 1024L) else meta(size = 2048L)
        })

        val lease = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))
        assertEquals(1, decodeCalls.get())
        lease.close()

        // A later acquire sees the fresh identity and hits the entry installed under it.
        val hit = checkNotNull(cache.acquire(jpegPath, isRaw = false, preferLinear = true))
        assertEquals(1, decodeCalls.get())
        hit.close()
    }

    /** Queues continuations so a test can cancel between block completion and the caller resume. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.put(block)
        }

        fun runNext() {
            takeNext().run()
        }

        fun takeNext(): Runnable =
            checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "the continuation was never dispatched" }

        fun runUntilIdle() {
            while (true) {
                val task = tasks.poll() ?: return
                task.run()
            }
        }
    }

    /** Signals the first attempt to take [delegate], so a test can rewrite the file while queued. */
    private class ObservableMutex(private val delegate: Mutex) : Mutex by delegate {
        val lockAttempted = CountDownLatch(1)

        override suspend fun lock(owner: Any?) {
            lockAttempted.countDown()
            delegate.lock(owner)
        }
    }
}
