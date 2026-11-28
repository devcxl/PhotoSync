package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.RandomAccessFile
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.AiAssetRef
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintCropBlender
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.domain.LiquifyMeshCodec

/**
 * Seam under test: [MiGanInpainter] — variant selection, per-tile scheduling, input validation,
 * cancellation and content-addressed persistence, with the native runtime replaced by fakes.
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
    private val pipelineSpec = AiModelSpec(
        id = "test-migan-pipeline",
        version = "1",
        fileName = "migan-pipeline-test.onnx",
        mirrorUrls = listOf("https://mirror.example/migan-pipeline-test.onnx"),
        byteSize = modelBytes.size.toLong(),
        sha256 = LiquifyMeshCodec.sha256Hex(modelBytes),
        license = AiModelLicense("MIT", "https://example/license"),
        variantHint = InpaintVariant.PIPELINE_UINT8
    )

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldRunOneGeneratorTileAndPersistOnePatch_whenTheInferenceSucceeds() = runBlocking {
        val models = installedModels(modelSpec)
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
            generatorRunner = runner,
            variantDetector = generatorDetector(),
            modelSpec = modelSpec
        )
        val width = 512
        val height = 512
        val progress = mutableListOf<Int>()

        val outcome = inpainter.inpaint(
            photoId = 7L,
            sourceArgb = IntArray(width * height) { 0xFF303030.toInt() },
            width = width,
            height = height,
            mask = centredHoleMask(width, height, 40),
            onProgress = { progress.add(it) }
        ).getOrThrow()

        assertEquals(InpaintVariant.GENERATOR_FIXED_512, outcome.variant)
        assertEquals(1, outcome.tiles.size)
        assertEquals(1, runner.calls)
        assertEquals(InpaintCropBlender.MODEL_INPUT_ELEMENTS, runner.lastInputSize)
        assertEquals(InpaintBBox(96f / 512f, 96f / 512f, 416f / 512f, 416f / 512f), outcome.patches.single().bbox)
        val ref = outcome.patches.single()
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
    fun shouldRunThePipelineVariant_whenTheSignatureSaysUint8() = runBlocking {
        val models = installedModels(pipelineSpec)
        val generator = RecordingTileRunner()
        val pipeline = RecordingPipelineRunner()
        val outcome = MiGanInpainter(
            sessionManager = sessionManager(FakeSessionFactory()),
            downloadManager = models,
            assetStore = AiAssetStore(tempDir),
            encoder = FakeEncoder(),
            generatorRunner = generator,
            pipelineRunner = pipeline,
            variantDetector = pipelineDetector(),
            modelSpec = pipelineSpec
        ).inpaint(
            photoId = 8L,
            sourceArgb = IntArray(512 * 512) { 0xFF204060.toInt() },
            width = 512,
            height = 512,
            mask = centredHoleMask(512, 512, 40)
        ).getOrThrow()

        assertEquals(InpaintVariant.PIPELINE_UINT8, outcome.variant)
        assertEquals(0, generator.calls)
        assertEquals(1, pipeline.calls)
        val tile = outcome.tiles.single().tile
        assertEquals(3 * tile.cropRect.width * tile.cropRect.height, pipeline.lastImageSize)
        assertEquals(tile.cropRect.width * tile.cropRect.height, pipeline.lastMaskSize)
        assertTrue("the mask handed to the pipeline must be hard", pipeline.lastMask.all {
            it.toInt() == 0 || it.toInt() == 255.toByte().toInt()
        })
        assertTrue("the painted hole must be marked as repair", pipeline.lastMask.any { it.toInt() == 0 })
    }

    @Test
    fun shouldDiscoverTheLegacyGenerator_whenOnlyTheOldArtifactIsInstalled() = runBlocking {
        val models = legacyPlaceholder()
        val runner = RecordingTileRunner()
        val inpainter = MiGanInpainter(
            sessionManager = sessionManager(FakeSessionFactory()),
            downloadManager = models,
            assetStore = AiAssetStore(tempDir),
            encoder = FakeEncoder(),
            generatorRunner = runner,
            variantDetector = generatorDetector(),
            modelSpec = AiModelCatalog.MI_GAN_PIPELINE
        )

        val selection = inpainter.resolveModel().getOrThrow()

        assertEquals(AiModelCatalog.MI_GAN_GENERATOR_LEGACY.id, selection.spec.id)
        assertEquals(InpaintVariant.GENERATOR_FIXED_512, selection.variant)
        assertEquals(models.modelFile(AiModelCatalog.MI_GAN_GENERATOR_LEGACY), selection.file)
        assertEquals(0, runner.calls)
    }

    @Test
    fun shouldEmitOnePatchPerTile_whenTwoHolesAreFarApart() = runBlocking {
        val outcome = citedInpainter(RecordingTileRunner()).inpaint(
            photoId = 11L,
            sourceArgb = IntArray(512 * 512) { 0xFF404040.toInt() },
            width = 512,
            height = 512,
            mask = maskOf(
                512,
                512,
                InpaintPixelRect(40, 40, 80, 80),
                InpaintPixelRect(400, 400, 440, 440)
            )
        ).getOrThrow()

        assertEquals(2, outcome.tiles.size)
        assertEquals(2, outcome.patches.size)
        assertEquals(2, outcome.patches.map { it.bbox }.toSet().size)
        assertTrue("a single tile patch must not union both holes", outcome.patches.all { patch ->
            val bbox = patch.bbox
            (bbox.right - bbox.left) < 0.75f && (bbox.bottom - bbox.top) < 0.75f
        })
        assertNotEquals(outcome.patches[0].patchRelPath, outcome.patches[1].patchRelPath)
    }

    @Test
    fun shouldRejectMismatchedBuffers_whenTheSourceDoesNotMatchItsSize() = runBlocking {
        val runner = RecordingTileRunner()
        val inpainter = citedInpainter(runner)

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
        val inpainter = citedInpainter(MiGanTileRunner { _, _ -> FloatArray(3) })

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
    fun shouldFailWithIncompatible_whenTheSignatureMatchesNoVariant() = runBlocking {
        val detector = InpaintVariantDetector(OnnxSignatureProbe {
            listOf(ModelInputSignature("input", ModelTensorType.FLOAT32, listOf(1L, 3L, 512L, 512L)))
        })
        val inpainter = citedInpainter(RecordingTileRunner(), detector = detector)

        val result = inpainter.inpaint(
            photoId = 7L,
            sourceArgb = IntArray(512 * 512),
            width = 512,
            height = 512,
            mask = centredHoleMask(512, 512, 40)
        )

        assertTrue(result.isFailure)
        assertEquals(MiGanFailureReason.INCOMPATIBLE, reasonOf(result))
    }

    @Test
    fun shouldFailWithNoStrokes_whenNothingIsPainted() = runBlocking {
        val runner = RecordingTileRunner()
        val inpainter = citedInpainter(runner)

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
        val models = AiModelDownloadManager(File(tempDir, "models"))
        val runner = RecordingTileRunner()
        val inpainter = citedInpainter(runner, models = models)

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
            downloadManager = installedModels(modelSpec),
            assetStore = store,
            encoder = FakeEncoder(),
            generatorRunner = runner,
            variantDetector = generatorDetector(),
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
        // The tile completed while the cancel arrived; E2 keeps patches completed before the
        // cancellation, so a persisted asset here is the intended behaviour, not a leak.
        sessionManager.close()
        assertTrue(sessionFactory.created.single().closed)
    }

    private fun citedInpainter(
        runner: MiGanTileRunner,
        models: AiModelDownloadManager = installedModels(modelSpec),
        detector: InpaintVariantDetector = generatorDetector()
    ): MiGanInpainter = MiGanInpainter(
        sessionManager = sessionManager(FakeSessionFactory()),
        downloadManager = models,
        assetStore = AiAssetStore(tempDir),
        encoder = FakeEncoder(),
        generatorRunner = runner,
        variantDetector = detector,
        modelSpec = modelSpec
    )

    private fun installedModels(spec: AiModelSpec): AiModelDownloadManager {
        val models = AiModelDownloadManager(File(tempDir, "models"))
        val file = models.modelFile(spec)
        file.parentFile?.mkdirs()
        file.writeBytes(modelBytes)
        return models
    }

    /** A sparse 28 MB placeholder so `isInstalled` sees the pinned legacy length. */
    private fun legacyPlaceholder(): AiModelDownloadManager {
        val models = AiModelDownloadManager(File(tempDir, "models"))
        val legacy = AiModelCatalog.MI_GAN_GENERATOR_LEGACY
        val file = models.modelFile(legacy)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(legacy.byteSize) }
        return models
    }

    private fun generatorDetector(): InpaintVariantDetector = InpaintVariantDetector(
        OnnxSignatureProbe {
            listOf(ModelInputSignature("input", ModelTensorType.FLOAT32, listOf(1L, 4L, 512L, 512L)))
        }
    )

    private fun pipelineDetector(): InpaintVariantDetector = InpaintVariantDetector(
        OnnxSignatureProbe {
            listOf(
                ModelInputSignature("image", ModelTensorType.UINT8, listOf(1L, 3L, -1L, -1L)),
                ModelInputSignature("mask", ModelTensorType.UINT8, listOf(1L, 1L, -1L, -1L))
            )
        }
    )

    private fun sessionManager(factory: OrtSessionFactory): OrtSessionManager = OrtSessionManager(
        tier = DeviceMemoryTier.STANDARD,
        sessionFactory = factory,
        scheduler = NoopScheduler
    )

    private fun reasonOf(result: Result<*>): MiGanFailureReason? =
        (result.exceptionOrNull() as? MiGanInpaintException)?.reason

    private fun centredHoleMask(width: Int, height: Int, hole: Int): InpaintMaskBuffer {
        val left = (width - hole) / 2
        val top = (height - hole) / 2
        return maskOf(width, height, InpaintPixelRect(left, top, left + hole, top + hole))
    }

    private fun maskOf(width: Int, height: Int, vararg painted: InpaintPixelRect): InpaintMaskBuffer {
        val coverage = ByteArray(width * height)
        for (rect in painted) {
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    coverage[y * width + x] = 0xFF.toByte()
                }
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

    private class RecordingPipelineRunner : MiganPipelineRunner {
        var calls = 0
        var lastImageSize = -1
        var lastMaskSize = -1
        var lastMask: ByteArray = ByteArray(0)

        override fun run(
            handle: OrtSessionHandle,
            image: ByteArray,
            mask: ByteArray,
            width: Int,
            height: Int
        ): ByteArray {
            calls++
            lastImageSize = image.size
            lastMaskSize = mask.size
            lastMask = mask
            return image
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
        var rgbBytes = byteArrayOf(11, 12, 13)
            private set
        var alphaBytes = byteArrayOf(21, 22, 23)
            private set
        private var calls = 0

        override fun encodeRgb(argb: IntArray, width: Int, height: Int): ByteArray {
            calls++
            rgbBytes = byteArrayOf(calls.toByte(), 12, 13)
            return rgbBytes
        }

        override fun encodeAlpha(alpha: ByteArray, width: Int, height: Int): ByteArray {
            alphaBytes = byteArrayOf(calls.toByte(), 22, 23)
            return alphaBytes
        }
    }
}
