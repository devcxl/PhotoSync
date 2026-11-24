package cn.devcxl.photosync.editor.vision

import java.io.ByteArrayInputStream
import java.io.File
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
 * Seam under test: [ModelDownloadManager] — SHA-256 verification, corrupted-download cleanup and
 * `Range` resume, driven through a fake [ModelHttpSource] so nothing touches the network.
 */
class ModelDownloadManagerTest {

    private val payload = ByteArray(4096) { index -> (index % 251).toByte() }
    private val tempDir: File = Files.createTempDirectory("portrait-model").toFile()
    private val spec = PortraitModelSpec(
        fileName = "selfie_multiclass_256x256.tflite",
        downloadUrl = "https://example.invalid/model.tflite",
        byteSize = payload.size.toLong(),
        sha256 = sha256Of(payload)
    )

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldRejectAndDeleteTheCorruptedDownload_whenTheSha256DoesNotMatch() {
        val corrupted = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val source = FakeHttpSource(corrupted, supportsRange = true)
        val manager = manager(source)

        val result = runBlocking { manager.ensureModel() }

        assertNull(result)
        assertFalse(manager.modelFile().exists())
        assertFalse(manager.partFile().exists())
        assertFalse(manager.isDownloaded)
    }

    @Test
    fun shouldResumeFromThePartialFile_whenAPreviousDownloadWasInterrupted() {
        val half = payload.size / 2
        manager(FakeHttpSource(ByteArray(0), supportsRange = true)).partFile()
            .writeBytes(payload.copyOfRange(0, half))
        val source = FakeHttpSource(payload, supportsRange = true)
        val manager = manager(source)

        val result = runBlocking { manager.ensureModel() }

        assertNotNull(result)
        assertEquals(listOf(half.toLong()), source.requestedRanges)
        assertTrue(checkNotNull(result).readBytes().contentEquals(payload))
        assertFalse(manager.partFile().exists())
        assertTrue(manager.isDownloaded)
    }

    @Test
    fun shouldRestartFromZero_whenTheServerIgnoresTheRangeRequest() {
        manager(FakeHttpSource(ByteArray(0), supportsRange = true)).partFile()
            .writeBytes(payload.copyOfRange(0, 512))
        val source = FakeHttpSource(payload, supportsRange = false)
        val manager = manager(source)

        val result = runBlocking { manager.ensureModel() }

        assertNotNull(result)
        assertEquals(listOf(512L), source.requestedRanges)
        assertTrue(checkNotNull(result).readBytes().contentEquals(payload))
    }

    @Test
    fun shouldReuseTheVerifiedModel_whenItIsAlreadyOnDisk() {
        manager(FakeHttpSource(ByteArray(0), supportsRange = true)).modelFile().writeBytes(payload)
        val source = FakeHttpSource(payload, supportsRange = true)
        val manager = manager(source)

        val result = runBlocking { manager.ensureModel() }

        assertNotNull(result)
        assertTrue(checkNotNull(result).readBytes().contentEquals(payload))
        assertTrue(source.requestedRanges.isEmpty())
    }

    @Test
    fun shouldDeleteACachedModelWithTheWrongSize_whenVerifyingOnDisk() {
        val manager = manager(FakeHttpSource(payload, supportsRange = true))
        manager.modelFile().writeBytes(payload.copyOfRange(0, 128))

        val result = runBlocking { manager.ensureModel() }

        assertNotNull(result)
        assertEquals(payload.size.toLong(), manager.modelFile().length())
        assertTrue(checkNotNull(result).readBytes().contentEquals(payload))
    }

    @Test
    fun shouldReportProgressUpToOne_whenTheDownloadCompletes() {
        val progress = mutableListOf<Float>()
        val manager = manager(FakeHttpSource(payload, supportsRange = true))

        runBlocking { manager.ensureModel { progress += it } }

        assertTrue(progress.isNotEmpty())
        assertEquals(1.0f, progress.last(), 1e-6f)
    }

    private fun manager(source: ModelHttpSource): ModelDownloadManager = ModelDownloadManager(
        modelDir = tempDir,
        spec = spec,
        http = source,
        ioDispatcher = Dispatchers.Unconfined
    )

    private class FakeHttpSource(
        private val payload: ByteArray,
        private val supportsRange: Boolean
    ) : ModelHttpSource {

        val requestedRanges = mutableListOf<Long>()

        override fun open(url: String, rangeStart: Long): ModelHttpResponse {
            requestedRanges += rangeStart
            if (!supportsRange || rangeStart <= 0L) {
                return ModelHttpResponse(200, payload.size.toLong(), streamOf(payload))
            }
            val sliced = payload.copyOfRange(rangeStart.toInt(), payload.size)
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
