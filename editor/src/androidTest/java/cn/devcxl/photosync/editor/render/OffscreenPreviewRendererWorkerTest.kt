package cn.devcxl.photosync.editor.render

import java.io.File

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import cn.devcxl.photosync.editor.domain.DetailOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ToneOps

import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.acquireLease
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.assertQuadrants
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.createQuadrantJpeg
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.loadFrame
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.newWorkspace
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.previewRequest
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.releaseCachedSources
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.renderOnWorker
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.requireHeadlessGpu
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.requireRendered
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.resetGpuRenderer

/**
 * End-to-end coverage of the asynchronous preview API
 * ([OffscreenPreviewRenderer.render], the serial worker and the source lease handoff).
 *
 * The synchronous pixel suite lives in [OffscreenPreviewRendererOrientationTest] and
 * [OffscreenPreviewRendererGraphTest]; this class covers the production path that
 * `BackgroundPreviewRenderer` uses for presets and live tethering: a leased source, a queued job on
 * the dedicated worker thread, and exactly one published file.
 *
 * ## Device requirement
 * A headless EGL context on GLES 3.x, probed independently by [HeadlessGlProbe]. Only a device below
 * that requirement skips; once the probe passes a `null` worker result fails the named request.
 *
 * ## Timeout coverage
 * The renderer's 20 s watchdog has no injection point, and forcing a real overrun would require a
 * production change. [shouldAbandonCancelledJobWithoutPublishing_whenTheCallerCancels] exercises the same
 * caller-goes-away path the timeout uses: the job is abandoned, no file is published and the serial
 * worker stays usable. If the renderer gains a testable timeout hook, add a direct overrun case
 * here.
 *
 * File-handoff success/late-discard semantics are covered at unit level by
 * `PreviewResultHandoffTest`; here the same contract is asserted through a real worker render and a
 * real cancellation.
 */
@RunWith(AndroidJUnit4::class)
class OffscreenPreviewRendererWorkerTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = newWorkspace("worker")
        resetGpuRenderer()
    }

    @After
    fun tearDown() {
        resetGpuRenderer()
        releaseCachedSources()
        workDir.deleteRecursively()
    }

    @Test
    fun shouldPublishUprightPreview_whenTheWorkerRendersALeasedSource() {
        runBlocking {
            val capabilities = requireHeadlessGpu()
            val path = createQuadrantJpeg(workDir, "worker.jpg").absolutePath
            val outDir = File(workDir, "worker-success")
            val lease = acquireLease(path)

            val file = requireRendered(
                "queued identity render",
                capabilities,
                renderOnWorker(previewRequest(lease.source, EditGraph(), outDir), lease)
            )

            assertTrue("The worker must publish a real file", file.isFile)
            assertEquals(listOf(file), outDir.listFiles()?.toList())
            assertQuadrants("worker identity preview", loadFrame(file))
        }
    }

    @Test
    fun shouldSerializeAndHandOffDistinctFiles_whenTwoWorkerRequestsQueueConcurrently() {
        runBlocking {
            val capabilities = requireHeadlessGpu()
            val path = createQuadrantJpeg(workDir, "worker-concurrent.jpg").absolutePath
            val graph = EditGraph(tone = ToneOps(exposure = 0.2f, contrast = 10))

            // Acquisition happens before the async bodies so cancellation/queueing cannot hide a
            // decode failure inside the worker request.
            val leaseA = acquireLease(path)
            val leaseB = acquireLease(path)
            val firstDir = File(workDir, "worker-a")
            val secondDir = File(workDir, "worker-b")

            val first = async(Dispatchers.Default) {
                requireRendered(
                    "queued render A",
                    capabilities,
                    renderOnWorker(previewRequest(leaseA.source, graph, firstDir), leaseA)
                )
            }
            val second = async(Dispatchers.Default) {
                requireRendered(
                    "queued render B",
                    capabilities,
                    renderOnWorker(previewRequest(leaseB.source, graph, secondDir), leaseB)
                )
            }
            val firstFile = first.await()
            val secondFile = second.await()

            // The single worker runs them one after the other, so both must have produced their own
            // complete file with no cross-talk between the two leases.
            assertNotEquals("Each job must publish its own file", firstFile, secondFile)
            assertTrue(firstFile.isFile)
            assertTrue(secondFile.isFile)
            assertEquals(listOf(firstFile), firstDir.listFiles()?.toList())
            assertEquals(listOf(secondFile), secondDir.listFiles()?.toList())
            assertQuadrants("queued render A", loadFrame(firstFile))
            assertQuadrants("queued render B", loadFrame(secondFile))
        }
    }

    @Test
    fun shouldAbandonCancelledJobWithoutPublishing_whenTheCallerCancels() {
        runBlocking {
            val capabilities = requireHeadlessGpu()
            val path = createQuadrantJpeg(workDir, "worker-cancelled.jpg").absolutePath
            val graph = EditGraph(
                tone = ToneOps(exposure = 0.3f, contrast = 12),
                detail = DetailOps(clarity = 30, dehaze = 8)
            )
            val cancelledDir = File(workDir, "worker-cancelled")
            val cancelledLease = acquireLease(path)

            // UNDISPATCHED runs the body until the renderer suspends on the job, so cancelling here
            // exercises the real "caller went away while the worker owns the job" path.
            val pending = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                renderOnWorker(
                    previewRequest(cancelledLease.source, graph, cancelledDir),
                    cancelledLease
                )
            }
            pending.cancel()

            val failure = try {
                pending.await()
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }
            assertNotNull("A cancelled worker render must surface as coroutine cancellation", failure)
            assertTrue(
                "A cancelled caller must not count as a GPU capability failure",
                OffscreenPreviewRenderer.isEnabled
            )

            // A follow-up render is queued behind the abandoned job; its completion proves the
            // worker passed the cancellation checkpoint instead of wedging on it.
            val followUpLease = acquireLease(path)
            val followUp = requireRendered(
                "render after cancellation",
                capabilities,
                renderOnWorker(
                    previewRequest(followUpLease.source, graph, File(workDir, "worker-after-cancel")),
                    followUpLease
                )
            )
            assertQuadrants("render after cancellation", loadFrame(followUp))
            assertTrue(
                "A cancelled job must not leave a published preview behind",
                cancelledDir.listFiles().isNullOrEmpty()
            )

            // The abandoned job and the follow-up both closed their leases, so dropping the cache's
            // own reference must recycle the decoded pixels: a leaked lease keeps them alive.
            releaseCachedSources()
            val recycled = (cancelledLease.source as? EditorSource.BitmapSource)?.full?.isRecycled
            assertTrue(
                "Cancelled and completed jobs must release their source leases, but the decoded " +
                    "bitmap was still alive after the cache dropped its reference",
                recycled == true
            )
        }
    }
}
