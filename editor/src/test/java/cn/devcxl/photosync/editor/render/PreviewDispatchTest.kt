package cn.devcxl.photosync.editor.render

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Seam under test: [dispatchPreviewJob] — the hand-over contract between the caller's coroutine and
 * the preview worker. It must never leak the source, never resume twice, and never enqueue a job its
 * waiter already abandoned.
 */
class PreviewDispatchTest {

    @Test
    fun shouldDeliverNullAndReleaseTheSourceOnce_whenTheQueueRejectsTheJob() = runBlocking {
        val sink = RecordingSink(accept = false)
        var releases = 0
        val continuationRef = AtomicReference<CancellableContinuation<File?>?>(null)
        val job = previewJob(releases = { releases++ }, continuationRef)

        val written = withTimeoutOrNull(TIMEOUT_MS) {
            dispatchPreviewJob(job, sink, continuationRef)
        }

        // A duplicate resume would surface as an IllegalStateException instead of `null`.
        assertNull(written)
        assertEquals("the rejected job releases its source exactly once", 1, releases)
        assertEquals(1, sink.offers)
        assertEquals(0, sink.removes)
        assertTrue(job.isCompleted)
    }

    @Test
    fun shouldDeliverTheFileAndReleaseTheSourceOnce_whenTheWorkerCompletesTheJob() = runBlocking {
        val sink = RecordingSink(accept = true)
        val file = temporaryFile()
        var releases = 0
        val continuationRef = AtomicReference<CancellableContinuation<File?>?>(null)
        val job = previewJob(releases = { releases++ }, continuationRef)

        val waiter = async {
            withTimeoutOrNull(TIMEOUT_MS) { dispatchPreviewJob(job, sink, continuationRef) }
        }
        while (sink.offers == 0) yield()

        job.complete(file)

        assertEquals(file, waiter.await())
        assertEquals(1, releases)
        assertTrue("a delivered result belongs to the caller", file.exists())
    }

    @Test
    fun shouldTakeTheJobBackAndReleaseTheSource_whenTheCallerIsCancelledWhileWaiting() = runBlocking {
        val sink = RecordingSink(accept = true)
        var releases = 0
        val continuationRef = AtomicReference<CancellableContinuation<File?>?>(null)
        val job = previewJob(releases = { releases++ }, continuationRef)

        val waiter = launch { dispatchPreviewJob(job, sink, continuationRef) }
        while (sink.offers == 0) yield()
        waiter.cancelAndJoin()

        assertEquals("the worker never started it", 1, sink.removes)
        assertEquals("the cancellation path releases the source exactly once", 1, releases)
        assertTrue(job.isCompleted)
        // Taken back from the queue: nothing carried the cancellation, so the job is simply finished.
        assertTrue(job.isCancelled.not())
    }

    @Test
    fun shouldNotEnqueueAnything_whenTheJobWasAbandonedBeforeTheHandOver() = runBlocking {
        val sink = RecordingSink(accept = true)
        var releases = 0
        val continuationRef = AtomicReference<CancellableContinuation<File?>?>(null)
        val job = previewJob(releases = { releases++ }, continuationRef)
        job.cancel()

        val written = withTimeoutOrNull(TIMEOUT_MS) {
            dispatchPreviewJob(job, sink, continuationRef)
        }

        assertNull(written)
        assertEquals("a dead job must not occupy a queue slot", 0, sink.offers)
        assertEquals(1, releases)
    }

    @Test
    fun shouldPropagateCancellation_whenTheCallerIsCancelledWhileWaiting() = runBlocking {
        val sink = RecordingSink(accept = true)
        val continuationRef = AtomicReference<CancellableContinuation<File?>?>(null)
        val job = previewJob(releases = {}, continuationRef)

        var cancelled = false
        val waiter = launch {
            try {
                dispatchPreviewJob(job, sink, continuationRef)
            } catch (expected: CancellationException) {
                cancelled = true
                throw expected
            }
        }
        while (sink.offers == 0) yield()
        waiter.cancelAndJoin()

        assertTrue("user cancellation must not be converted into a result", cancelled)
        assertTrue(job.isCompleted)
    }

    private fun previewJob(
        releases: () -> Unit,
        continuationRef: AtomicReference<CancellableContinuation<File?>?>
    ): PreviewJob<OffscreenPreviewRequest> = PreviewJob(
        request = OffscreenPreviewRequest(
            source = linearSource(),
            graph = EditGraph(),
            outDir = File("build/tmp/preview-dispatch-test")
        ),
        releaseSource = releases,
        onCompleted = { file -> handOffPreviewFile(file, continuationRef.get()) }
    )

    private fun linearSource(): EditorSource.LinearRawSource {
        val buffer = LinearRawBuffer(
            width = 2,
            height = 2,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgba16fBuffer = ByteBuffer.allocateDirect(2 * 2 * 8).order(ByteOrder.nativeOrder())
        )
        return EditorSource.LinearRawSource(full = buffer, proxy = buffer)
    }

    private fun temporaryFile(): File = File.createTempFile("preview_", ".jpg").apply {
        deleteOnExit()
        writeText("frame")
    }

    /** In-memory stand-in for the worker queue. */
    private class RecordingSink(private val accept: Boolean) : PreviewJobSink {

        private val queued = CopyOnWriteArraySet<PreviewJob<OffscreenPreviewRequest>>()

        var offers: Int = 0
            private set
        var removes: Int = 0
            private set

        override fun offer(job: PreviewJob<OffscreenPreviewRequest>): Boolean {
            offers++
            return if (accept) queued.add(job) else false
        }

        override fun remove(job: PreviewJob<OffscreenPreviewRequest>): Boolean {
            removes++
            return queued.remove(job)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
