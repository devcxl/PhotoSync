package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Downloads and imports on-demand `.onnx` models with one shared integrity contract.
 *
 * ## Storage
 * Every model lives in `<modelsRoot>/<model-id>/<fileName>`. The sibling `.part` file survives a
 * failed attempt, a user cancel and a cancelled coroutine, and is resumed with a `Range` header.
 * After a successful install every other file in the model directory except the probe journal is
 * deleted, so bumping [AiModelSpec.version] cannot leave orphan artifacts.
 *
 * ## Integrity
 * A file becomes visible as the model only after both the exact [AiModelSpec.byteSize] and the
 * pinned [AiModelSpec.sha256] match, the optional [AiModelVariantCompatibility] check passes, and
 * the `.part` file was atomically renamed over the final path. The same checks run for downloads
 * and for SAF imports, so a tampered local file is rejected identically.
 *
 * ## Mirrors
 * [AiModelSpec.mirrorUrls] is tried in order. HTTP status codes are classified first (404/410 →
 * [AiModelFailureReason.SOURCE_GONE], 401/403 → [AiModelFailureReason.SOURCE_DENIED]), then
 * transport and verification failures; only when no mirror yields a verifiable file does [download]
 * fail. Every terminal attempt is recorded in [AiModelProbeStore].
 *
 * ## Replacement
 * A verified installed file is never deleted before a replacement has been downloaded and verified.
 * The `.part` is promoted over it only after the pinned bytes and SHA-256 match, and callers must
 * explicitly pass `forceRedownload = true` after their own confirmation.
 *
 * Blocking work runs on [ioDispatcher]; the class itself has no Android dependency.
 */
