package cn.devcxl.photosync.adapter

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SsivTaskTrackerFailureTest {

    @Test
    fun shouldPostSentinelAndPropagate_whenTaskThrows() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val boom = IllegalStateException("decode failed")
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.execute(Runnable { throw boom })

        val thrown = assertThrows(IllegalStateException::class.java) { worker.runNext() }

        assertSame(boom, thrown)
        assertEquals("sentinel must still be posted after a task failure", 1, main.size)
        assertEquals(1, tracker.outstandingTaskCount)

        tracker.retire()

        assertEquals(0, drains.get())

        main.runNext()

        assertEquals(0, tracker.outstandingTaskCount)
        assertTrue(tracker.isDrained)
        assertEquals(1, drains.get())
    }

    @Test
    fun shouldNotRollbackCount_whenInlineExecutorRunsAndTaskThrows() {
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(
            backgroundExecutor = Executor { it.run() },
            postToMain = {
                it.run()
                true
            },
            onDrained = { drains.incrementAndGet() }
        )

        assertThrows(IllegalStateException::class.java) {
            tracker.execute(Runnable { throw IllegalStateException("boom") })
        }

        assertEquals("inline failure must not roll back an already settled task", 0, tracker.outstandingTaskCount)

        tracker.retire()

        assertEquals(1, drains.get())
    }

    @Test
    fun shouldRollbackCountAndPropagate_whenBackgroundExecutorRejects() {
        val rejection = RejectedExecutionException("queue full")
        val rejecting = Executor { throw rejection }
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(rejecting, { true }) { drains.incrementAndGet() }

        val thrown = assertThrows(RejectedExecutionException::class.java) {
            tracker.execute(Runnable { })
        }

        assertSame(rejection, thrown)
        assertEquals(0, tracker.outstandingTaskCount)

        tracker.retire()

        assertTrue(tracker.isDrained)
        assertEquals(1, drains.get())
    }

    @Test
    fun shouldRecordPostFailure_andRetainTask_whenPostReturnsFalse() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(worker, { false }) { drains.incrementAndGet() }

        tracker.execute(Runnable { })

        worker.runNext()

        assertNotNull(tracker.postFailure)
        assertEquals("failed post must not settle the task", 1, tracker.outstandingTaskCount)
        assertEquals(0, main.size)
        assertEquals(0, drains.get())

        tracker.retire()

        assertFalse(tracker.isDrained)
        assertEquals(0, drains.get())

        // The tracker never drained, so it still accepts derived work rather than rejecting it.
        tracker.execute(Runnable { })

        assertEquals(2, tracker.outstandingTaskCount)
    }

    @Test
    fun shouldRecordPostFailure_andRetainTask_whenPostThrows() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val postFailure = IllegalStateException("looper gone")
        val tracker = SsivTaskTracker(worker, { throw postFailure }) { drains.incrementAndGet() }

        tracker.execute(Runnable { })

        worker.runNext()

        assertSame(postFailure, tracker.postFailure)
        assertEquals(1, tracker.outstandingTaskCount)
        assertEquals(0, main.size)
        assertEquals(0, drains.get())

        tracker.retire()

        assertFalse(tracker.isDrained)
        assertEquals(0, drains.get())
    }

    @Test
    fun shouldKeepTaskFailureVisible_whenPostAlsoFails() {
        val worker = TrackerManualExecutor()
        val drains = AtomicInteger()
        val taskFailure = IllegalStateException("decode failed")
        val postFailure = IllegalStateException("looper gone")
        val tracker = SsivTaskTracker(worker, { throw postFailure }) { drains.incrementAndGet() }

        tracker.execute(Runnable { throw taskFailure })

        val thrown = assertThrows(IllegalStateException::class.java) { worker.runNext() }

        assertSame(taskFailure, thrown)
        assertSame(postFailure, tracker.postFailure)
        assertEquals(1, thrown.suppressed.size)
        assertSame(postFailure, thrown.suppressed.single())
        assertEquals("post failure must retain the task", 1, tracker.outstandingTaskCount)

        tracker.retire()

        assertFalse(tracker.isDrained)
        assertEquals(0, drains.get())
    }

    @Test
    fun shouldNotSelfSuppress_whenTaskAndPostFailWithSameThrowable() {
        val worker = TrackerManualExecutor()
        val drains = AtomicInteger()
        val observed = mutableListOf<Throwable>()
        val sharedFailure = IllegalStateException("shared failure")
        val tracker = SsivTaskTracker(
            backgroundExecutor = worker,
            postToMain = { throw sharedFailure },
            onPostFailure = { observed += it },
            onDrained = { drains.incrementAndGet() }
        )

        tracker.execute(Runnable { throw sharedFailure })

        val thrown = assertThrows(IllegalStateException::class.java) { worker.runNext() }

        assertSame(sharedFailure, thrown)
        assertEquals("a throwable must never be self-suppressed", 0, thrown.suppressed.size)
        assertSame(sharedFailure, tracker.postFailure)
        assertEquals(1, observed.size)
        assertSame(sharedFailure, observed.single())
        assertEquals(1, tracker.outstandingTaskCount)

        tracker.retire()

        assertFalse(tracker.isDrained)
        assertEquals(0, drains.get())
    }

    @Test
    fun shouldNotifyPostFailureObserver_whenPostFails() {
        val worker = TrackerManualExecutor()
        val drains = AtomicInteger()
        val observed = mutableListOf<Throwable>()
        val postFailure = IllegalStateException("looper gone")
        val tracker = SsivTaskTracker(
            backgroundExecutor = worker,
            postToMain = { throw postFailure },
            onDrained = { drains.incrementAndGet() },
            onPostFailure = { observed += it }
        )

        tracker.execute(Runnable { })

        worker.runNext()

        assertEquals(1, observed.size)
        assertSame(postFailure, observed.single())
        assertSame(postFailure, tracker.postFailure)
        assertEquals(1, tracker.outstandingTaskCount)

        tracker.retire()

        assertFalse(tracker.isDrained)
        assertEquals(0, drains.get())
    }

    @Test
    fun shouldNotLetPostFailureObserverExceptionMaskTaskFailure() {
        val worker = TrackerManualExecutor()
        val drains = AtomicInteger()
        val taskFailure = IllegalStateException("decode failed")
        val postFailure = IllegalStateException("looper gone")
        val observerFailure = IllegalArgumentException("logger failed")
        val tracker = SsivTaskTracker(
            backgroundExecutor = worker,
            postToMain = { throw postFailure },
            onDrained = { drains.incrementAndGet() },
            onPostFailure = { throw observerFailure }
        )

        tracker.execute(Runnable { throw taskFailure })

        val thrown = assertThrows(IllegalStateException::class.java) { worker.runNext() }

        assertSame(taskFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(postFailure, thrown.suppressed.single())
        assertEquals(1, postFailure.suppressed.size)
        assertSame(observerFailure, postFailure.suppressed.single())
        assertEquals("observer failure must not settle the task", 1, tracker.outstandingTaskCount)

        tracker.retire()

        assertFalse(tracker.isDrained)
        assertEquals(0, drains.get())
    }

    @Test
    fun shouldContainObserverException_whenOnlyPostFails() {
        val worker = TrackerManualExecutor()
        val drains = AtomicInteger()
        val postFailure = IllegalStateException("looper gone")
        val observerFailure = IllegalArgumentException("logger failed")
        val tracker = SsivTaskTracker(
            backgroundExecutor = worker,
            postToMain = { throw postFailure },
            onDrained = { drains.incrementAndGet() },
            onPostFailure = { throw observerFailure }
        )

        tracker.execute(Runnable { })

        worker.runNext()

        assertSame(postFailure, tracker.postFailure)
        assertSame(observerFailure, postFailure.suppressed.single())
        assertEquals(1, tracker.outstandingTaskCount)
        assertEquals(0, drains.get())
    }
}
