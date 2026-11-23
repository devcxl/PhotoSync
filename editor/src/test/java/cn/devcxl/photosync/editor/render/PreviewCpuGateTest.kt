package cn.devcxl.photosync.editor.render

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.export.BakeConcurrencyGate

/**
 * Seam under test: [PreviewCpuGate] — CPU preview fallbacks must be bounded per format, a RAW preview
 * must yield to an export bake, and a JPEG preview must not be stalled by one.
 */
class PreviewCpuGateTest {

    @Test
    fun shouldRunOneJpegRenderAtATime() = runBlocking {
        val peak = measurePeakConcurrency(isRaw = false)

        assertEquals(1, peak)
    }

    @Test
    fun shouldRunOneRawRenderAtATime() = runBlocking {
        val peak = measurePeakConcurrency(isRaw = true)

        assertEquals(1, peak)
    }

    @Test
    fun shouldWaitForARunningBake_whenRenderingRawOnTheCpu() = runBlocking {
        val bakeHoldsGate = CompletableDeferred<Unit>()
        val releaseBake = CompletableDeferred<Unit>()
        val bake = launch {
            BakeConcurrencyGate.rawBakeMutex.withLock {
                bakeHoldsGate.complete(Unit)
                releaseBake.await()
            }
        }
        bakeHoldsGate.await()

        val previewStarted = AtomicBoolean(false)
        val rawPreview = launch {
            PreviewCpuGate.render(isRaw = true) { previewStarted.set(true) }
        }
        yield()
        delay(50)

        assertFalse("a RAW CPU preview must yield to the export bake", previewStarted.get())

        releaseBake.complete(Unit)
        rawPreview.join()
        bake.join()
        assertTrue(previewStarted.get())
    }

    @Test
    fun shouldNotBeStalledByABake_whenRenderingJpegOnTheCpu() = runBlocking {
        val bakeHoldsGate = CompletableDeferred<Unit>()
        val releaseBake = CompletableDeferred<Unit>()
        val bake = launch {
            BakeConcurrencyGate.rawBakeMutex.withLock {
                bakeHoldsGate.complete(Unit)
                releaseBake.await()
            }
        }
        bakeHoldsGate.await()

        val jpegStarted = CompletableDeferred<Unit>()
        val jpegPreview = launch {
            PreviewCpuGate.render(isRaw = false) { jpegStarted.complete(Unit) }
        }
        withTimeout(2_000) { jpegStarted.await() }

        releaseBake.complete(Unit)
        jpegPreview.join()
        bake.join()
    }

    /**
     * Runs four renders of one format and returns the highest number that overlapped.
     *
     * The 20ms hold makes an unbounded gate observable: without serialisation all four would overlap.
     */
    private suspend fun measurePeakConcurrency(isRaw: Boolean): Int = coroutineScope {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val jobs = List(RENDER_COUNT) {
            launch {
                PreviewCpuGate.render(isRaw = isRaw) {
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, now) }
                    delay(HOLD_MS)
                    inFlight.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        peak.get()
    }

    private companion object {
        const val RENDER_COUNT = 4
        const val HOLD_MS = 20L
    }
}