class AiModelDownloadManager(
    private val modelsRootDir: File,
    private val http: ModelHttpSource = HttpUrlConnectionSource(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val digest: (File) -> String = AiModelTransferHelper::sha256Of,
    private val variantCompatibility: AiModelVariantCompatibility = AiModelVariantCompatibility.ANY
) {

    private val probes = AiModelProbeStore(modelsRootDir)

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
        return spec.hasPinnedArtifact && file.isFile && file.length() == spec.byteSize
    }

    /** The last real probe recorded for [spec], or a "not probed" result. */
    fun lastProbeResult(spec: AiModelSpec): AiModelProbeResult = probes.read(spec)

    /** Bytes currently occupied by [spec], including a partial download. */
    fun bytesOnDisk(spec: AiModelSpec): Long = AiModelTransferHelper.totalBytes(modelDir(spec))

    /**
     * Bytes occupied by every model directory under [modelsRootDir].
     *
     * Loose files at the root are ignored on purpose: the existing MediaPipe portrait model lives
     * there as a single `.tflite` file and is not managed by this catalog.
     */
    fun totalBytesOnDisk(): Long =
        modelsRootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sumOf { AiModelTransferHelper.totalBytes(it) }
            ?: 0L

    /**
     * Ensures [spec] is installed, resuming a partial transfer or falling back across mirrors.
     *
     * @param onProgress Receives the completion ratio in `[0, 1]`; called on [ioDispatcher].
     * @param forceRedownload Downloads even when a verified file is already installed. The old file
     *   stays in place until the replacement passed both checks.
     * @param cancellation Optional user-controlled pause flag; the kept `.part` makes the next call
     *   resume.
     * @return The verified model file, or a failure carrying [AiModelInstallException].
     */
    suspend fun download(
        spec: AiModelSpec,
        onProgress: (Float) -> Unit = {},
        forceRedownload: Boolean = false,
        cancellation: AiModelDownloadCancellation? = null
    ): Result<File> = withContext(ioDispatcher) {
        if (!spec.isDownloadable) {
            return@withContext installFailure(AiModelFailureReason.NOT_AVAILABLE)
        }
        if (!forceRedownload) {
            val cached = verifiedFileOnDisk(spec)
            if (cached != null) {
                onProgress(1f)
                return@withContext Result.success(cached)
            }
        }
        if (!ensureModelDir(spec)) {
            probes.record(spec, AiModelProbeOutcome.FAILED, AiModelFailureReason.STORAGE_FAILURE)
            return@withContext installFailure(AiModelFailureReason.STORAGE_FAILURE)
        }
        if (!forceRedownload) discardInvalidFinalFile(spec)
        val failures = mutableListOf<AiModelFailureReason>()
        var wasCancelled = false
        try {
            for (url in spec.mirrorUrls) {
                currentCoroutineContext().ensureActive()
                if (cancellation?.isCancelled == true) {
                    wasCancelled = true
                    break
                }
                when (val outcome = transfer(spec, url, onProgress, cancellation)) {
                    TransferOutcome.SUCCESS -> {
                        val failure = verifyPartFile(spec)
                        if (failure != null) {
                            failures += failure
                            partFile(spec).delete()
                            Timber.w("Model mirror %s produced an invalid file (%s)", url, failure)
                            continue
                        }
                        if (!variantCompatibility.accepts(partFile(spec), spec.variantHint)) {
                            failures += AiModelFailureReason.INCOMPATIBLE
                            partFile(spec).delete()
                            Timber.w("Model mirror %s served a file with an unexpected signature", url)
                            continue
                        }
                        if (!promotePartFile(spec)) {
                            probes.record(
                                spec,
                                AiModelProbeOutcome.FAILED,
                                AiModelFailureReason.STORAGE_FAILURE
                            )
                            return@withContext installFailure(AiModelFailureReason.STORAGE_FAILURE)
                        }
                        deleteStaleFiles(spec)
                        probes.record(spec, AiModelProbeOutcome.SUCCEEDED, null)
                        return@withContext Result.success(modelFile(spec))
                    }
                    TransferOutcome.CANCELLED -> {
                        wasCancelled = true
                        break
                    }
                    is TransferOutcome.Failed -> failures += outcome.reason
                }
            }
            val reason = if (wasCancelled) {
                AiModelFailureReason.TRANSPORT
            } else {
                aggregateFailures(failures)
            }
            probes.record(
                spec,
                if (wasCancelled) AiModelProbeOutcome.CANCELLED else AiModelProbeOutcome.FAILED,
                reason
            )
            installFailure(reason)
        } catch (e: CancellationException) {
            // The coroutine was cancelled externally. The .part file is never deleted here, so a
            // later attempt resumes, but a cancelled context cannot carry a Result back to the UI.
            probes.record(spec, AiModelProbeOutcome.CANCELLED, AiModelFailureReason.TRANSPORT)
            throw e
        } finally {
            runCatching { http.close() }
                .onFailure { Timber.w(it, "Failed to close the model HTTP source") }
        }
    }

    /**
     * Imports a user-selected `.onnx` file through the same size and SHA-256 checks as [download].
     *
     * The SAF picker hands over an [InputStream], so this method stays free of Android `Uri`
     * handling. A rejected import leaves neither a staging file nor a final file behind; a
     * pre-existing resumable `.part` of the model is not destroyed.
     *
     * @param onProgress Receives the completion ratio in `[0, 1]`; called on [ioDispatcher].
     * @return The verified model file, or a failure carrying [AiModelInstallException].
     */
    suspend fun importLocal(
        spec: AiModelSpec,
        input: InputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = importLocalAny(listOf(spec), input, onProgress)

    /**
     * Imports one file that may match any of [candidates] (preferred order).
     *
     * This is how MI-GAN keeps accepting both the pipeline export and an already-held legacy
     * generator file: the stream is staged once, then matched against the pinned length and SHA-256
     * of every supported entry. The first matching candidate wins.
     */
    suspend fun importLocalAny(
        candidates: List<AiModelSpec>,
        input: InputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(ioDispatcher) {
        val pinned = candidates.filter { it.hasPinnedArtifact }
        if (pinned.isEmpty()) {
            return@withContext installFailure(AiModelFailureReason.NOT_AVAILABLE)
        }
        val reportSpec = pinned.first()
        fun fail(reason: AiModelFailureReason, cause: Throwable? = null): Result<File> {
            probes.record(reportSpec, AiModelProbeOutcome.FAILED, reason)
            return installFailure(reason, cause)
        }
        if (!ensureModelsRoot()) {
            return@withContext fail(AiModelFailureReason.STORAGE_FAILURE)
        }
        val staging = try {
            File.createTempFile(IMPORT_STAGING_PREFIX, ".part", modelsRootDir)
        } catch (e: IOException) {
            Timber.e(e, "Could not create an import staging file")
            return@withContext fail(AiModelFailureReason.STORAGE_FAILURE, e)
        }
        try {
            val bound = pinned.maxByOrNull { it.byteSize } ?: pinned.first()
            FileOutputStream(staging).use { output ->
                AiModelTransferHelper.copyBounded(bound, input, output, onProgress)?.let { failure ->
                    return@withContext fail(failure)
                }
            }
            val length = staging.length()
            val candidatesOfSize = pinned.filter { it.byteSize == length }
            if (candidatesOfSize.isEmpty()) {
                return@withContext fail(AiModelFailureReason.SIZE_MISMATCH)
            }
            val digestHex = digest(staging)
            val matched = candidatesOfSize.firstOrNull {
                it.sha256.equals(digestHex, ignoreCase = true)
            } ?: return@withContext fail(AiModelFailureReason.CHECKSUM_MISMATCH)
            if (!variantCompatibility.accepts(staging, matched.variantHint)) {
                return@withContext fail(AiModelFailureReason.INCOMPATIBLE)
            }
            if (!ensureModelDir(matched)) {
                return@withContext fail(AiModelFailureReason.STORAGE_FAILURE)
            }
            if (!promoteStagedFile(staging, matched)) {
                return@withContext fail(AiModelFailureReason.STORAGE_FAILURE)
            }
            deleteStaleFiles(matched)
            probes.record(matched, AiModelProbeOutcome.SUCCEEDED, null)
            Result.success(modelFile(matched))
        } catch (e: IOException) {
            Timber.e(e, "Failed to copy the selected ONNX file into the model directory")
            fail(AiModelFailureReason.STORAGE_FAILURE, e)
        } finally {
            if (staging.exists() && !staging.delete()) {
                Timber.w("Could not delete the import staging file %s", staging.name)
            }
        }
    }

    /**
     * Deletes one model directory, including a partial download and its probe journal.
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

    private suspend fun transfer(
        spec: AiModelSpec,
        url: String,
        onProgress: (Float) -> Unit,
        cancellation: AiModelDownloadCancellation?
    ): TransferOutcome {
        var response: ModelHttpResponse? = null
        return try {
            val resumeFrom = partFile(spec).takeIf { it.isFile }?.length() ?: 0L
            val canResume = resumeFrom in 1L until spec.byteSize
            if (resumeFrom > 0L && !canResume) partFile(spec).delete()
            val start = if (canResume) resumeFrom else 0L
            response = http.open(url, start)
            classifyHttpStatus(response.statusCode)?.let { return TransferOutcome.Failed(it) }
            val append = start > 0L && response.statusCode == HTTP_PARTIAL_CONTENT
            if (!append) partFile(spec).delete()
            when (
                AiModelTransferHelper.writeBody(
                    spec = spec,
                    partFile = partFile(spec),
                    body = response.body,
                    alreadyWritten = if (append) start else 0L,
                    onProgress = onProgress,
                    cancellation = cancellation
                )
            ) {
                AiModelTransferHelper.WriteOutcome.COMPLETE -> TransferOutcome.SUCCESS
                AiModelTransferHelper.WriteOutcome.CANCELLED -> TransferOutcome.CANCELLED
            }
        } catch (e: FileNotFoundException) {
            Timber.e(e, "The model directory became unwritable during the transfer")
            TransferOutcome.Failed(AiModelFailureReason.STORAGE_FAILURE)
        } catch (e: IOException) {
            Timber.w(e, "Model mirror failed; keeping the partial file for the next source")
            TransferOutcome.Failed(AiModelFailureReason.TRANSPORT)
        } catch (e: SecurityException) {
            Timber.w(e, "Model download was denied by the platform")
            TransferOutcome.Failed(AiModelFailureReason.TRANSPORT)
        } finally {
            runCatching { response?.body?.close() }
                .onFailure { Timber.w(it, "Failed to close a model response body") }
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

    private fun promotePartFile(spec: AiModelSpec): Boolean = promote(partFile(spec), modelFile(spec))

    private fun promoteStagedFile(staging: File, spec: AiModelSpec): Boolean =
        promote(staging, modelFile(spec))

    private fun promote(source: File, finalFile: File): Boolean {
        val parent = finalFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            Timber.e("Could not create the model directory for %s", finalFile.name)
            return false
        }
        if (source.renameTo(finalFile)) return true
        // Some filesystems refuse to replace an existing file; copy-then-delete keeps the same
        // bytes and still leaves the verified final file in place.
        return try {
            source.copyTo(finalFile, overwrite = true).isFile && source.delete()
        } catch (e: IOException) {
            Timber.e(e, "Failed to move the verified model into place")
            false
        }
    }

    private fun deleteStaleFiles(spec: AiModelSpec) {
        modelDir(spec).listFiles()?.forEach { file ->
            if (file.name == spec.fileName || file.name == PROBE_FILE_NAME) return@forEach
            if (!file.deleteRecursively()) {
                Timber.w("Failed to delete a stale model file in %s", spec.id)
            }
        }
    }

    private fun ensureModelDir(spec: AiModelSpec): Boolean {
        if (modelDir(spec).isDirectory) return true
        val created = modelDir(spec).mkdirs()
        if (!created) Timber.e("Model directory could not be created for %s", spec.id)
        return created || modelDir(spec).isDirectory
    }

    private fun ensureModelsRoot(): Boolean {
        if (modelsRootDir.isDirectory) return true
        val created = modelsRootDir.mkdirs()
        if (!created) Timber.e("Model root could not be created")
        return created || modelsRootDir.isDirectory
    }

    /**
     * Chooses the user-facing reason from the per-mirror outcomes.
     *
     * Verification failures win over transport noise because they prove bytes arrived; permanent
     * access failures only become the headline when every mirror agreed on them.
     */
    private fun aggregateFailures(failures: List<AiModelFailureReason>): AiModelFailureReason {
        if (failures.isEmpty()) return AiModelFailureReason.TRANSPORT
        val verification = failures.firstOrNull {
            it == AiModelFailureReason.CHECKSUM_MISMATCH ||
                it == AiModelFailureReason.SIZE_MISMATCH ||
                it == AiModelFailureReason.INCOMPATIBLE
        }
        if (verification != null) return verification
        if (failures.any { it == AiModelFailureReason.STORAGE_FAILURE }) {
            return AiModelFailureReason.STORAGE_FAILURE
        }
        if (failures.all { it == AiModelFailureReason.SOURCE_GONE }) {
            return AiModelFailureReason.SOURCE_GONE
        }
        if (failures.all { it == AiModelFailureReason.SOURCE_DENIED }) {
            return AiModelFailureReason.SOURCE_DENIED
        }
        return AiModelFailureReason.TRANSPORT
    }

    private fun classifyHttpStatus(statusCode: Int): AiModelFailureReason? = when {
        statusCode in 200..299 -> null
        statusCode == 401 || statusCode == 403 -> AiModelFailureReason.SOURCE_DENIED
        statusCode == 404 || statusCode == 410 -> AiModelFailureReason.SOURCE_GONE
        else -> AiModelFailureReason.TRANSPORT
    }

    private fun installFailure(
        reason: AiModelFailureReason,
        cause: Throwable? = null
    ): Result<File> = Result.failure(
        AiModelInstallException(reason, "AI model install failed: $reason", cause)
    )

    private sealed interface TransferOutcome {

        data object SUCCESS : TransferOutcome

        data object CANCELLED : TransferOutcome

        data class Failed(val reason: AiModelFailureReason) : TransferOutcome
    }

    private companion object {
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val IMPORT_STAGING_PREFIX = ".import-"
        private const val PROBE_FILE_NAME = ".probe.properties"
    }
}
