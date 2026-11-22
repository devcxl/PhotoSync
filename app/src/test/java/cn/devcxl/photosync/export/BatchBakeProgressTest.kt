package cn.devcxl.photosync.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [BatchBakeProgress] — the numbers and phase the top-right status card renders.
 */
class BatchBakeProgressTest {

    @Test
    fun shouldReportRunningStateAndRatio_whenSomePhotosAreDone() {
        val progress = BatchBakeProgress.from(done = 2, total = 5, running = true)

        assertEquals(BatchBakeState.RUNNING, progress.state)
        assertEquals(0.4f, progress.ratio, 1e-6f)
        assertFalse(progress.isFinished)
    }

    @Test
    fun shouldReportPreparingState_whenTotalIsStillUnknown() {
        val progress = BatchBakeProgress.from(done = 0, total = 0, running = true)

        assertEquals(BatchBakeState.PREPARING, progress.state)
        assertEquals(0.0f, progress.ratio, 1e-6f)
    }

    @Test
    fun shouldReportFinishedState_whenEveryPhotoWasProcessed() {
        val progress = BatchBakeProgress.from(done = 5, total = 5, running = false)

        assertEquals(BatchBakeState.FINISHED, progress.state)
        assertTrue(progress.isFinished)
        assertEquals(1.0f, progress.ratio, 1e-6f)
    }

    @Test
    fun shouldReportCancelled_whenQueueStoppedBeforeFinishing() {
        val progress = BatchBakeProgress.from(done = 2, total = 5, running = false)

        assertEquals(BatchBakeState.CANCELLED, progress.state)
        assertFalse(progress.isFinished)
    }

    @Test
    fun shouldClampRatio_whenCountersAreInconsistent() {
        assertEquals(1.0f, BatchBakeProgress.from(done = 9, total = 4, running = true).ratio, 1e-6f)
        assertEquals(0.0f, BatchBakeProgress.from(done = 0, total = 0, running = false).ratio, 1e-6f)
    }

    @Test
    fun shouldClampNegativeCounters_whenPayloadIsCorrupt() {
        val progress = BatchBakeProgress.from(done = -3, total = -1, running = true)

        assertEquals(0, progress.done)
        assertEquals(0, progress.total)
        assertEquals(BatchBakeState.PREPARING, progress.state)
    }

    @Test
    fun shouldReportFinished_whenSomePhotosFailedButAllProcessed() {
        val progress = BatchBakeProgress.from(done = 3, total = 5, failed = 2, running = false)

        assertEquals(BatchBakeState.FINISHED, progress.state)
        assertTrue(progress.isFinished)
        assertEquals(1.0f, progress.ratio, 1e-6f)
    }

    @Test
    fun shouldCarryCurrentPhotoName_whenEncodingOnePhoto() {
        val progress = BatchBakeProgress.from(
            done = 1,
            total = 3,
            running = true,
            currentName = "DSC04821.ARW"
        )

        assertEquals("DSC04821.ARW", progress.currentName)
    }
}
