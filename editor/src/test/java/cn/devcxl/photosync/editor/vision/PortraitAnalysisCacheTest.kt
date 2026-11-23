package cn.devcxl.photosync.editor.vision

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.BodyOps
import cn.devcxl.photosync.editor.domain.BodyWarpSolver

/**
 * Seam under test: [PortraitAnalysisCache] — the keying (source identity, model identity, delegate
 * environment), the single-entry policy, the mutex-serialized single analysis, the "failure and
 * identity drift are not cached" rules, the clear generation guard and the cancellation contract.
 *
 * The fake `produce` blocks on a [CountDownLatch], exactly like the real MediaPipe pass, so the
 * cancellation tests exercise the production timing: waiting is cancellable, a blocking pass is not.
 */
class PortraitAnalysisCacheTest {

    @Test
    fun shouldReuseTheAnalysis_whenKeyAndIdentityAreStable() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()

        val first = cache.get({ KEY }, { calls.incrementAndGet(); snapshot(1.0f) })
        val second = cache.get({ KEY }, { calls.incrementAndGet(); snapshot(2.0f) })

        assertNotNull(first)
        assertSame(first, second)
        assertEquals(1, calls.get())
    }

    @Test
    fun shouldReanalyse_whenAnyKeyedInputChanges() = runBlocking {
        val variants = listOf(
            KEY.copy(sourcePath = "/photos/other.jpg"),
            KEY.copy(sourceSizeBytes = KEY.sourceSizeBytes + 1L),
            KEY.copy(sourceLastModifiedMillis = KEY.sourceLastModifiedMillis + 1L),
            KEY.copy(isRaw = true),
            KEY.copy(model = KEY.model.copy(sizeBytes = 1_024L, lastModifiedMillis = 7L)),
            KEY.copy(model = KEY.model.copy(path = "/data/models/other-model.tflite")),
            KEY.copy(environment = KEY.environment.copy(delegate = "GPU")),
            KEY.copy(environment = KEY.environment.copy(analysisMaxEdgePx = 256)),
            KEY.copy(environment = KEY.environment.copy(pipelineProfile = "portrait-preview-analysis-v2"))
        )

        variants.forEach { variant ->
            val cache = PortraitAnalysisCache()
            val calls = AtomicInteger()
            cache.get({ KEY }, { calls.incrementAndGet(); snapshot(1.0f) })
            cache.get({ variant }, { calls.incrementAndGet(); snapshot(2.0f) })
            assertEquals("variant must miss: $variant", 2, calls.get())
        }
    }

    @Test
    fun shouldRebuildBodyGuidesPerCall_whenTheAnalysisComesFromCache() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()

        val first = cache.get({ KEY }, { calls.incrementAndGet(); snapshot(1.0f) })
        val second = cache.get({ KEY }, { calls.incrementAndGet(); snapshot(1.0f) })

        assertNotNull(first)
        assertSame(first, second)
        assertEquals(1, calls.get())

        val waistHeavy = first!!.toAnalysis(BodyOps(waistY = 0.30f, hipY = 0.40f))
        val hipHeavy = second!!.toAnalysis(BodyOps(waistY = 0.60f, hipY = 0.70f))
        assertEquals(0.30f, waistHeavy.guides.waistY, FLOAT_DELTA)
        assertEquals(0.40f, waistHeavy.guides.hipY, FLOAT_DELTA)
        assertEquals(0.60f, hipHeavy.guides.waistY, FLOAT_DELTA)
        assertEquals(0.70f, hipHeavy.guides.hipY, FLOAT_DELTA)
    }

    @Test
    fun shouldNotAnalyse_whenTheSourceHasNoStableIdentity() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()

        assertNull(cache.get({ null }, { calls.incrementAndGet(); snapshot(1.0f) }))
        assertEquals(0, calls.get())
    }

    @Test
    fun shouldNotCacheFailures_whenTheProducerReturnsNull() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()

        assertNull(cache.get({ KEY }, { calls.incrementAndGet(); null }))
        assertEquals(1, calls.get())

        val recovered = cache.get({ KEY }, { calls.incrementAndGet(); snapshot(3.0f) })
        assertNotNull(recovered)
        assertEquals(2, calls.get())
    }

    @Test
    fun shouldThrowAndNotCache_whenTheSourceChangesDuringTheAnalysis() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()
        val replaced = KEY.copy(
            sourceSizeBytes = KEY.sourceSizeBytes + 9L,
            sourceLastModifiedMillis = KEY.sourceLastModifiedMillis + 1L
        )
        var current: PortraitAnalysisKey? = KEY

        val error = expectDrift {
            cache.get(
                keyOf = { current },
                produce = {
                    calls.incrementAndGet()
                    current = replaced
                    snapshot(1.0f)
                }
            )
        }
        assertTrue("the photo moved, so the render must not be published", error.sourceChanged)
        assertFalse(error.modelChanged)
        assertFalse(error.environmentChanged)
        assertFalse(
            "the exception must not leak the source path: ${error.message}",
            error.message.orEmpty().contains("/photos")
        )
        assertFalse(error.message.orEmpty().contains(KEY.sourcePath))
        assertEquals(1, calls.get())

        // The stale pass was discarded; retrying analyses the replaced bytes and caches them.
        val retried = cache.get({ current }, { calls.incrementAndGet(); snapshot(2.0f) })
        assertNotNull(retried)
        assertEquals(2, calls.get())
        cache.get({ current }, { calls.incrementAndGet(); snapshot(3.0f) })
        assertEquals(2, calls.get())
    }

    @Test
    fun shouldThrow_whenTheSourceDisappearsDuringTheAnalysis() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()
        var current: PortraitAnalysisKey? = KEY

        val error = expectDrift {
            cache.get(
                keyOf = { current },
                produce = {
                    calls.incrementAndGet()
                    current = null
                    snapshot(1.0f)
                }
            )
        }
        assertTrue(error.sourceChanged)
        assertFalse(error.modelChanged)
        assertFalse(error.environmentChanged)
        assertEquals(1, calls.get())
    }

    @Test
    fun shouldThrow_whenTheModelChangesDuringTheAnalysis() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()
        val withModel = KEY.copy(
            model = KEY.model.copy(sizeBytes = 16_371_837L, lastModifiedMillis = 42L)
        )
        var current: PortraitAnalysisKey? = KEY

        val error = expectDrift {
            cache.get(
                keyOf = { current },
                produce = {
                    calls.incrementAndGet()
                    current = withModel
                    // The pass finished against the offline person mask while the six-class model
                    // appeared; returning it would mix the two mask sources for this photo.
                    snapshot(1.0f)
                }
            )
        }
        assertFalse(error.sourceChanged)
        assertTrue("a finished model download must fail the render, not be mixed in", error.modelChanged)
        assertFalse(error.environmentChanged)
        assertEquals(1, calls.get())
    }

    @Test
    fun shouldThrow_whenOnlyTheEnvironmentChangesDuringTheAnalysis() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()
        val onGpu = KEY.copy(environment = KEY.environment.copy(delegate = "GPU"))
        var current: PortraitAnalysisKey? = KEY

        val error = expectDrift {
            cache.get(
                keyOf = { current },
                produce = {
                    calls.incrementAndGet()
                    current = onGpu
                    snapshot(1.0f)
                }
            )
        }
        assertFalse(error.sourceChanged)
        assertFalse(error.modelChanged)
        assertTrue(error.environmentChanged)
        assertEquals(1, calls.get())
    }

    @Test
    fun shouldKeepOnlyOneEntry_whenAnotherPhotoIsRequested() = runBlocking {
        val cache = PortraitAnalysisCache()
        val firstCalls = AtomicInteger()
        val secondCalls = AtomicInteger()
        val first = KEY.copy(sourcePath = "/photos/first.jpg")
        val second = KEY.copy(sourcePath = "/photos/second.jpg")

        cache.get({ first }, { firstCalls.incrementAndGet(); snapshot(1.0f) })
        cache.get({ second }, { secondCalls.incrementAndGet(); snapshot(2.0f) })
        cache.get({ first }, { firstCalls.incrementAndGet(); snapshot(3.0f) })

        assertEquals(2, firstCalls.get())
        assertEquals(1, secondCalls.get())
    }

    @Test
    fun shouldRunOneAnalysisAtATime_whenCallersRaceOnDifferentKeys() = runBlocking {
        val cache = PortraitAnalysisCache()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val first = KEY.copy(sourcePath = "/photos/first.jpg")
        val second = KEY.copy(sourcePath = "/photos/second.jpg")

        val leader = async(Dispatchers.Default) {
            cache.get(
                keyOf = { first },
                produce = {
                    calls.incrementAndGet()
                    peak.accumulateAndGet(running.incrementAndGet()) { left, right -> maxOf(left, right) }
                    started.countDown()
                    assertTrue(gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    running.decrementAndGet()
                    snapshot(1.0f)
                }
            )
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val follower = async(Dispatchers.Default) {
            cache.get(
                keyOf = { second },
                produce = {
                    calls.incrementAndGet()
                    peak.accumulateAndGet(running.incrementAndGet()) { left, right -> maxOf(left, right) }
                    running.decrementAndGet()
                    snapshot(2.0f)
                }
            )
        }

        gate.countDown()
        withTimeout(TIMEOUT_MILLIS) {
            assertNotNull(leader.await())
            assertNotNull(follower.await())
        }
        assertEquals(2, calls.get())
        assertEquals("two analyses must never overlap", 1, peak.get())
    }

    @Test
    fun shouldCancelTheWaitingCaller_whenAnotherAnalysisIsRunning() = runBlocking {
        val cache = PortraitAnalysisCache()
        val leaderCalls = AtomicInteger()
        val waiterCalls = AtomicInteger()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)

        val leader = async(Dispatchers.Default) {
            cache.get(
                keyOf = { KEY },
                produce = {
                    leaderCalls.incrementAndGet()
                    started.countDown()
                    assertTrue(gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    snapshot(1.0f)
                }
            )
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val waiter = async(Dispatchers.Default) {
            cache.get({ KEY }, { waiterCalls.incrementAndGet(); snapshot(2.0f) })
        }
        // Let the waiter park on the mutex before it is cancelled.
        delay(SETTLE_MILLIS)
        assertFalse("the waiter must still be parked on the analysis mutex", waiter.isCompleted)

        waiter.cancel()
        withTimeout(TIMEOUT_MILLIS) { waiter.join() }
        assertTrue("a cancelled waiter must not stay parked on the mutex", waiter.isCancelled)
        assertEquals(0, waiterCalls.get())

        gate.countDown()
        withTimeout(TIMEOUT_MILLIS) { assertNotNull(leader.await()) }
        assertEquals(1, leaderCalls.get())
    }

    @Test
    fun shouldReleaseTheLockAndStoreNothing_whenTheCallerIsCancelledDuringTheAnalysis() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)

        val job = launch(Dispatchers.Default) {
            cache.get(
                keyOf = { KEY },
                produce = {
                    calls.incrementAndGet()
                    started.countDown()
                    // A blocking pass cannot observe the cancellation; only the re-check after it.
                    assertTrue(gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    snapshot(1.0f)
                }
            )
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        job.cancel()
        gate.countDown()

        withTimeout(TIMEOUT_MILLIS) { job.join() }
        assertTrue("the cancellation must survive the blocking pass", job.isCancelled)

        // Nothing was published, so a later caller analyses again instead of reading a stale entry.
        val recovered = withTimeout(TIMEOUT_MILLIS) {
            cache.get({ KEY }, { calls.incrementAndGet(); snapshot(2.0f) })
        }
        assertNotNull(recovered)
        assertEquals(2, calls.get())
    }

    @Test
    fun shouldNotRepopulateTheCache_whenClearedWhileTheAnalysisRuns() = runBlocking {
        val cache = PortraitAnalysisCache()
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)

        val analysis = async(Dispatchers.Default) {
            cache.get(
                keyOf = { KEY },
                produce = {
                    calls.incrementAndGet()
                    started.countDown()
                    assertTrue(gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    snapshot(1.0f)
                }
            )
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        // Must not wait for the in-flight analysis.
        cache.clear()
        gate.countDown()

        withTimeout(TIMEOUT_MILLIS) { assertNotNull(analysis.await()) }
        assertNotNull(cache.get({ KEY }, { calls.incrementAndGet(); snapshot(2.0f) }))
        assertEquals(2, calls.get())
    }

    @Test
    fun shouldClearTheSharedCache_whenTheFacadeIsCleared() = runBlocking {
        val calls = AtomicInteger()
        try {
            PortraitPreviewCache.analysis({ KEY }, { calls.incrementAndGet(); snapshot(1.0f) })
            PortraitPreviewCache.analysis({ KEY }, { calls.incrementAndGet(); snapshot(1.0f) })
            assertEquals(1, calls.get())

            PortraitPreviewCache.clear()

            PortraitPreviewCache.analysis({ KEY }, { calls.incrementAndGet(); snapshot(1.0f) })
            assertEquals(2, calls.get())
        } finally {
            PortraitPreviewCache.clear()
        }
    }

    @Test
    fun shouldTrackPresenceAndStampOfTheModelFile() {
        val directory = Files.createTempDirectory("portrait-model-identity").toFile()
        try {
            val model = File(directory, "selfie_multiclass_256x256.tflite")
            val absent = PortraitModelIdentity.of(model)
            assertFalse(absent.isPresent)
            assertEquals(PortraitModelIdentity.ABSENT_SIZE, absent.sizeBytes)

            model.writeBytes(ByteArray(64) { 7 })
            val present = PortraitModelIdentity.of(model)
            assertTrue(present.isPresent)
            assertEquals(model.absolutePath, present.path)
            assertEquals(64L, present.sizeBytes)
            assertEquals(model.lastModified(), present.lastModifiedMillis)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Runs [block] and asserts it fails with a path-free [PortraitIdentityDriftException]. */
    private suspend fun expectDrift(block: suspend () -> Unit): PortraitIdentityDriftException {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(
            "expected a PortraitIdentityDriftException, got $error",
            error is PortraitIdentityDriftException
        )
        return error as PortraitIdentityDriftException
    }

    private fun snapshot(marker: Float): PortraitAnalysisSnapshot = PortraitAnalysisSnapshot(
        faces = emptyList(),
        skinMask = FloatArray(MASK_CELLS) { marker },
        personMask = null,
        usesMulticlass = false
    )

    private companion object {
        private const val FLOAT_DELTA = 1e-6f
        private const val TIMEOUT_MILLIS = 5_000L
        private const val TIMEOUT_SECONDS = 5L
        private const val SETTLE_MILLIS = 100L
        private val MASK_CELLS = BodyWarpSolver.MASK_SIZE * BodyWarpSolver.MASK_SIZE

        private val KEY = PortraitAnalysisKey(
            sourcePath = "/photos/IMG_0001.jpg",
            sourceSizeBytes = 8_192L,
            sourceLastModifiedMillis = 1_700_000_000_000L,
            isRaw = false,
            model = PortraitModelIdentity(
                path = "/data/models/selfie_multiclass_256x256.tflite",
                sizeBytes = PortraitModelIdentity.ABSENT_SIZE,
                lastModifiedMillis = PortraitModelIdentity.ABSENT_SIZE
            ),
            environment = PortraitAnalysisEnvironment(
                analysisMaxEdgePx = 512,
                delegate = "CPU",
                pipelineProfile = "portrait-preview-analysis-v1"
            )
        )
    }
}
