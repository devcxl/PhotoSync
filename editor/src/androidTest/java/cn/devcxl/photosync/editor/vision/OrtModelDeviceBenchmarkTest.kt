package cn.devcxl.photosync.editor.vision

import java.io.File
import java.security.MessageDigest
import java.util.Locale

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/**
 * On-device latency and resident-memory probe for the two pinned generator sessions.
 *
 * The test runs the *production* [OnnxRuntimeSessionFactory] and [OrtSessionManager] so the numbers
 * describe the shipping configuration (CPU + XNNPACK, two intra-op threads), not a lab variant.
 *
 * Models are not bundled: push them to the device first, for example
 * `adb push migan_generator_512.onnx /sdcard/Android/data/<target>/files/ort-benchmark-models/`,
 * or to `/sdcard/Download/` / `/data/local/tmp/`. Without them every method is skipped via
 * [assumeTrue], so CI (which has no models) stays green.
 *
 * The staged copy is verified against [AiModelSpec.byteSize] and [AiModelSpec.sha256] before it is
 * loaded, so a stale or half-copied artifact can never produce misleading numbers.
 */
@RunWith(AndroidJUnit4::class)
class OrtModelDeviceBenchmarkTest {

    private lateinit var context: Context
    private lateinit var stagedDir: File
    private lateinit var reportFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        stagedDir = File(context.cacheDir, STAGED_DIR_NAME).apply { mkdirs() }
        reportFile = File(context.getExternalFilesDir(null) ?: context.cacheDir, REPORT_FILE_NAME)
        reportFile.parentFile?.mkdirs()
        reportFile.writeText("")
        Timber.plant(Timber.DebugTree())
        report(
            String.format(
                Locale.US,
                "ORT-BENCH device=%s android=%s abi=%s totalMemMb=%d tier=%s cacheDir=%s",
                Build.MODEL,
                Build.VERSION.RELEASE,
                Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                totalMemoryMb(),
                DeviceMemoryTier.detect(context),
                context.cacheDir
            )
        )
    }

    @After
    fun tearDown() {
        stagedDir.deleteRecursively()
        Timber.uprootAll()
    }

    @Test
    fun shouldBenchmarkModnetAndMiganGenerators_onTheStandardTier() {
        val modnet = stageModel(AiModelCatalog.MODNET_PORTRAIT, MODNET_SOURCE)
        val migan = stageModel(AiModelCatalog.MI_GAN_GENERATOR, MIGAN_SOURCE)
        assumeTrue(
            "push ${MODNET_SOURCE} and/or ${MIGAN_SOURCE} to one of ${sourceDirsHint()}",
            modnet != null || migan != null
        )

        val environment = OrtEnvironment.getEnvironment()
        val factory = RecordingSessionFactory()
        val manager = OrtSessionManager(
            tier = DeviceMemoryTier.STANDARD,
            sessionFactory = factory,
            idleTimeoutMs = OrtSessionManager.STANDARD_IDLE_TIMEOUT_MS
        )
        try {
            if (modnet != null) {
                benchmarkPhase(
                    label = "modnet-portrait",
                    tier = DeviceMemoryTier.STANDARD,
                    modelFile = modnet,
                    outputChannels = 1,
                    warmRuns = WARM_RUNS,
                    manager = manager,
                    factory = factory,
                    inputFactory = ::modnetInput
                ).also { assertEquals(1, it.runs.last().sessionsResidentAfter) }
            }
            if (migan != null) {
                val phase = benchmarkPhase(
                    label = "migan-generator",
                    tier = DeviceMemoryTier.STANDARD,
                    modelFile = migan,
                    outputChannels = 3,
                    warmRuns = WARM_RUNS,
                    manager = manager,
                    factory = factory,
                    inputFactory = ::miganInput
                )
                assertEquals(1, manager.activeSessionCount)
                if (modnet != null) {
                    assertSwitchClosedTheFirstSession(factory)
                }
                assertEquals(1, phase.runs.last().sessionsResidentAfter)
            }
        } finally {
            manager.close()
        }
        assertEquals(0, manager.activeSessionCount)
        val released = snapshot()
        report(
            String.format(
                Locale.US,
                "ORT-BENCH phase=standard-release rssKb=%d peakRssKb=%d nativeHeapMb=%.2f",
                released.rssKb,
                released.peakRssKb,
                released.nativeHeapMb
            )
        )
    }

    @Test
    fun shouldReleaseTheMiganGeneratorSessionImmediately_whenTheLowTierRunsInference() {
        val model = stageModel(AiModelCatalog.MI_GAN_GENERATOR, MIGAN_SOURCE)
        assumeTrue("push $MIGAN_SOURCE to one of ${sourceDirsHint()}", model != null)
        val modelFile = model ?: return

        val factory = RecordingSessionFactory()
        val manager = OrtSessionManager(
            tier = DeviceMemoryTier.LOW,
            sessionFactory = factory,
            idleTimeoutMs = OrtSessionManager.STANDARD_IDLE_TIMEOUT_MS
        )
        try {
            val phase = benchmarkPhase(
                label = "migan-generator",
                tier = DeviceMemoryTier.LOW,
                modelFile = modelFile,
                outputChannels = 3,
                warmRuns = WARM_RUNS - 1,
                manager = manager,
                factory = factory,
                inputFactory = ::miganInput
            )
            for (run in phase.runs) {
                assertEquals("low tier must hold no session after a run", 0, run.sessionsResidentAfter)
            }
            assertEquals(0, manager.activeSessionCount)
            assertEquals(
                "low tier recreates the session for every call",
                phase.runs.size,
                factory.handles.size
            )
            assertTrue(factory.handles.all { it.closed })
        } finally {
            manager.close()
        }
        assertEquals(0, manager.activeSessionCount)
        val released = snapshot()
        report(
            String.format(
                Locale.US,
                "ORT-BENCH phase=low-release rssKb=%d peakRssKb=%d nativeHeapMb=%.2f sessions=%d",
                released.rssKb,
                released.peakRssKb,
                released.nativeHeapMb,
                manager.activeSessionCount
            )
        )
    }

    @Test
    fun shouldReleaseTheWarmStandardTierSession_whenItStaysIdle() {
        val model = stageModel(AiModelCatalog.MODNET_PORTRAIT, MODNET_SOURCE)
        assumeTrue("push $MODNET_SOURCE to one of ${sourceDirsHint()}", model != null)
        val modelFile = model ?: return

        val factory = RecordingSessionFactory()
        val manager = OrtSessionManager(
            tier = DeviceMemoryTier.STANDARD,
            sessionFactory = factory,
            idleTimeoutMs = OrtSessionManager.STANDARD_IDLE_TIMEOUT_MS
        )
        val input = modnetInput(OrtEnvironment.getEnvironment())
        try {
            runInference(manager, factory, modelFile, input, 1)
            assertEquals("standard tier keeps the session warm", 1, manager.activeSessionCount)
            val resident = snapshot()
            val startedAt = SystemClock.elapsedRealtime()
            val releasedByIdleTimer = manager.awaitIdleRelease(IDLE_RELEASE_TIMEOUT_MS)
            val waitedMillis = SystemClock.elapsedRealtime() - startedAt
            assertTrue("the idle timer must release the warm session", releasedByIdleTimer)
            assertEquals(0, manager.activeSessionCount)
            val idle = snapshot()
            report(
                String.format(
                    Locale.US,
                    "ORT-BENCH phase=standard-idle-release waitedMs=%d rssResidentKb=%d " +
                        "rssAfterIdleKb=%d peakRssKb=%d nativeHeapMb=%.2f",
                    waitedMillis,
                    resident.rssKb,
                    idle.rssKb,
                    idle.peakRssKb,
                    idle.nativeHeapMb
                )
            )

            runInference(manager, factory, modelFile, input, 1)
            assertEquals(1, manager.activeSessionCount)
            manager.onMemoryPressure()
            assertEquals("onTrimMemory must release the session", 0, manager.activeSessionCount)
            assertTrue("every session must be closed at the end", factory.handles.all { it.closed })
        } finally {
            input.close()
            manager.close()
        }
    }

    /**
     * Loads [modelFile] (if needed), runs it once plus [warmRuns] warm iterations and reports both
     * latencies and the process memory high-water mark for the phase.
     */
    private fun benchmarkPhase(
        label: String,
        tier: DeviceMemoryTier,
        modelFile: File,
        outputChannels: Int,
        warmRuns: Int,
        manager: OrtSessionManager,
        factory: RecordingSessionFactory,
        inputFactory: (OrtEnvironment) -> OnnxTensor
    ): PhaseRecord {
        val highWaterMarkReset = ProcessMemoryProbe.clearHighWaterMark()
        val baseline = snapshot()
        val input = inputFactory(OrtEnvironment.getEnvironment())
        try {
            val runs = ArrayList<RunRecord>(warmRuns + 1)
            runs += runInference(manager, factory, modelFile, input, outputChannels)
            repeat(warmRuns) { runs += runInference(manager, factory, modelFile, input, outputChannels) }
            val after = snapshot()
            val phase = PhaseRecord(label, tier, modelFile.length(), runs, baseline, after)
            report(phase.toLogLine(highWaterMarkReset))
            return phase
        } finally {
            input.close()
        }
    }

    private fun runInference(
        manager: OrtSessionManager,
        factory: RecordingSessionFactory,
        modelFile: File,
        input: OnnxTensor,
        outputChannels: Int
    ): RunRecord {
        val createdBefore = factory.handles.size
        val wallStart = SystemClock.elapsedRealtimeNanos()
        var runNanos = 0L
        val outcome = manager.withSession(modelFile) { handle ->
            val session = (handle as RecordingSessionFactory.TrackedHandle).session
            val runStart = SystemClock.elapsedRealtimeNanos()
            val stats = session.run(mapOf(INPUT_NAME to input)).use { result ->
                val output = result.get(0) as OnnxTensor
                val shape = (output.info as TensorInfo).shape.toList()
                OutputStats(shape, summarize(output.floatBuffer))
            }
            runNanos = SystemClock.elapsedRealtimeNanos() - runStart
            stats
        }
        val wallNanos = SystemClock.elapsedRealtimeNanos() - wallStart
        val stats = outcome.getOrElse { throw AssertionError("inference failed for $modelFile", it) }
        assertEquals(
            "output shape",
            listOf(1L, outputChannels.toLong(), SIZE.toLong(), SIZE.toLong()),
            stats.shape
        )
        assertEquals("output must be finite", 0, stats.summary.nonFinite)
        assertTrue(
            "output must not be a constant frame",
            stats.summary.max > stats.summary.min
        )
        val sessionLoadMillis =
            if (factory.handles.size > createdBefore) factory.lastCreateMillis else 0.0
        return RunRecord(
            sessionLoadMillis = sessionLoadMillis,
            inferenceMillis = runNanos / NANOS_PER_MILLI,
            wallMillis = wallNanos / NANOS_PER_MILLI,
            sessionsResidentAfter = manager.activeSessionCount,
            outputSummary = stats.summary
        )
    }

    private fun assertSwitchClosedTheFirstSession(factory: RecordingSessionFactory) {
        assertEquals("one session per model", 2, factory.handles.size)
        assertTrue("old session must be closed before the new one loads", factory.handles[0].closed)
        assertFalse("the new session must stay resident on the standard tier", factory.handles[1].closed)
    }

    private fun stageModel(spec: AiModelSpec, sourceName: String): File? {
        val target = File(stagedDir, sourceName)
        val source = candidateSources(sourceName).firstOrNull { it.isFile }
        if (source == null) {
            Timber.w("%s skipped %s: searched %s", TAG, sourceName, sourceDirsHint())
            return null
        }
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
        assertEquals("$sourceName byte size", spec.byteSize, target.length())
        assertEquals("$sourceName sha256", spec.sha256, sha256Of(target))
        return target
    }

    private fun candidateSources(name: String): List<File> = listOfNotNull(
        context.getExternalFilesDir(STAGED_DIR_NAME)?.let { File(it, name) },
        context.externalCacheDir?.let { File(it, name) },
        File("/sdcard/Download/$name"),
        File("/data/local/tmp/$name")
    )

    private fun sourceDirsHint(): String = candidateSources("x").joinToString(" | ") { it.parent }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES.toInt())
        file.inputStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun modnetInput(environment: OrtEnvironment): OnnxTensor {
        val values = FloatArray(3 * SIZE * SIZE)
        for (index in values.indices) {
            values[index] = ((index * 37) % 255) / 255f
        }
        return OnnxTensor.createTensor(
            environment,
            java.nio.FloatBuffer.wrap(values),
            longArrayOf(1L, 3L, SIZE.toLong(), SIZE.toLong())
        )
    }

    private fun miganInput(environment: OrtEnvironment): OnnxTensor {
        val pixels = SIZE * SIZE
        val values = FloatArray(4 * pixels)
        for (index in 0 until pixels) {
            val x = index % SIZE
            val y = index / SIZE
            val hole = x in HOLE_RANGE && y in HOLE_RANGE
            val mask = if (hole) 0f else 1f
            val image = ((index * 53) % 255) / 255f * 2f - 1f
            values[index] = mask - 0.5f
            values[pixels + index] = image * mask
            values[2 * pixels + index] = image * mask
            values[3 * pixels + index] = image * mask
        }
        return OnnxTensor.createTensor(
            environment,
            java.nio.FloatBuffer.wrap(values),
            longArrayOf(1L, 4L, SIZE.toLong(), SIZE.toLong())
        )
    }

    private fun summarize(buffer: java.nio.FloatBuffer): FloatSummary {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var nonFinite = 0
        while (buffer.hasRemaining()) {
            val value = buffer.get()
            if (!value.isFinite()) {
                nonFinite++
                continue
            }
            if (value < min) min = value
            if (value > max) max = value
        }
        return FloatSummary(min, max, nonFinite)
    }

    private fun snapshot(): MemorySnapshot {
        val process = ProcessMemoryProbe.read()
        val runtime = Runtime.getRuntime()
        return MemorySnapshot(
            rssKb = process?.rssKb ?: -1L,
            peakRssKb = process?.highWaterMarkKb ?: -1L,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB,
            javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        )
    }

    private fun totalMemoryMb(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return -1L
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem / BYTES_PER_MB_LONG
    }

    private fun report(line: String) {
        Timber.tag(TAG).i(line)
        reportFile.appendText(line + "\n")
    }

    private data class FloatSummary(val min: Float, val max: Float, val nonFinite: Int)

    private data class OutputStats(val shape: List<Long>, val summary: FloatSummary)

    private data class RunRecord(
        val sessionLoadMillis: Double,
        val inferenceMillis: Double,
        val wallMillis: Double,
        val sessionsResidentAfter: Int,
        val outputSummary: FloatSummary
    )

    private data class MemorySnapshot(
        val rssKb: Long,
        val peakRssKb: Long,
        val nativeHeapMb: Double,
        val javaHeapMb: Double
    )

    private data class PhaseRecord(
        val label: String,
        val tier: DeviceMemoryTier,
        val modelBytes: Long,
        val runs: List<RunRecord>,
        val baseline: MemorySnapshot,
        val after: MemorySnapshot
    ) {

        fun toLogLine(highWaterMarkReset: Boolean): String {
            val warm = runs.drop(1).joinToString("/") { String.format(Locale.US, "%.1f", it.inferenceMillis) }
            return String.format(
                Locale.US,
                "ORT-BENCH phase=%s tier=%s bytes=%d loadMs=%.1f firstInferenceMs=%.1f " +
                    "warmInferenceMs=[%s] rssBaseKb=%d rssPeakKb=%d rssAfterKb=%d " +
                    "peakDeltaKb=%d nativeHeapBaseMb=%.2f nativeHeapMb=%.2f javaHeapMb=%.2f " +
                    "min=%.3f max=%.3f hwmReset=%s",
                label,
                tier,
                modelBytes,
                runs.first().sessionLoadMillis,
                runs.first().inferenceMillis,
                warm,
                baseline.rssKb,
                after.peakRssKb,
                after.rssKb,
                after.peakRssKb - baseline.rssKb,
                baseline.nativeHeapMb,
                after.nativeHeapMb,
                after.javaHeapMb,
                runs.first().outputSummary.min,
                runs.first().outputSummary.max,
                highWaterMarkReset
            )
        }
    }

    private companion object {

        const val TAG = "ORT-BENCH"
        const val INPUT_NAME = "input"
        const val SIZE = 512
        const val WARM_RUNS = 3
        val HOLE_RANGE = 192 until 320
        const val STAGED_DIR_NAME = "ort-benchmark-models"
        const val REPORT_FILE_NAME = "ort-benchmark.log"
        const val MODNET_SOURCE = "modnet_fp32.onnx"
        const val MIGAN_SOURCE = "migan_generator_512.onnx"
        const val COPY_BUFFER_BYTES = 1 shl 20
        const val IDLE_RELEASE_TIMEOUT_MS = 40_000L
        const val BYTES_PER_MB = 1024.0 * 1024.0
        const val BYTES_PER_MB_LONG = 1024L * 1024L
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}

