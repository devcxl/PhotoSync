package cn.devcxl.photosync.editor.vision

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.AiAssetRef
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintCropBlender
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.LiquifyMeshCodec

/**
 * Seam under test: [MiGanInpainter] — serial tile scheduling, input validation, cancellation and
 * content-addressed persistence, with the native runtime replaced by a fake session and runner.
 */
class MiGanInpainterTest {

    private val tempDir: File = Files.createTempDirectory("migan-inpainter").toFile()
    private val modelBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val modelSpec = AiModelSpec(
        id = "test-migan-generator",
        version = "1",
        fileName = "migan-test.onnx",
        mirrorUrls = listOf("https://mirror.example/migan-test.onnx"),
        byteSize = modelBytes.size.toLong(),
        sha256 = LiquifyMeshCodec.sha256Hex(modelBytes),
        license = AiModelLicense("MIT", "https://example/license")
    )

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldRunOneTileAndPersistPatchAndMask_whenTheInferenceSucceeds() = runBlocking {
        val models = installedModels()
        val sessionFactory = FakeSessionFactory()
        val sessionManager = sessionManager(sessionFactory)
        val runner = RecordingTileRunner()
        val encoder = FakeEncoder()
        val store = AiAssetStore(tempDir)
        val inpainter = MiGanInpainter(
            sessionManager = sessionManager,
            downloadManager = models,
            assetStore = store,
            encoder = encoder,
            tileRunner = runner,
            modelSpec = modelSpec
        )
        val width = 512
        val height = 512
        val progress = mutableListOf<Int>()

        val result = inpainter.inpaint(
            photoId = 7L,
            sourceArgb = IntArray(width * height) { 0xFF303030.toInt() },
            width = width,
            height = height,
            mask = centredHoleMask(width, height, 40),
            onProgress = { progress.add(it) }
        )

        val patches = result.getOrThrow()
        assertEquals(1, patches.size)
        assertEquals(1, runner.calls)
        assertEquals(InpaintCropBlender.MODEL_INPUT_ELEMENTS, runner.lastInputSize)
        assertEquals(InpaintBBox(0.0f, 0.0f, 1.0f, 1.0f), patches.single().bbox)
        val ref = patches.single()
        val storedPatch = checkNotNull(store.read(AiAssetRef(ref.patchRelPath, ref.sha256)))
        assertTrue(storedPatch.contentEquals(encoder.rgbBytes))
        val maskDigest = ref.maskRelPath.substringAfterLast('_').substringBeforeLast('.')
        val storedMask = checkNotNull(store.read(ref.maskRelPath, maskDigest))
        assertTrue(storedMask.contentEquals(encoder.alphaBytes))
        assertEquals(LiquifyMeshCodec.sha256Hex(encoder.rgbBytes), ref.sha256)
        assertTrue(progress.first() == 0)
        assertEquals(100, progress.last())
        sessionManager.close()
        assertTrue(sessionFactory.created.single().closed)
    }

    @Test
    fun shouldRejectMismatchedBuffers_whenTheSourceDoesNotMatchItsSize() = runBlocking {
        val runner = RecordingTileRunner()
        val inpainter = inpainter(runner)

        val result = inpainter.inpaint(
            photoId = 7L,
            sourceArgb = IntArray(16),
            width = 512,
            height = 512,
            mask = centredHoleMask(512, 512, 40)
        )

        assertTrue(result.isFailure)
        assertEquals(MiGanFailureReason.INVALID_INPUT, reasonOf(result))
        assertEquals(0, runner.calls)
    }

    @Test
    fun shouldFailWithoutPersisting_whenTheModelReturnsTheWrongShape() = runBlocking {
        val inpainter = inpainter(MiGanTileRunner { _, _ -> FloatArray(3) })

        val result = inpainter.inpaint(
            photoId = 7L,
            sourceArgb = IntArray(512 * 512),
            width = 512,
            height = 512,
            mask = centredHoleMask(512, 512, 40)
        )

        assertTrue(result.isFailure)
        assertEquals(MiGanFailureReason.INVALID_INPUT, reasonOf(result))
        assertFalse(File(tempDir, "edits/7").exists())
    }

    @Test
    fun shouldFailWithNoStrokes_whenNothingIsPainted() = runBlocking {
        val runner = RecordingTileRunner()
        val inpainter = inpainter(runner)

        val result = inpainter.inpaint(
            photoId = 7L,
            sourceArgb = IntArray(512 * 512),
            width = 512,
            height = 512,
            mask = InpaintMaskBuffer(512, 512, ByteArray(512 * 512))
        )

        assertTrue(result.isFailure)
        assertEquals(MiGanFailureReason.NO_STROKES, reasonOf(result))
        assertEquals(0, runner.calls)
    }

