package cn.devcxl.photosync.adapter

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Lifecycle tests for [TiledViewerSlot].
 *
 * The fake host records attach/detach/recycle calls, and the fake executors keep both the decode
 * worker and the main looper queue under the test's control. "Decode work" is simulated by
 * submitting a no-op through the executor that the slot installed on the view.
 */
class TiledViewerSlotTest {

    private val worker = ManualExecutor()
    private val main = ManualExecutor()
    private val host = FakeTiledHost()
    private lateinit var slot: TiledViewerSlot<FakeTiledView>

    @Before
    fun setUp() {
        slot = TiledViewerSlot(
            host = host,
            workerExecutor = worker,
            postToMain = { runnable ->
                main.execute(runnable)
                true
            }
        )
    }

    @Test
    fun shouldCreateAndAttachView_whenNoneIsAttached() {
        val target = slot.acquireForBind(needsNewImage = true)

        assertTrue(target.isFresh)
        assertSame(host.created.single(), target.view)
        assertEquals(listOf(target.view), host.attached)
        assertSame(target.view, slot.currentView)
        assertFalse(slot.hasInFlightTasks)
    }

    @Test
    fun shouldReuseView_whenItHasNoTrackedWork() {
        val first = slot.acquireForBind(needsNewImage = true)
        val second = slot.acquireForBind(needsNewImage = true)

        assertFalse(second.isFresh)
        assertSame(first.view, second.view)
        assertEquals("an idle view must not be rebuilt", 1, host.created.size)
    }

    @Test
    fun shouldReplaceBusyView_whenNewImageIsNeeded() {
        val first = slot.acquireForBind(needsNewImage = true)
        first.view.executor.execute(Runnable { })

        assertTrue(slot.hasInFlightTasks)

        val second = slot.acquireForBind(needsNewImage = true)

        assertTrue(second.isFresh)
        assertNotSame(first.view, second.view)
        assertEquals(listOf(first.view), host.detached)
        assertSame(second.view, slot.currentView)
        assertFalse("the replacement view starts idle", slot.hasInFlightTasks)
    }

    @Test
    fun shouldRecycleReplacedViewOnlyAfterItsCallbacksDrained() {
        val first = slot.acquireForBind(needsNewImage = true)
        first.view.executor.execute(Runnable { })

        slot.acquireForBind(needsNewImage = true)

        assertTrue("a busy view must not be recycled while decoding", host.recycled.isEmpty())

        worker.runAll()
        assertTrue("the result message is handled on the main thread", host.recycled.isEmpty())

        main.runAll()

        assertEquals(listOf(first.view), host.recycled)
    }

    @Test
    fun shouldReuseBusyView_whenNoNewImageIsNeeded() {
        val first = slot.acquireForBind(needsNewImage = true)
        first.view.executor.execute(Runnable { })

        val second = slot.acquireForBind(needsNewImage = false)

        assertFalse(second.isFresh)
        assertSame(first.view, second.view)
        assertTrue(host.detached.isEmpty())
        assertEquals(1, host.created.size)
        assertTrue(slot.hasInFlightTasks)
    }

    @Test
    fun shouldRecycleIdleViewImmediately_whenReleased() {
        val first = slot.acquireForBind(needsNewImage = true)

        slot.release()

        assertNull(slot.currentView)
        assertEquals(listOf(first.view), host.detached)
        assertEquals(listOf(first.view), host.recycled)
    }

    @Test
    fun shouldDeferReleaseOfBusyView_andStillCountDerivedTasks() {
        val first = slot.acquireForBind(needsNewImage = true)
        first.view.executor.execute(Runnable { })

        slot.release()

        assertNull(slot.currentView)
        assertEquals(listOf(first.view), host.detached)
        assertTrue(host.recycled.isEmpty())

        // SSIV derives follow-up tile tasks from onTilesInited inside the callbacks being
        // awaited; a retired tracker must keep accepting them or the main thread would crash.
        first.view.executor.execute(Runnable { })

        worker.runAll()
        main.runAll()

        assertEquals(listOf(first.view), host.recycled)
    }

    @Test
    fun shouldGiveEveryViewItsOwnTracker() {
        val first = slot.acquireForBind(needsNewImage = true)
        val reused = slot.acquireForBind(needsNewImage = false)
        first.view.executor.execute(Runnable { })

        val replacement = slot.acquireForBind(needsNewImage = true)

        assertSame("a reused view keeps its tracker", first.view.executor, reused.view.executor)
        assertNotSame(
            "a replacement view must not share the retired tracker",
            first.view.executor,
            replacement.view.executor
        )
    }

    @Test
    fun shouldRecycleBothViews_whenReplacementIsReleasedWhileOldOneDrains() {
        val first = slot.acquireForBind(needsNewImage = true)
        first.view.executor.execute(Runnable { })
        val second = slot.acquireForBind(needsNewImage = true)

        worker.runAll()
        main.runAll()
        slot.release()

        assertEquals(listOf(first.view, second.view), host.recycled)
        assertEquals(listOf(first.view, second.view), host.detached)
    }

    @Test
    fun shouldKeepAttachedViewAlive_whenItsTaskFinishes() {
        val target = slot.acquireForBind(needsNewImage = true)
        target.view.executor.execute(Runnable { })

        worker.runAll()
        main.runAll()

        assertTrue(host.recycled.isEmpty())
        assertSame(target.view, slot.currentView)
        assertFalse(slot.hasInFlightTasks)
    }

    @Test
    fun shouldReportPostFailure_andRetainTheReleasedView() {
        val failures = mutableListOf<Throwable>()
        val failingSlot = TiledViewerSlot(
            host = host,
            workerExecutor = worker,
            postToMain = { throw IllegalStateException("looper gone") },
            onTaskPostFailure = { failures += it }
        )
        val target = failingSlot.acquireForBind(needsNewImage = true)
        target.view.executor.execute(Runnable { })

        failingSlot.release()
        worker.runAll()

        assertEquals("looper gone", failures.single().message)
        assertEquals(listOf(target.view), host.detached)
        assertTrue("without a sentinel the view cannot be recycled safely", host.recycled.isEmpty())
    }

    private class FakeTiledView(val serial: Int, val executor: Executor)

    private class FakeTiledHost : TiledViewerSlot.Host<FakeTiledView> {
        val created = mutableListOf<FakeTiledView>()
        val attached = mutableListOf<FakeTiledView>()
        val detached = mutableListOf<FakeTiledView>()
        val recycled = mutableListOf<FakeTiledView>()

        override fun createView(executor: Executor): FakeTiledView {
            return FakeTiledView(created.size, executor).also { created += it }
        }

        override fun attachView(view: FakeTiledView) {
            attached += view
        }

        override fun detachView(view: FakeTiledView) {
            detached += view
        }

        override fun recycleView(view: FakeTiledView) {
            recycled += view
        }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }
}
