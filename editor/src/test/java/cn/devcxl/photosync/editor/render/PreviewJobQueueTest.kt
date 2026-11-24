package cn.devcxl.photosync.editor.render

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PreviewJobQueue] and [PreviewJob] — the ownership and backpressure rules that make
 * a timed-out or cancelled GPU preview safe for the caller's source pixels.
 */
class PreviewJobQueueTest {

    @Test
    fun shouldReturnJobsInOfferOrder_whenConsumed() {
        val queue = PreviewJobQueue<String>(capacity = 2)

        assertTrue(queue.offer("first"))
        assertTrue(queue.offer("second"))

        assertEquals("first", queue.take(0))
        assertEquals("second", queue.take(0))
    }

    @Test
    fun shouldRejectOffer_whenTheBacklogIsFull() {
        val queue = PreviewJobQueue<String>(capacity = 1)

        assertTrue(queue.offer("first"))
        assertFalse("a full queue must reject instead of growing", queue.offer("second"))
    }

    @Test
    fun shouldRejectOffer_whenTheQueueWasStopped() {
        val queue = PreviewJobQueue<String>(capacity = 2)
        queue.stop()

        assertFalse(queue.offer("late"))
        assertTrue(queue.isStopped)
    }

    @Test
    fun shouldDrainEveryWaitingJob_whenStopped() {
        val queue = PreviewJobQueue<String>(capacity = 2)
        queue.offer("first")
        queue.offer("second")

        val drained = queue.stop()

        assertEquals(listOf("first", "second"), drained)
        assertNull("a stopped, drained queue must not hand out work", queue.take(0))
        assertTrue("stop is idempotent", queue.stop().isEmpty())
    }

    @Test
    fun shouldStopTheQueue_whenTakeFindsItEmpty() {
        val queue = PreviewJobQueue<String>(capacity = 2)

        assertNull(queue.take(0))
        assertTrue(queue.isStopped)
    }

    @Test
    fun shouldRemoveAJobThatNeverStarted() {
        val queue = PreviewJobQueue<String>(capacity = 2)
        queue.offer("first")
        queue.offer("second")

        assertTrue(queue.remove("first"))
        assertFalse("a removed job is gone", queue.remove("first"))
        assertEquals("second", queue.take(0))
    }

    @Test
    fun shouldDeliverTheResultAndReleaseTheSourceOnce_whenCompletedTwice() {
        var deliveries = 0
        var releases = 0
        var delivered: File? = null
        val job = PreviewJob(
            request = "request",
            releaseSource = { releases++ },
            onCompleted = { deliveries++; delivered = it }
        )

        job.complete(null)
        job.complete(null)

        assertEquals(1, deliveries)
        assertEquals(1, releases)
        assertNull(delivered)
        assertTrue(job.isCompleted)
    }

    @Test
    fun shouldReleaseTheSourceWithoutAResult_whenRejectedBeforeExecution() {
        var releases = 0
        val job = PreviewJob(request = "request", releaseSource = { releases++ })

        // The dispatcher owns a rejected job and finishes it itself.
        job.complete(null)

        assertEquals(1, releases)
        assertFalse("a rejected job never started", job.hasStarted)
        assertFalse("rejection is congestion, not a GPU failure", job.isFailed)
    }

    @Test
    fun shouldReportStartedAndFailed_whenTheWorkerSaysSo() {
        val job = PreviewJob(request = "request")

        job.markStarted()
        job.markFailed()
        job.complete(null)

        assertTrue(job.hasStarted)
        assertTrue(job.isFailed)
        assertTrue(job.isCompleted)
    }

    @Test
    fun shouldKeepTheCancellationFlagWithoutFinishingTheJob_whenCancelled() {
        var releases = 0
        val job = PreviewJob(request = "request", releaseSource = { releases++ })

        job.cancel()

        assertTrue(job.isCancelled)
        assertFalse("cancelling must not release the source of a possibly running job", job.isCompleted)
        assertEquals(0, releases)

        job.complete(null)
        assertEquals(1, releases)
    }
}
