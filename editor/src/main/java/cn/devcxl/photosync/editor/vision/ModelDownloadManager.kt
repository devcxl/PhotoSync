package cn.devcxl.photosync.editor.vision

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Response of one ranged model request.
 *
 * @property statusCode HTTP status; `206` means [body] starts at the requested offset, `200` means
 *   the server ignored the `Range` header and [body] starts at zero.
 * @property totalBytes Value of `Content-Length` for this response.
 * @property body Response stream; for a non-2xx status it is the error stream (possibly empty), so
 *   the caller can classify the status instead of receiving a folded `IOException`.
 */
class ModelHttpResponse(
    val statusCode: Int,
    val totalBytes: Long,
    val body: InputStream
) {

    /** True when [statusCode] is in the 2xx range. */
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

/**
 * Transport seam of [ModelDownloadManager].
 *
 * Keeping the HTTP surface this small is what lets the downloader (including its Range resume and
 * SHA-256 cleanup) be exercised by pure JVM unit tests without touching a socket.
 */
interface ModelHttpSource {

    /**
     * Opens [url] asking for bytes starting at [rangeStart] (`0` requests the whole file).
     *
     * HTTP error statuses are returned in [ModelHttpResponse.statusCode] so callers can classify
     * 404/410 and 401/403; only transport failures (DNS, timeout, reset) are thrown as exceptions.
     */
    fun open(url: String, rangeStart: Long): ModelHttpResponse

    /** Releases any transport resources. */
    fun close() {}
}

/**
 * [ModelHttpSource] backed by `HttpURLConnection`, the only HTTP stack that ships with `minSdk 24`.
 *
 * The response object carries the real HTTP status for error responses too; the classes above map
 * 404/410 and 401/403 to their own failure reasons instead of collapsing every non-2xx into a
 * generic transport error.
 */
class HttpUrlConnectionSource(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) : ModelHttpSource {

    override fun open(url: String, rangeStart: Long): ModelHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        if (rangeStart > 0L) connection.setRequestProperty("Range", "bytes=$rangeStart-")
        val status = connection.responseCode
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }
        return ModelHttpResponse(
            statusCode = status,
            totalBytes = connection.contentLengthLong,
            body = stream
        )
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

/**
 * Downloads the optional 16.37 MB six-class segmentation model on demand.
 *
 * ## Contract
 * - **Resume**: bytes already in `<filesDir>/models/<name>.part` are kept and requested with a
 *   `Range` header, so a dropped connection never restarts the 16 MB transfer.
 * - **Integrity**: the completed `.part` must match both the expected byte count and the pinned
 *   SHA-256 before the atomic rename; any mismatch deletes the file so a corrupt model can never be
 *   handed to MediaPipe.
 * - **Single flight**: callers share one `ensureModel` invocation; the manager itself is not
 *   re-entrant across processes, which is acceptable because the model directory is app-private.
 *
 * Blocking work runs on [ioDispatcher]; the class itself has no Android dependency.
 */
class ModelDownloadManager(
    private val modelDir: File,
    val spec: PortraitModelSpec = PortraitModelSpec.MULTICLASS_SELFIE,
    private val http: ModelHttpSource = HttpUrlConnectionSource(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val digest: (File) -> String = ::sha256Of
) {

    private val modelFile = File(modelDir, spec.fileName)
    private val partFile = File(modelDir, spec.partFileName)

    @Volatile
    private var verified: Boolean = false

    /** True when the verified model is on disk and ready to be loaded. */
    val isDownloaded: Boolean
        get() = verified && modelFile.isFile && modelFile.length() == spec.byteSize

    /** The final model path, whether or not it has been downloaded yet. */
    fun modelFile(): File = modelFile

    /** The partial download path, kept across sessions for the Range resume. */
    fun partFile(): File = partFile

    /**
     * Ensures the model exists and passes verification, downloading or resuming as needed.
     *
     * @param onProgress Receives the completion ratio in `[0, 1]`.
     * @return The verified model file, or `null` when the download or its verification failed.
     */
    suspend fun ensureModel(onProgress: (Float) -> Unit = {}): File? = withContext(ioDispatcher) {
        if (isVerifiedOnDisk()) {
            onProgress(1.0f)
            return@withContext modelFile
        }
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            Timber.e("Portrait model directory could not be created")
            return@withContext null
        }
        discardInvalidFinalFile()
        val resumeFrom = partFile.takeIf { it.isFile }?.length() ?: 0L
        val usableResume = resumeFrom in 1L until spec.byteSize
        if (resumeFrom >= spec.byteSize && !usableResume) partFile.delete()
        val start = if (usableResume) resumeFrom else {
            partFile.delete()
            0L
        }
        download(start, onProgress)
    }

    /** Drops both files; used by the portrait coordinator when the user clears downloaded data. */
    fun clear() {
        verified = false
        modelFile.delete()
        partFile.delete()
    }

    private fun isVerifiedOnDisk(): Boolean {
        if (verified) return modelFile.isFile && modelFile.length() == spec.byteSize
        if (!modelFile.isFile || modelFile.length() != spec.byteSize) return false
        if (!digest(modelFile).equals(spec.sha256, ignoreCase = true)) {
            Timber.w("Cached portrait model failed its SHA-256 check; deleting it")
            modelFile.delete()
            return false
        }
        verified = true
        return true
    }

    private fun discardInvalidFinalFile() {
        if (modelFile.isFile && modelFile.length() != spec.byteSize) modelFile.delete()
    }

    private fun download(start: Long, onProgress: (Float) -> Unit): File? {
        var response: ModelHttpResponse? = null
        try {
            response = http.open(spec.downloadUrl, start)
            val append = start > 0L && response.statusCode == HTTP_PARTIAL_CONTENT
            val written = if (append) start else 0L
            if (!append) partFile.delete()
            appendBody(response.body, written, onProgress)
        } catch (e: IOException) {
            Timber.w(e, "Portrait model download failed; keeping the partial file for a resume")
            return null
        } catch (e: SecurityException) {
            Timber.w(e, "Portrait model download was denied by the platform")
            return null
        } finally {
            runCatching { response?.body?.close() }
                .onFailure { Timber.w(it, "Failed to close model response body") }
            runCatching { http.close() }
                .onFailure { Timber.w(it, "Failed to close model HTTP source") }
        }
        return verifyAndPromote()
    }

    private fun appendBody(body: InputStream, alreadyWritten: Long, onProgress: (Float) -> Unit) {
        FileOutputStream(partFile, alreadyWritten > 0L).use { output ->
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

    private fun verifyAndPromote(): File? {
        if (!partFile.isFile || partFile.length() != spec.byteSize) {
            Timber.w("Portrait model transfer ended at %d of %d bytes", partFile.length(), spec.byteSize)
            partFile.delete()
            return null
        }
        if (!digest(partFile).equals(spec.sha256, ignoreCase = true)) {
            Timber.e("Portrait model SHA-256 mismatch; the corrupted download was deleted")
            partFile.delete()
            return null
        }
        if (!partFile.renameTo(modelFile)) {
            // Some filesystems refuse to replace an existing file; the final copy carries the same
            // bytes, so falling back to copy-then-delete keeps the atomicity contract intact.
            if (!partFile.copyTo(modelFile, overwrite = true).isFile) {
                partFile.delete()
                return null
            }
            partFile.delete()
        }
        verified = true
        return modelFile
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
