package cn.devcxl.photosync.adapter

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SsivTaskTrackerLifecycleTest {

    @Test
    fun shouldCountTaskOnSubmit_andHoldItUntilMainSentinelRuns() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.execute(Runnable { })

        assertEquals(1, tracker.outstandingTaskCount)
        assertEquals(1, worker.size)
        assertEquals(0, main.size)

        worker.runNext()

        assertEquals("count must survive until the main sentinel runs", 1, tracker.outstandingTaskCount)
        assertEquals(1, main.size)
        assertEquals(0, drains.get())

        main.runNext()

        assertEquals(0, tracker.outstandingTaskCount)
        assertEquals(0, drains.get())
    }

    @Test
    fun shouldPostSentinelOnlyAfterTaskBodyReturns() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val events = mutableListOf<String>()
        val tracker = SsivTaskTracker(
            backgroundExecutor = worker,
            postToMain = { sentinel ->
                events += "post-sentinel"
                main.execute(sentinel)
                true
            },
            onDrained = {}
        )

        tracker.execute(Runnable { events += "task" })

        assertEquals(emptyList<String>(), events)

        worker.runNext()

        assertEquals(listOf("task", "post-sentinel"), events)
        assertEquals(1, tracker.outstandingTaskCount)
    }

    @Test
    fun shouldDeliverDrainOnce_whenLastSentinelRunsAfterRetire() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.execute(Runnable { })
        tracker.retire()
        tracker.retire()

        assertTrue(tracker.isRetired)
        assertFalse(tracker.isDrained)
        assertEquals(0, drains.get())
        assertEquals(1, tracker.outstandingTaskCount)

        worker.runNext()

        assertEquals(0, drains.get())

        main.runNext()

        assertEquals(0, tracker.outstandingTaskCount)
        assertTrue(tracker.isDrained)
        assertEquals(1, drains.get())

        tracker.retire()

        assertEquals("retire must stay idempotent after draining", 1, drains.get())
    }

    @Test
    fun shouldAcceptDerivedTasks_afterRetireUntilDrained() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.execute(Runnable { })
        worker.runNext()
        tracker.retire()

        assertTrue(tracker.isRetired)
        assertFalse(tracker.isDrained)
        assertEquals(1, tracker.outstandingTaskCount)

        // Simulates SSIV deriving a tile task from onTilesInited while the first sentinel is queued.
        tracker.execute(Runnable { })

        assertEquals(2, tracker.outstandingTaskCount)

        main.runNext()

        assertEquals(1, tracker.outstandingTaskCount)
        assertEquals(0, drains.get())

        worker.runNext()
        main.runNext()

        assertEquals(0, tracker.outstandingTaskCount)
        assertTrue(tracker.isDrained)
        assertEquals(1, drains.get())

        assertThrows(RejectedExecutionException::class.java) {
            tracker.execute(Runnable { })
        }
    }

    @Test
    fun shouldDeliverDrainImmediately_whenRetiredWhileIdle() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.retire()
        tracker.retire()

        assertTrue(tracker.isRetired)
        assertTrue(tracker.isDrained)
        assertEquals(0, tracker.outstandingTaskCount)
        assertEquals(1, drains.get())
    }

    @Test
    fun shouldCountReentrantTask_andDrainAfterEverySentinel() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        var innerRan = false
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.execute(Runnable { tracker.execute(Runnable { innerRan = true }) })

        assertEquals(1, tracker.outstandingTaskCount)

        worker.runNext()

        assertEquals("reentrant submission must be counted", 2, tracker.outstandingTaskCount)
        assertEquals(1, worker.size)
        assertFalse(innerRan)

        main.runNext()

        assertEquals(1, tracker.outstandingTaskCount)

        tracker.retire()

        assertEquals(0, drains.get())

        worker.runNext()

        assertTrue(innerRan)
        assertEquals(1, tracker.outstandingTaskCount)

        main.runNext()

        assertEquals(0, tracker.outstandingTaskCount)
        assertTrue(tracker.isDrained)
        assertEquals(1, drains.get())
    }

    @Test
    fun shouldRunQueuedTasks_evenAfterRetire() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val ran = mutableListOf<String>()
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.execute(Runnable { ran += "first" })
        tracker.execute(Runnable { ran += "second" })
        tracker.retire()

        worker.runNext()
        worker.runNext()
        main.runAll()

        assertEquals(listOf("first", "second"), ran)
        assertEquals(0, tracker.outstandingTaskCount)
        assertTrue(tracker.isDrained)
        assertEquals(1, drains.get())
    }

    @Test
    fun shouldRejectExecuteOnlyAfterDrain_withoutCounting() {
        val worker = TrackerManualExecutor()
        val main = TrackerManualExecutor()
        val drains = AtomicInteger()
        val tracker = SsivTaskTracker(worker, main::post) { drains.incrementAndGet() }

        tracker.retire()

        assertTrue(tracker.isDrained)

        assertThrows(RejectedExecutionException::class.java) {
            tracker.execute(Runnable { })
        }

        assertEquals(0, tracker.outstandingTaskCount)
        assertEquals(0, worker.size)
        assertEquals(0, main.size)
        assertEquals(1, drains.get())
    }
}

/** Shared manual executor for the tracker lifecycle and failure test classes. */
internal class TrackerManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    val size: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun post(command: Runnable): Boolean {
        tasks.addLast(command)
        return true
    }

    fun runNext() {
        check(tasks.isNotEmpty()) { "no queued task" }
        tasks.removeFirst().run()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }
}
