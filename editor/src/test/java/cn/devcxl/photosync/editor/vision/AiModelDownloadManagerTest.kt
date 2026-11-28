package cn.devcxl.photosync.editor.vision

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [AiModelDownloadManager] — HTTP status classification, ordered mirror fallback,
 * `Range` resume, cancellation, SHA-256 rejection, probe persistence and SAF import, driven through
 * a fake [ModelHttpSource] so nothing touches the network.
 */
class AiModelDownloadManagerTest {

    private val payload = ByteArray(4096) { index -> (index % 251).toByte() }
    private val primary = "https://mirror.example/primary.onnx"
    private val secondary = "https://mirror.example/secondary.onnx"
    private val tempDir: File = Files.createTempDirectory("ai-model").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldFallBackToTheSecondaryMirror_whenThePrimaryFails() {
        val source = FakeHttpSource(
            mapOf(
                primary to Mirror(failure = IOException("primary timed out")),
                secondary to Mirror(payload)
            )
        )
        val spec = spec(listOf(primary, secondary))

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isSuccess)
        assertEquals(listOf(primary to 0L, secondary to 0L), source.requests)
        assertTrue(checkNotNull(result.getOrNull()).readBytes().contentEquals(payload))
    }

    @Test
    fun shouldResumeFromThePartialFile_whenThePrimaryMirrorSupportsRange() {
        val spec = spec(listOf(primary, secondary))
        val half = payload.size / 2
        manager(FakeHttpSource(emptyMap())).partFile(spec).apply {
            parentFile?.mkdirs()
            writeBytes(payload.copyOfRange(0, half))
        }
        val source = FakeHttpSource(mapOf(primary to Mirror(payload)))

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isSuccess)
        assertEquals(listOf(primary to half.toLong()), source.requests)
        assertTrue(checkNotNull(result.getOrNull()).readBytes().contentEquals(payload))
        assertFalse(manager(source).partFile(spec).exists())
    }

    @Test
    fun shouldRestartFromZero_whenTheMirrorIgnoresTheRangeRequest() {
        val spec = spec(listOf(primary))
        manager(FakeHttpSource(emptyMap())).partFile(spec).apply {
            parentFile?.mkdirs()
            writeBytes(payload.copyOfRange(0, 512))
        }
        val source = FakeHttpSource(mapOf(primary to Mirror(payload)), supportsRange = false)

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isSuccess)
        assertEquals(listOf(primary to 512L), source.requests)
        assertTrue(checkNotNull(result.getOrNull()).readBytes().contentEquals(payload))
    }

    @Test
    fun shouldFallBackToTheSecondaryMirror_whenThePrimaryServesACorruptFile() {
        val corrupt = payload.copyOf().also { it[7] = (it[7] + 1).toByte() }
        val source = FakeHttpSource(
            mapOf(
                primary to Mirror(corrupt),
                secondary to Mirror(payload)
            )
        )
        val spec = spec(listOf(primary, secondary))

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isSuccess)
        assertEquals(listOf(primary to 0L, secondary to 0L), source.requests)
        assertTrue(checkNotNull(result.getOrNull()).readBytes().contentEquals(payload))
    }

    @Test
    fun shouldRejectAndDeleteTheFile_whenEveryMirrorFailsTheChecksum() {
        val corrupt = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val source = FakeHttpSource(
            mapOf(primary to Mirror(corrupt), secondary to Mirror(corrupt))
        )
        val spec = spec(listOf(primary, secondary))
        val manager = manager(source)

        val result = runBlocking { manager.download(spec) }

        assertTrue(result.isFailure)
        assertEquals(AiModelFailureReason.CHECKSUM_MISMATCH, failureReason(result))
        assertFalse(manager.partFile(spec).exists())
        assertFalse(manager.modelFile(spec).exists())
        assertFalse(manager.isInstalled(spec))
    }

    @Test
    fun shouldRejectWithSourceGone_whenEveryMirrorReturns404() {
        val source = FakeHttpSource(statusOnly(404))
        val spec = spec(source.urls)

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isFailure)
        assertEquals(AiModelFailureReason.SOURCE_GONE, failureReason(result))
        assertTrue(manager(source).lastProbeResult(spec).sourceGone)
    }

    @Test
    fun shouldRejectWithSourceGone_whenEveryMirrorReturns410() {
        val source = FakeHttpSource(statusOnly(410))
        val spec = spec(source.urls)

        val result = runBlocking { manager(source).download(spec) }

        assertEquals(AiModelFailureReason.SOURCE_GONE, failureReason(result))
        assertEquals(AiModelProbeOutcome.FAILED, manager(source).lastProbeResult(spec).outcome)
    }

    @Test
    fun shouldRejectWithSourceDenied_whenEveryMirrorReturns401() {
        val source = FakeHttpSource(statusOnly(401))

        val result = runBlocking { manager(source).download(spec(source.urls)) }

        assertEquals(AiModelFailureReason.SOURCE_DENIED, failureReason(result))
    }

    @Test
    fun shouldRejectWithSourceDenied_whenEveryMirrorReturns403() {
        val source = FakeHttpSource(statusOnly(403))

        val result = runBlocking { manager(source).download(spec(source.urls)) }

        assertEquals(AiModelFailureReason.SOURCE_DENIED, failureReason(result))
    }

    @Test
    fun shouldRejectWithTransport_whenTheMirrorTimesOutOrResets() {
        val source = FakeHttpSource(statusOnly(-1, failure = IOException("connection reset")))

        val result = runBlocking { manager(source).download(spec(source.urls)) }

        assertEquals(AiModelFailureReason.TRANSPORT, failureReason(result))
        assertEquals(AiModelProbeOutcome.FAILED, manager(source).lastProbeResult(spec(source.urls)).outcome)
    }

    @Test
    fun shouldRejectWithSizeMismatch_whenTheTransferEndsShort() {
        val short = payload.copyOfRange(0, payload.size - 1)
        val source = FakeHttpSource(statusOnly(200, body = short))

        val result = runBlocking { manager(source).download(spec(source.urls)) }

        assertEquals(AiModelFailureReason.SIZE_MISMATCH, failureReason(result))
        assertFalse(manager(source).partFile(spec(source.urls)).exists())
    }

    @Test
    fun shouldRejectWithIncompatible_whenTheSignatureCheckRefusesTheBytes() {
        val source = FakeHttpSource(statusOnly(200))
        val spec = spec(source.urls)
        val manager = AiModelDownloadManager(
            modelsRootDir = tempDir,
            http = source,
            ioDispatcher = Dispatchers.Unconfined,
            variantCompatibility = AiModelVariantCompatibility { _, _ -> false }
        )

        val result = runBlocking { manager.download(spec) }

        assertEquals(AiModelFailureReason.INCOMPATIBLE, failureReason(result))
        assertFalse(manager.partFile(spec).exists())
        assertFalse(manager.modelFile(spec).exists())
    }

    @Test
    fun shouldRejectWithStorageFailure_whenTheModelRootCannotBeCreated() {
        val unusableRoot = File(tempDir, "not-a-directory").apply { writeBytes(ByteArray(8)) }
        val source = FakeHttpSource(statusOnly(200))
        val manager = AiModelDownloadManager(
            modelsRootDir = unusableRoot,
            http = source,
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = runBlocking { manager.download(spec(source.urls)) }

        assertEquals(AiModelFailureReason.STORAGE_FAILURE, failureReason(result))
    }

    @Test
    fun shouldKeepThePartialFile_whenTheUserCancelsAndResumeLater() {
        val spec = spec(listOf(primary))
        val half = payload.size / 2
        manager(FakeHttpSource(emptyMap())).partFile(spec).apply {
            parentFile?.mkdirs()
            writeBytes(payload.copyOfRange(0, half))
        }
        val cancellation = AiModelDownloadCancellation().apply { cancel() }
        val source = FakeHttpSource(mapOf(primary to Mirror(payload)))

        val cancelled = runBlocking {
            manager(source).download(spec, cancellation = cancellation)
        }

        assertEquals(AiModelFailureReason.TRANSPORT, failureReason(cancelled))
        assertEquals(half.toLong(), manager(source).partFile(spec).length())
        assertEquals(AiModelProbeOutcome.CANCELLED, manager(source).lastProbeResult(spec).outcome)

        val resumed = runBlocking { manager(source).download(spec) }
        assertTrue(resumed.isSuccess)
        assertEquals(listOf(primary to half.toLong()), source.requests)
        assertTrue(checkNotNull(resumed.getOrNull()).readBytes().contentEquals(payload))
    }

    @Test
    fun shouldKeepTheBytesWrittenUntilCancellation_whenTheStreamPausesMidTransfer() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))
        val cancellation = AiModelDownloadCancellation()
        val source = FakeHttpSource(
            mapOf(primary to Mirror(payload, bodyOverride = { CancellingBody(payload, 1024, cancellation) }))
        )

        val result = runBlocking { manager(source).download(spec, cancellation = cancellation) }

        assertEquals(AiModelFailureReason.TRANSPORT, failureReason(result))
        assertTrue(manager.partFile(spec).length() >= 1024L)
        assertTrue(manager.partFile(spec).length() < payload.size.toLong())
    }

    @Test
    fun shouldKeepTheInstalledFile_whenARedownloadFails() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))
        manager.modelFile(spec).apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        val source = FakeHttpSource(statusOnly(-1, failure = IOException("offline")))

        val result = runBlocking {
            manager.download(spec, forceRedownload = true)
        }

        assertTrue(result.isFailure)
        assertEquals(AiModelFailureReason.TRANSPORT, failureReason(result))
        assertTrue(manager.modelFile(spec).isFile)
        assertTrue(manager.modelFile(spec).readBytes().contentEquals(payload))
        assertTrue(manager.isInstalled(spec))
    }

    @Test
    fun shouldKeepTheInstalledFileUntilTheReplacementIsVerified_whenRedownloading() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))
        manager.modelFile(spec).apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        val source = FakeHttpSource(
            mapOf(primary to Mirror(payload, onOpen = {
                assertTrue("installed file must survive the start of a redownload", manager.modelFile(spec).isFile)
            }))
        )

        val result = runBlocking {
            manager(source).download(spec, forceRedownload = true)
        }

        assertTrue(result.exceptionOrNull().toString(), result.isSuccess)
        assertTrue(manager.modelFile(spec).readBytes().contentEquals(payload))
    }

    @Test
    fun shouldReuseTheVerifiedModel_whenItIsAlreadyOnDiskAndNoRedownloadWasRequested() {
        val spec = spec(listOf(primary))
        manager(FakeHttpSource(emptyMap())).modelFile(spec).apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        val source = FakeHttpSource(mapOf(primary to Mirror(payload)))

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isSuccess)
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun shouldReplaceTheCachedModel_whenItsHashDoesNotMatch() {
        val spec = spec(listOf(primary))
        manager(FakeHttpSource(emptyMap())).modelFile(spec).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(payload.size) { 3 })
        }
        val source = FakeHttpSource(mapOf(primary to Mirror(payload)))

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isSuccess)
        assertTrue(checkNotNull(result.getOrNull()).readBytes().contentEquals(payload))
        assertEquals(listOf(primary to 0L), source.requests)
    }

    @Test
    fun shouldAcceptALocalImport_whenTheBytesAndShaMatch() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))

        val result = runBlocking { manager.importLocal(spec, ByteArrayInputStream(payload)) }

        assertTrue(result.isSuccess)
        assertTrue(manager.isInstalled(spec))
        assertFalse(manager.partFile(spec).exists())
        assertTrue(manager.lastProbeResult(spec).outcome != AiModelProbeOutcome.FAILED)
    }

    @Test
    fun shouldRejectALocalImport_whenTheShaDoesNotMatch() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))
        val tampered = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val result = runBlocking { manager.importLocal(spec, ByteArrayInputStream(tampered)) }

        assertTrue(result.isFailure)
        assertEquals(AiModelFailureReason.CHECKSUM_MISMATCH, failureReason(result))
        assertFalse(manager.modelFile(spec).exists())
        assertFalse(manager.partFile(spec).exists())
    }

    @Test
    fun shouldRejectALocalImport_whenTheStreamIsShorterThanThePinnedSize() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))

        val result = runBlocking {
            manager.importLocal(spec, ByteArrayInputStream(payload.copyOfRange(0, 256)))
        }

        assertTrue(result.isFailure)
        assertEquals(AiModelFailureReason.SIZE_MISMATCH, failureReason(result))
        assertFalse(manager.partFile(spec).exists())
    }

    @Test
    fun shouldRejectALocalImport_whenTheStreamIsLongerThanThePinnedSize() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))

        val result = runBlocking {
            manager.importLocal(spec, ByteArrayInputStream(payload + byteArrayOf(1, 2, 3)))
        }

        assertTrue(result.isFailure)
        assertEquals(AiModelFailureReason.SIZE_MISMATCH, failureReason(result))
        assertFalse(manager.partFile(spec).exists())
    }

    @Test
    fun shouldRejectALocalImport_whenTheSignatureCheckRefusesTheBytes() {
        val spec = spec(listOf(primary))
        val manager = AiModelDownloadManager(
            modelsRootDir = tempDir,
            http = FakeHttpSource(emptyMap()),
            ioDispatcher = Dispatchers.Unconfined,
            variantCompatibility = AiModelVariantCompatibility { _, _ -> false }
        )

        val result = runBlocking { manager.importLocal(spec, ByteArrayInputStream(payload)) }

        assertEquals(AiModelFailureReason.INCOMPATIBLE, failureReason(result))
        assertFalse(manager.modelFile(spec).exists())
    }

    @Test
    fun shouldAcceptTheLegacyPin_whenTheUserImportsAnOldGeneratorFile() {
        val pipeline = spec(
            listOf(primary),
            id = "migan-pipeline-test",
            fileName = "pipeline.onnx",
            byteSize = payload.size.toLong() * 2,
            sha256 = sha256Of(ByteArray(payload.size * 2) { 9 })
        )
        val legacy = spec(
            emptyList(),
            id = "migan-generator-test",
            fileName = "generator.onnx",
            byteSize = payload.size.toLong(),
            sha256 = sha256Of(payload)
        )
        val manager = manager(FakeHttpSource(emptyMap()))

        val result = runBlocking {
            manager.importLocalAny(listOf(pipeline, legacy), ByteArrayInputStream(payload))
        }

        assertTrue(result.isSuccess)
        assertTrue(manager.isInstalled(legacy))
        assertFalse(manager.isInstalled(pipeline))
        assertFalse(manager.modelFile(pipeline).exists())
        assertFalse(manager.modelFile(legacy).readBytes().isEmpty())
    }

    @Test
    fun shouldLeaveNoStagingFile_whenAnImportIsRejected() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))

        val result = runBlocking {
            manager.importLocal(spec, ByteArrayInputStream(payload.copyOfRange(0, 128)))
        }

        assertTrue(result.isFailure)
        assertTrue(
            "staging files must be cleaned up",
            tempDir.listFiles()?.none { it.name.startsWith(".import-") } != false
        )
    }

    @Test
    fun shouldReportTotalAndPerModelUsage_whenModelsAndPartialFilesExist() {
        val specA = spec(listOf(primary), id = "model-a")
        val specB = spec(listOf(primary), id = "model-b")
        manager(FakeHttpSource(emptyMap())).apply {
            modelDir(specA).mkdirs()
            modelFile(specA).writeBytes(ByteArray(100))
            partFile(specA).writeBytes(ByteArray(50))
            modelDir(specB).mkdirs()
            modelFile(specB).writeBytes(ByteArray(200))
            File(tempDir, "selfie_multiclass_256x256.tflite").writeBytes(ByteArray(999))
        }
        val manager = manager(FakeHttpSource(emptyMap()))

        assertEquals(150L, manager.bytesOnDisk(specA))
        assertEquals(350L, manager.totalBytesOnDisk())
    }

    @Test
    fun shouldDeleteOnlyTheRequestedModel_whenDeleting() {
        val specA = spec(listOf(primary), id = "model-a")
        val specB = spec(listOf(primary), id = "model-b")
        val manager = manager(FakeHttpSource(emptyMap()))
        manager.modelFile(specA).apply { parentFile?.mkdirs(); writeBytes(payload) }
        manager.modelFile(specB).apply { parentFile?.mkdirs(); writeBytes(payload) }

        assertTrue(manager.delete(specA))

        assertFalse(manager.modelDir(specA).exists())
        assertTrue(manager.isInstalled(specB))
    }

    @Test
    fun shouldRemoveStaleVersionFilesButKeepTheProbeJournal_whenANewVersionIsInstalled() {
        val source = FakeHttpSource(mapOf(primary to Mirror(payload)))
        val specV2 = spec(listOf(primary), fileName = "model_v2.onnx")
        val manager = manager(source)
        File(manager.modelDir(specV2), "model_v1.onnx").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64))
        }
        File(manager.modelDir(specV2), "model_v1.onnx.part").writeBytes(ByteArray(32))

        val result = runBlocking { manager.download(specV2) }

        assertTrue(result.isSuccess)
        assertEquals(
            setOf("model_v2.onnx", ".probe.properties"),
            manager.modelDir(specV2).listFiles()?.map { it.name }?.toSet()
        )
    }

    @Test
    fun shouldRejectDownload_whenTheCatalogEntryIsNotPinned() {
        val source = FakeHttpSource(emptyMap())
        val spec = spec(emptyList(), byteSize = 0L, sha256 = "")

        val result = runBlocking { manager(source).download(spec) }

        assertTrue(result.isFailure)
        assertEquals(AiModelFailureReason.NOT_AVAILABLE, failureReason(result))
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun shouldRejectDownload_whenTheReadOnlyLegacyEntryHasNoMirrors() {
        val source = FakeHttpSource(emptyMap())
        val legacy = spec(emptyList(), id = "legacy", byteSize = payload.size.toLong())

        val result = runBlocking { manager(source).download(legacy) }

        assertEquals(AiModelFailureReason.NOT_AVAILABLE, failureReason(result))
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun shouldPersistTheLastProbeAcrossManagerInstances_whenASourceGoesGone() {
        val source = FakeHttpSource(statusOnly(404))
        val spec = spec(source.urls)

        runBlocking { manager(source).download(spec) }

        val reread = AiModelDownloadManager(
            modelsRootDir = tempDir,
            http = FakeHttpSource(emptyMap()),
            ioDispatcher = Dispatchers.Unconfined
        ).lastProbeResult(spec)
        assertEquals(AiModelProbeOutcome.FAILED, reread.outcome)
        assertEquals(AiModelFailureReason.SOURCE_GONE, reread.failureReason)
        assertTrue(reread.sourceGone)
    }

    @Test
    fun shouldAcceptOnlyOnnxSuffixes_whenValidatingImportNames() {
        assertTrue(isOnnxModelFileName("model.onnx"))
        assertTrue(isOnnxModelFileName("MODEL.ONNX"))
        assertFalse(isOnnxModelFileName("model.tflite"))
        assertFalse(isOnnxModelFileName(null))
    }

    private fun spec(
        mirrors: List<String>,
        id: String = "test-model",
        fileName: String = "model_v2.onnx",
        byteSize: Long = payload.size.toLong(),
        sha256: String = sha256Of(payload)
    ): AiModelSpec = AiModelSpec(
        id = id,
        version = "2",
        fileName = fileName,
        mirrorUrls = mirrors,
        byteSize = byteSize,
        sha256 = sha256,
        license = AiModelLicense("Apache-2.0", "https://example.invalid/LICENSE")
    )

    private fun manager(source: ModelHttpSource): AiModelDownloadManager = AiModelDownloadManager(
        modelsRootDir = tempDir,
        http = source,
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun failureReason(result: Result<File>): AiModelFailureReason? =
        (result.exceptionOrNull() as? AiModelInstallException)?.reason

    private fun statusOnly(
        statusCode: Int,
        body: ByteArray = payload,
        failure: IOException? = null
    ): Map<String, Mirror> = linkedMapOf(
        primary to Mirror(body, failure = failure, statusCode = statusCode),
        secondary to Mirror(body, failure = failure, statusCode = statusCode)
    )

    private class Mirror(
        val payload: ByteArray = ByteArray(0),
        val failure: IOException? = null,
        val statusCode: Int = 200,
        val onOpen: (() -> Unit)? = null,
        val bodyOverride: ((Long) -> InputStream)? = null
    )

    private class FakeHttpSource(
        private val mirrors: Map<String, Mirror>,
        private val supportsRange: Boolean = true
    ) : ModelHttpSource {

        val requests = mutableListOf<Pair<String, Long>>()

        /** Mirrors registered by helper builders, in request order. */
        val urls: List<String>
            get() = mirrors.keys.toList()

        override fun open(url: String, rangeStart: Long): ModelHttpResponse {
            requests += url to rangeStart
            val mirror = mirrors[url] ?: throw IOException("No mirror registered for $url")
            mirror.onOpen?.invoke()
            mirror.failure?.let { throw it }
            if (mirror.statusCode !in 200..299) {
                return ModelHttpResponse(
                    statusCode = mirror.statusCode,
                    totalBytes = 0L,
                    body = streamOf(ByteArray(0))
                )
            }
            mirror.bodyOverride?.let { factory ->
                return ModelHttpResponse(200, mirror.payload.size.toLong(), factory(rangeStart))
            }
            if (!supportsRange || rangeStart <= 0L) {
                return ModelHttpResponse(
                    statusCode = 200,
                    totalBytes = mirror.payload.size.toLong(),
                    body = streamOf(mirror.payload)
                )
            }
            val sliced = mirror.payload.copyOfRange(rangeStart.toInt(), mirror.payload.size)
            return ModelHttpResponse(206, sliced.size.toLong(), streamOf(sliced))
        }

        private fun streamOf(bytes: ByteArray): InputStream = ByteArrayInputStream(bytes)
    }

    /**
     * Delivers [limit] bytes, then requests a pause, so the manager must keep exactly what it wrote.
     */
    private class CancellingBody(
        private val payload: ByteArray,
        private val limit: Int,
        private val cancellation: AiModelDownloadCancellation
    ) : InputStream() {

        private var index = 0

        override fun read(): Int = read(ByteArray(1), 0, 1)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= limit) {
                cancellation.cancel()
                return -1
            }
            val count = minOf(length, limit - index)
            payload.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }
    }

    private companion object {
        fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
