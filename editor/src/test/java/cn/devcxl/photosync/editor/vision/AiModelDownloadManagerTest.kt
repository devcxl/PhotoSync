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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [AiModelDownloadManager] — ordered mirror fallback, `Range` resume, SHA-256
 * rejection and SAF import, driven through a fake [ModelHttpSource] so nothing touches the network.
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
    fun shouldReuseTheVerifiedModel_whenItIsAlreadyOnDisk() {
        val spec = spec(listOf(primary))
        val manager = manager(FakeHttpSource(emptyMap()))
        manager.modelFile(spec).apply {
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
        val manager = manager(FakeHttpSource(emptyMap()))
        manager.modelFile(spec).apply {
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
    fun shouldRemoveStaleVersionFiles_whenANewVersionIsInstalled() {
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
            listOf("model_v2.onnx"),
            manager.modelDir(specV2).listFiles()?.map { it.name }
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

    private class Mirror(
        val payload: ByteArray = ByteArray(0),
        val failure: IOException? = null
    )

    private class FakeHttpSource(
        private val mirrors: Map<String, Mirror>,
        private val supportsRange: Boolean = true
    ) : ModelHttpSource {

        val requests = mutableListOf<Pair<String, Long>>()

        override fun open(url: String, rangeStart: Long): ModelHttpResponse {
            requests += url to rangeStart
            val mirror = mirrors[url] ?: throw IOException("No mirror registered for $url")
            mirror.failure?.let { throw it }
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

    private companion object {
        fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
