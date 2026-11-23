package cn.devcxl.photosync.editor.render

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Seam under test: [PreviewFailurePolicy] — preparation failures must restore the CPU-era contract
 * (log and fall back) instead of crashing the caller, and the not-yet-transferred lease must always be
 * released exactly once.
 */
class PreviewFailurePolicyTest {

    @Test
    fun shouldReturnThePreparedRequestAndKeepTheLease_whenPreparationSucceeds() = runBlocking {
        val closes = AtomicInteger()
        val lease = countingLease(closes)

        val prepared = PreviewFailurePolicy.prepareOrRelease(lease) { "request" }

        assertEquals("request", prepared)
        assertEquals("a successful preparation transfers the lease", 0, closes.get())
    }

    @Test
    fun shouldReturnNullAndReleaseTheLease_whenPreparationRunsOutOfMemory() = runBlocking {
        val closes = AtomicInteger()

        val prepared = PreviewFailurePolicy.prepareOrRelease(countingLease(closes)) {
            throw OutOfMemoryError("no pixels for you")
        }

        assertNull(prepared)
        assertEquals(1, closes.get())
    }

    @Test
    fun shouldReturnNullAndReleaseTheLease_whenPreparationFailsOnIo() = runBlocking {
        val closes = AtomicInteger()

        val prepared = PreviewFailurePolicy.prepareOrRelease(countingLease(closes)) {
            throw IOException("lut unreadable")
        }

        assertNull(prepared)
        assertEquals(1, closes.get())
    }

    @Test
    fun shouldReturnNullAndReleaseTheLease_whenPreparationFailsUnexpectedly() = runBlocking {
        val closes = AtomicInteger()

        val prepared = PreviewFailurePolicy.prepareOrRelease(countingLease(closes)) {
            throw IllegalStateException("MediaPipe delegate refused the image")
        }

        assertNull(prepared)
        assertEquals(1, closes.get())
    }

    @Test
    fun shouldPropagateCancellationAndReleaseTheLease_whenTheCallerWasCancelled() = runBlocking {
        val closes = AtomicInteger()

        try {
            PreviewFailurePolicy.prepareOrRelease(countingLease(closes)) {
                throw CancellationException("user left")
            }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals(1, closes.get())
        }
    }

    @Test
    fun shouldPropagateUnexpectedErrorsAndReleaseTheLease_whenPreparationBreaks() = runBlocking {
        val closes = AtomicInteger()

        try {
            PreviewFailurePolicy.prepareOrRelease(countingLease(closes)) {
                throw AssertionError("driver misbehaved")
            }
            fail("an unexpected Error must propagate")
        } catch (expected: AssertionError) {
            assertEquals(1, closes.get())
        }
    }

    private fun countingLease(closes: AtomicInteger): AutoCloseable = AutoCloseable { closes.incrementAndGet() }
}
