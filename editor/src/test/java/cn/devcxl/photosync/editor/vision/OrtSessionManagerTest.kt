package cn.devcxl.photosync.editor.vision

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import ai.onnxruntime.OrtException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [OrtSessionManager] — single-flight session reuse, tiered release, model switch
 * and recoverable failures. The native runtime is replaced by [FakeSessionFactory], so no `.so` is
 * loaded and the idle timeout runs through [ManualIdleScheduler] instead of wall-clock delays.
 */
class OrtSessionManagerTest {

    private val tempDir: File = Files.createTempDirectory("ort-session").toFile()
    private val modelA: File = File(tempDir, "model-a.onnx").apply { writeBytes(byteArrayOf(1)) }
    private val modelB: File = File(tempDir, "model-b.onnx").apply { writeBytes(byteArrayOf(2)) }
    private val scheduler = ManualIdleScheduler()
    private var nowMillis = 1_000L

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldNotCreateASession_whenTheManagerIsOnlyConstructed() {
        val factory = FakeSessionFactory()

        manager(DeviceMemoryTier.STANDARD, factory)

        assertEquals(0, factory.created.size)
    }

    @Test
    fun shouldReturnModelMissing_whenTheModelFileDoesNotExist() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)

        val result = manager.withSession(File(tempDir, "missing.onnx")) { 1 }

        assertTrue(result.isFailure)
        assertEquals(OrtSessionFailureReason.MODEL_MISSING, reasonOf(result))
        assertEquals(0, factory.created.size)
    }

    @Test
    fun shouldReuseTheWarmSession_whenTheSameModelIsUsedAgainOnTheStandardTier() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)

        val first = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle
        val second = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        assertSame(first, second)
        assertEquals(1, factory.created.size)
        assertFalse(first.closed)
        assertEquals(1, manager.activeSessionCount)
    }

    @Test
    fun shouldCloseThePreviousSession_whenSwitchingModels() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)

        manager.withSession(modelA) { it }
        manager.withSession(modelB) { it }

        assertEquals(2, factory.created.size)
        assertTrue(factory.created[0].closed)
        assertFalse(factory.created[1].closed)
    }

    @Test
    fun shouldReleaseTheSession_whenTheStandardTierStaysIdleForThirtySeconds() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)
        val handle = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        nowMillis += OrtSessionManager.STANDARD_IDLE_TIMEOUT_MS
        scheduler.runPending()

        assertTrue(handle.closed)
        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldRebuildTheSession_whenTheRuntimeThreadCountChanges() {
        val factory = FakeSessionFactory()
        var policy = OrtSessionRuntimeConfig(DeviceMemoryTier.STANDARD, 2, 30_000L)
        val manager = managerWithPolicy(factory) { policy }

        val first = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle
        policy = policy.copy(intraOpThreads = 4)
        val second = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        assertEquals(listOf(2, 4), factory.createdThreads)
        assertTrue("the previous session must close before the rebuild", first.closed)
        assertFalse(second.closed)
        assertEquals(1, manager.activeSessionCount)
    }

    @Test
    fun shouldClampTheRuntimeThreadCount_whenTheSettingIsOutOfRange() {
        val factory = FakeSessionFactory()
        val manager = managerWithPolicy(factory) {
            OrtSessionRuntimeConfig(DeviceMemoryTier.STANDARD, 9, 30_000L)
        }

        manager.withSession(modelA) { 1 }

        assertEquals(
            OrtSessionRuntimeConfig.MAX_INTRA_OP_THREADS,
            factory.createdThreads.single()
        )
    }

    @Test
    fun shouldReleaseImmediately_whenTheRuntimePolicyIsImmediate() {
        val factory = FakeSessionFactory()
        val manager = managerWithPolicy(factory) {
            OrtSessionRuntimeConfig(DeviceMemoryTier.STANDARD, 2, idleTimeoutMs = null)
        }

        val handle = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        assertTrue(handle.closed)
        assertEquals(0, manager.activeSessionCount)
        assertNull(scheduler.scheduledDelayMillis)
    }

    @Test
    fun shouldReleaseImmediately_whenTheRuntimeTierIsLow() {
        val factory = FakeSessionFactory()
        val manager = managerWithPolicy(factory) {
            OrtSessionRuntimeConfig(DeviceMemoryTier.LOW, 2, 60_000L)
        }

        val handle = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        assertTrue(handle.closed)
        assertNull(scheduler.scheduledDelayMillis)
    }

    @Test
    fun shouldUseTheConfiguredIdleTimeout_whenThePolicyIsTenSeconds() {
        val factory = FakeSessionFactory()
        val manager = managerWithPolicy(factory) {
            OrtSessionRuntimeConfig(DeviceMemoryTier.STANDARD, 2, 10_000L)
        }
        val handle = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        nowMillis += 10_000L
        scheduler.runPending()

        assertTrue(handle.closed)
        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldKeepTheSession_whenTheIdleCheckRunsBeforeTheTimeout() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)
        manager.withSession(modelA) { it }

        nowMillis += OrtSessionManager.STANDARD_IDLE_TIMEOUT_MS - 1_000L
        scheduler.runPending()

        assertEquals(1, manager.activeSessionCount)
        assertEquals(1_000L, scheduler.scheduledDelayMillis)

        nowMillis += 1_000L
        scheduler.runPending()

        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldCloseImmediatelyAfterInference_whenTheDeviceIsLowTier() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.LOW, factory)

        val handle = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        assertTrue(handle.closed)
        assertEquals(0, manager.activeSessionCount)
        assertNull(scheduler.scheduledDelayMillis)
    }

    @Test
    fun shouldReturnRecoverableFailureAndRelease_whenInferenceRunsOutOfMemory() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)

        val result = manager.withSession(modelA) { throw OutOfMemoryError("simulated") }

        assertTrue(result.isFailure)
        assertEquals(OrtSessionFailureReason.OUT_OF_MEMORY, reasonOf(result))
        assertTrue(factory.created.single().closed)
        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldReturnRecoverableFailureAndRelease_whenInferenceThrowsOrtException() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)

        val result = manager.withSession(modelA) { throw OrtException("native failure") }

        assertTrue(result.isFailure)
        assertEquals(OrtSessionFailureReason.RUNTIME_ERROR, reasonOf(result))
        assertTrue(factory.created.single().closed)
        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldReturnRecoverableFailure_whenTheFactoryThrowsOrtException() {
        val factory = FakeSessionFactory().apply { failure = OrtException("bad model") }
        val manager = manager(DeviceMemoryTier.STANDARD, factory)

        val result = manager.withSession(modelA) { 1 }

        assertTrue(result.isFailure)
        assertEquals(OrtSessionFailureReason.RUNTIME_ERROR, reasonOf(result))
        assertEquals(0, factory.created.size)
        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldReleaseWithoutLoadingAnything_whenMemoryPressureArrives() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)

        manager.onMemoryPressure()

        assertEquals(0, factory.created.size)
        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldCloseTheWarmSession_whenMemoryPressureArrives() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)
        val handle = manager.withSession(modelA) { it }.getOrThrow() as FakeSessionHandle

        manager.onMemoryPressure()

        assertTrue(handle.closed)
        assertEquals(0, manager.activeSessionCount)
    }

    @Test
    fun shouldSerializeInference_whenTwoThreadsCallWithSession() {
        val factory = FakeSessionFactory()
        val manager = manager(DeviceMemoryTier.STANDARD, factory)
        val firstInside = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()

        val firstThread = Thread {
            manager.withSession(modelA) {
                maxConcurrent.updateAndGet { max -> maxOf(max, concurrent.incrementAndGet()) }
                firstInside.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
                concurrent.decrementAndGet()
            }
        }
        firstThread.start()
        assertTrue(firstInside.await(2, TimeUnit.SECONDS))

        val secondThread = Thread {
            secondAttempting.countDown()
            manager.withSession(modelA) {
                maxConcurrent.updateAndGet { max -> maxOf(max, concurrent.incrementAndGet()) }
                concurrent.decrementAndGet()
            }
        }
        secondThread.start()
        assertTrue(secondAttempting.await(2, TimeUnit.SECONDS))
        Thread.sleep(100L)
        assertEquals(1, maxConcurrent.get())

        releaseFirst.countDown()
        firstThread.join(2_000L)
        secondThread.join(2_000L)

        assertEquals(1, maxConcurrent.get())
        assertEquals(1, factory.created.size)
    }

    private fun manager(tier: DeviceMemoryTier, factory: OrtSessionFactory): OrtSessionManager =
        OrtSessionManager(
            tier = tier,
            sessionFactory = factory,
            idleTimeoutMs = OrtSessionManager.STANDARD_IDLE_TIMEOUT_MS,
            scheduler = scheduler,
            clock = { nowMillis }
        )

    private fun managerWithPolicy(
        factory: OrtSessionFactory,
        policy: () -> OrtSessionRuntimeConfig
    ): OrtSessionManager = OrtSessionManager(
        tier = DeviceMemoryTier.STANDARD,
        sessionFactory = factory,
        scheduler = scheduler,
        clock = { nowMillis },
        runtimePolicy = policy
    )

    private fun reasonOf(result: Result<*>): OrtSessionFailureReason? =
        (result.exceptionOrNull() as? OrtSessionUnavailableException)?.reason

    private class FakeSessionFactory : OrtSessionFactory {

        val created = mutableListOf<FakeSessionHandle>()
        val createdThreads = mutableListOf<Int>()
        var failure: Throwable? = null

        override fun create(modelFile: File, intraOpThreads: Int): OrtSessionHandle {
            failure?.let { throw it }
            createdThreads += intraOpThreads
            return FakeSessionHandle(modelFile.absolutePath).also { created += it }
        }
    }

    private class FakeSessionHandle(override val modelPath: String) : OrtSessionHandle {

        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }

    private class ManualIdleScheduler : IdleReleaseScheduler {

        var scheduledDelayMillis: Long? = null
            private set

        private var pendingTask: Runnable? = null

        override fun schedule(delayMillis: Long, task: Runnable) {
            scheduledDelayMillis = delayMillis
            pendingTask = task
        }

        override fun cancel() {
            scheduledDelayMillis = null
            pendingTask = null
        }

        fun runPending() {
            val task = pendingTask ?: return
            pendingTask = null
            task.run()
        }
    }
}