    @Test
    fun shouldFailWithModelMissing_whenTheArtifactIsNotInstalled() = runBlocking {
        val models = installedModels()
        File(models.modelDir(modelSpec), modelSpec.fileName).delete()
        val runner = RecordingTileRunner()
        val inpainter = inpainter(runner, models)

        val result = inpainter.inpaint(
            photoId = 7L,
            sourceArgb = IntArray(512 * 512),
            width = 512,
            height = 512,
            mask = centredHoleMask(512, 512, 40)
        )

        assertTrue(result.isFailure)
        assertEquals(MiGanFailureReason.MODEL_MISSING, reasonOf(result))
        assertEquals(0, runner.calls)
    }

    @Test
    fun shouldStopWithoutPersisting_whenCancelledMidInference() = runBlocking {
        val runner = BlockingTileRunner()
        val sessionFactory = FakeSessionFactory()
        val sessionManager = sessionManager(sessionFactory)
        val store = AiAssetStore(tempDir)
        val inpainter = MiGanInpainter(
            sessionManager = sessionManager,
            downloadManager = installedModels(),
            assetStore = store,
            encoder = FakeEncoder(),
            tileRunner = runner,
            modelSpec = modelSpec
        )
        var failure: Throwable? = null

        val job = launch(Dispatchers.Default) {
            try {
                inpainter.inpaint(
                    photoId = 9L,
                    sourceArgb = IntArray(64 * 64) { 0xFF404040.toInt() },
                    width = 64,
                    height = 64,
                    mask = centredHoleMask(64, 64, 8)
                )
            } catch (error: Throwable) {
                failure = error
            }
        }
        assertTrue(runner.entered.await(10, TimeUnit.SECONDS))
        job.cancel()
        runner.release.countDown()
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue(failure is CancellationException)
        assertFalse(File(tempDir, "edits/9").exists())
        sessionManager.close()
        assertTrue(sessionFactory.created.single().closed)
    }

    private fun inpainter(
        runner: MiGanTileRunner,
        models: AiModelDownloadManager = installedModels()
    ): MiGanInpainter = MiGanInpainter(
        sessionManager = sessionManager(FakeSessionFactory()),
        downloadManager = models,
        assetStore = AiAssetStore(tempDir),
        encoder = FakeEncoder(),
        tileRunner = runner,
        modelSpec = modelSpec
    )

    private fun installedModels(): AiModelDownloadManager {
        val models = AiModelDownloadManager(File(tempDir, "models"))
        val file = models.modelFile(modelSpec)
        file.parentFile?.mkdirs()
        file.writeBytes(modelBytes)
        return models
    }

    private fun sessionManager(factory: OrtSessionFactory): OrtSessionManager = OrtSessionManager(
        tier = DeviceMemoryTier.STANDARD,
        sessionFactory = factory,
        scheduler = NoopScheduler
    )

    private fun reasonOf(result: Result<*>): MiGanFailureReason? =
        (result.exceptionOrNull() as? MiGanInpaintException)?.reason

    private fun centredHoleMask(width: Int, height: Int, hole: Int): InpaintMaskBuffer {
        val coverage = ByteArray(width * height)
        val left = (width - hole) / 2
        val top = (height - hole) / 2
        for (y in top until top + hole) {
            for (x in left until left + hole) {
                coverage[y * width + x] = 0xFF.toByte()
            }
        }
        return InpaintMaskBuffer(width, height, coverage)
    }

    private class FakeHandle(override val modelPath: String) : OrtSessionHandle {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeSessionFactory : OrtSessionFactory {
        val created = mutableListOf<FakeHandle>()
        override fun create(modelFile: File, intraOpThreads: Int): OrtSessionHandle =
            FakeHandle(modelFile.absolutePath).also { created.add(it) }
    }

    private object NoopScheduler : IdleReleaseScheduler {
        override fun schedule(delayMillis: Long, task: Runnable) = Unit
        override fun cancel() = Unit
    }

    private class RecordingTileRunner : MiGanTileRunner {
        var calls = 0
        var lastInputSize = -1

        override fun run(handle: OrtSessionHandle, modelInput: FloatArray): FloatArray {
            calls++
            lastInputSize = modelInput.size
            return FloatArray(InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) { 1.0f }
        }
    }

    private class BlockingTileRunner : MiGanTileRunner {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun run(handle: OrtSessionHandle, modelInput: FloatArray): FloatArray {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return FloatArray(InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) { 1.0f }
        }
    }

    private class FakeEncoder : InpaintPatchEncoder {
        val rgbBytes = byteArrayOf(11, 12, 13)
        val alphaBytes = byteArrayOf(21, 22, 23)

        override fun encodeRgb(argb: IntArray, width: Int, height: Int): ByteArray = rgbBytes

        override fun encodeAlpha(alpha: ByteArray, width: Int, height: Int): ByteArray = alphaBytes
    }
}
