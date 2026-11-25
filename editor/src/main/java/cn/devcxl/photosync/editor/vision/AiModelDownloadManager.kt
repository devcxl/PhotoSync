package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Why an [AiModelDownloadManager] install failed; every reason is recoverable. */
enum class AiModelFailureReason {

    /** The catalog entry has no pinned artifact yet, so there is nothing to verify against. */
    NOT_AVAILABLE,

    /** Every mirror failed at the transport level. */
    DOWNLOAD_FAILED,

    /** The transferred or imported file did not have the exact pinned byte count. */
    SIZE_MISMATCH,

    /** The file had the right size but a different SHA-256, so it may be corrupted or tampered. */
    CHECKSUM_MISMATCH,

    /** The model directory could not be created, written or renamed. */
    STORAGE_FAILURE
}

/**
 * Recoverable failure returned by [AiModelDownloadManager].
 *
 * @property reason Machine-readable cause the settings page maps to a localized hint.
 */
class AiModelInstallException(
    val reason: AiModelFailureReason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Downloads and imports on-demand `.onnx` models with one shared integrity contract.
 *
 * ## Storage
 * Every model lives in `<modelsRoot>/<model-id>/<fileName>`. The sibling `.part` file survives a
 * failed attempt and is resumed with a `Range` header. After a successful install every other file
 * in the model directory is deleted, so bumping [AiModelSpec.version] cannot leave orphan artifacts.
 *
 * ## Integrity
 * A file becomes visible as the model only after both the exact [AiModelSpec.byteSize] and the
 * pinned [AiModelSpec.sha256] match and the `.part` file was atomically renamed. The same check
 * runs for downloads and for SAF imports, so a tampered local file is rejected identically.
 *
 * ## Mirrors
 * [AiModelSpec.mirrorUrls] is tried in order. A timeout, transport error, wrong size or wrong hash
 * moves on to the next mirror; only when no mirror yields a verifiable file does [download] fail.
 *
 * Blocking work runs on [ioDispatcher]; the class itself has no Android dependency.
 */
class AiModelDownloadManager(
    private val modelsRootDir: File,
    private val http: ModelHttpSource = HttpUrlConnectionSource(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val digest: (File) -> String = ::sha256Of
) {

    /** The per-model directory under `<modelsRoot>/<model-id>/`. */
    fun modelDir(spec: AiModelSpec): File = File(modelsRootDir, spec.id)

    /** The final verified model path, whether or not it exists yet. */
    fun modelFile(spec: AiModelSpec): File = File(modelDir(spec), spec.fileName)

    /** The partial download path kept across attempts for the `Range` resume. */
    fun partFile(spec: AiModelSpec): File = File(modelDir(spec), spec.partFileName)

    /**
     * Cheap installed check used by the settings list: the final file exists with the pinned size.
     *
     * The SHA-256 is not recomputed here because that would hash tens of megabytes on every list
     * refresh; [download] verifies it before reusing a file.
     */
    fun isInstalled(spec: AiModelSpec): Boolean {
        val file = modelFile(spec)
        return spec.isPinned && file.isFile && file.length() == spec.byteSize
    }

    /** Bytes currently occupied by [spec], including a partial download. */
    fun bytesOnDisk(spec: AiModelSpec): Long = modelDir(spec).totalBytes()

    /**
     * Bytes occupied by every model directory under [modelsRootDir].
     *
     * Loose files at the root are ignored on purpose: the existing MediaPipe portrait model lives
     * there as a single `.tflite` file and is not managed by this catalog.
     */
    fun totalBytesOnDisk(): Long =
        modelsRootDir.listFiles()?.filter { it.isDirectory }?.sumOf { it.totalBytes() } ?: 0L

    /**
     * Ensures [spec] is installed, resuming a partial transfer or falling back across mirrors.
     *
     * @param onProgress Receives the completion ratio in `[0, 1]`; called on [ioDispatcher].
     * @return The verified model file, or a failure carrying [AiModelInstallException].
     */
    suspend fun download(
        spec: AiModelSpec,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(ioDispatcher) {
        if (!spec.isPinned) {
            return@withContext installFailure(AiModelFailureReason.NOT_AVAILABLE)
        }
        val cached = verifiedFileOnDisk(spec)
        if (cached != null) {
            onProgress(1f)
            return@withContext Result.success(cached)
        }
        if (!ensureModelDir(spec)) {
            return@withContext installFailure(AiModelFailureReason.STORAGE_FAILURE)
        }
        discardInvalidFinalFile(spec)
        var lastVerificationFailure: AiModelFailureReason? = null
        try {
            for (url in spec.mirrorUrls) {
                if (!transfer(spec, url, onProgress)) continue
                val failure = verifyPartFile(spec)
                if (failure != null) {
                    lastVerificationFailure = failure
                    partFile(spec).delete()
                    Timber.w("Model mirror %s produced an invalid file (%s)", url, failure)
                    continue
                }
                if (!promotePartFile(spec)) {
                    return@withContext installFailure(AiModelFailureReason.STORAGE_FAILURE)
                }
                deleteStaleFiles(spec)
                return@withContext Result.success(modelFile(spec))
            }
            installFailure(lastVerificationFailure ?: AiModelFailureReason.DOWNLOAD_FAILED)
        } finally {
            runCatching { http.close() }
                .onFailure { Timber.w(it, "Failed to close the model HTTP source") }
        }
    }

    /**
     * Imports a user-selected `.onnx` file through the same size and SHA-256 checks as [download].
     *
     * The SAF picker hands over an [InputStream], so this method stays free of Android `Uri`
     * handling. A rejected import leaves neither a `.part` nor a final file behind.
     *
     * @param onProgress Receives the completion ratio in `[0, 1]`; called on [ioDispatcher].
     * @return The verified model file, or a failure carrying [AiModelInstallException].
     */
    suspend fun importLocal(
        spec: AiModelSpec,
        input: InputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(ioDispatcher) {
        if (!spec.isPinned) {
            return@withContext installFailure(AiModelFailureReason.NOT_AVAILABLE)
        }
        if (!ensureModelDir(spec)) {
            return@withContext installFailure(AiModelFailureReason.STORAGE_FAILURE)
        }
        partFile(spec).delete()
        try {
            FileOutputStream(partFile(spec)).use { output ->
                copyBounded(spec, input, output, onProgress)?.let { failure ->
                    partFile(spec).delete()
                    return@withContext installFailure(failure)
                }
            }
        } catch (e: IOException) {
            partFile(spec).delete()
            Timber.e(e, "Failed to copy the selected ONNX file into the model directory")
            return@withContext installFailure(AiModelFailureReason.STORAGE_FAILURE, e)
        }
        verifyPartFile(spec)?.let { failure ->
            partFile(spec).delete()
            return@withContext installFailure(failure)
        }
        if (!promotePartFile(spec)) {
            return@withContext installFailure(AiModelFailureReason.STORAGE_FAILURE)
        }
        deleteStaleFiles(spec)
        Result.success(modelFile(spec))
    }

    /**
     * Deletes one model directory, including a partial download.
     *
     * Saved edit records are independent of this directory, so deleting a model never invalidates
     * them; the model is simply downloaded again the next time its tool is used.
     *
     * @return `true` when the directory is gone afterwards.
     */
    fun delete(spec: AiModelSpec): Boolean {
        val directory = modelDir(spec)
        if (!directory.exists()) return true
        return directory.deleteRecursively()
    }

    private fun copyBounded(
        spec: AiModelSpec,
        input: InputStream,
        output: FileOutputStream,
        onProgress: (Float) -> Unit
    ): AiModelFailureReason? {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (written + read > spec.byteSize) {
                Timber.w("Local model import is larger than the pinned %d bytes", spec.byteSize)
                return AiModelFailureReason.SIZE_MISMATCH
            }
            output.write(buffer, 0, read)
            written += read
            onProgress((written.toFloat() / spec.byteSize.toFloat()).coerceIn(0f, 1f))
        }
        output.flush()
        return null
    }

    private fun verifiedFileOnDisk(spec: AiModelSpec): File? {
        val file = modelFile(spec)
        if (!file.isFile) return null
        if (file.length() != spec.byteSize) {
            file.delete()
            return null
        }
        if (!digest(file).equals(spec.sha256, ignoreCase = true)) {
            Timber.w("Cached model failed its SHA-256 check; deleting it")
            file.delete()
            return null
        }
        return file
    }

    private fun discardInvalidFinalFile(spec: AiModelSpec) {
        val file = modelFile(spec)
        if (file.isFile && file.length() != spec.byteSize) file.delete()
    }

    private fun transfer(spec: AiModelSpec, url: String, onProgress: (Float) -> Unit): Boolean {
        var response: ModelHttpResponse? = null
        return try {
            val resumeFrom = partFile(spec).takeIf { it.isFile }?.length() ?: 0L
            val canResume = resumeFrom in 1L until spec.byteSize
            if (resumeFrom > 0L && !canResume) partFile(spec).delete()
            val start = if (canResume) resumeFrom else 0L
            response = http.open(url, start)
            val append = start > 0L && response.statusCode == HTTP_PARTIAL_CONTENT
            if (!append) partFile(spec).delete()
            writeBody(spec, response.body, if (append) start else 0L, onProgress)
            true
        } catch (e: IOException) {
            Timber.w(e, "Model mirror failed; keeping the partial file for the next source")
            false
        } catch (e: SecurityException) {
            Timber.w(e, "Model download was denied by the platform")
            false
        } finally {
            runCatching { response?.body?.close() }
                .onFailure { Timber.w(it, "Failed to close a model response body") }
        }
    }

    private fun writeBody(
        spec: AiModelSpec,
        body: InputStream,
        alreadyWritten: Long,
        onProgress: (Float) -> Unit
    ) {
        FileOutputStream(partFile(spec), alreadyWritten > 0L).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var written = alreadyWritten
            while (written < spec.byteSize) {
                val read = body.read(buffer)
                if (read <= 0) break
                val allowed = minOf(read.toLong(), spec.byteSize - written).toInt()
                output.write(buffer, 0, allowed)
                written += allowed
                onProgress((written.toFloat() / spec.byteSize.toFloat()).coerceIn(0f, 1f))
            }
            output.flush()
        }
    }

    private fun verifyPartFile(spec: AiModelSpec): AiModelFailureReason? {
        val part = partFile(spec)
        if (!part.isFile || part.length() != spec.byteSize) return AiModelFailureReason.SIZE_MISMATCH
        if (!digest(part).equals(spec.sha256, ignoreCase = true)) {
            return AiModelFailureReason.CHECKSUM_MISMATCH
        }
        return null
    }

    private fun promotePartFile(spec: AiModelSpec): Boolean {
        val part = partFile(spec)
        val finalFile = modelFile(spec)
        if (part.renameTo(finalFile)) return true
        // Some filesystems refuse to replace an existing file; copy-then-delete keeps the same
        // bytes and still leaves the verified final file in place.
        return try {
            part.copyTo(finalFile, overwrite = true).isFile && part.delete()
        } catch (e: IOException) {
            Timber.e(e, "Failed to move the verified model into place")
            false
        }
    }

    private fun deleteStaleFiles(spec: AiModelSpec) {
        modelDir(spec).listFiles()?.forEach { file ->
            if (file.name == spec.fileName) return@forEach
            if (!file.deleteRecursively()) {
                Timber.w("Failed to delete a stale model file in %s", spec.id)
            }
        }
    }

    private fun ensureModelDir(spec: AiModelSpec): Boolean {
        if (modelsRootDir.exists() && modelDir(spec).exists()) return true
        val created = modelDir(spec).mkdirs()
        if (!created) Timber.e("Model directory could not be created for %s", spec.id)
        return created || modelDir(spec).isDirectory
    }

    private fun installFailure(
        reason: AiModelFailureReason,
        cause: Throwable? = null
    ): Result<File> = Result.failure(
        AiModelInstallException(reason, "AI model install failed: $reason", cause)
    )

    private fun File.totalBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.sumOf { it.totalBytes() } ?: 0L
    }

    private companion object {
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val BUFFER_BYTES = 64 * 1024

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }
    }
}
