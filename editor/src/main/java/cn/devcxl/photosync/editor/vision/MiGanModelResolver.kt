package cn.devcxl.photosync.editor.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Chooses the MI-GAN artifact to run and asks [InpaintVariantDetector] what shape its bytes declare.
 *
 * Candidate order is the preferred [modelSpec], then the entries `AiModelCatalog.importCandidates`
 * accepts for it. That makes the pipeline pin win over the legacy generator when both exist, while
 * a user who only holds the old generator file still gets a working erase tool.
 *
 * @param downloadManager Source of the installed-file check and paths.
 * @param detector Signature probe with its `(fileLength, sha256)` cache.
 * @param modelSpec Preferred catalog entry.
 */
internal class MiGanModelResolver(
    private val downloadManager: AiModelDownloadManager,
    private val detector: InpaintVariantDetector,
    private val modelSpec: AiModelSpec
) {

    /**
     * Resolves the installed artifact and its variant.
     *
     * @return The selected file and variant, or a failure carrying [MiGanInpaintException]
     *   (`MODEL_MISSING`, `INCOMPATIBLE`, `RUNTIME_ERROR`).
     */
    suspend fun resolve(): Result<MiGanModelSelection> = withContext(Dispatchers.Default) {
        val candidates = (listOf(modelSpec) + AiModelCatalog.importCandidates(modelSpec))
            .distinctBy { it.id }
        val installed = candidates.firstOrNull { downloadManager.isInstalled(it) }
            ?: return@withContext failure(MiGanFailureReason.MODEL_MISSING)
        val file = downloadManager.modelFile(installed)
        val variant = try {
            detector.detect(file, installed.sha256)
        } catch (error: AiModelInstallException) {
            Timber.e(error, "The installed MI-GAN file has an unsupported signature")
            return@withContext failure(
                if (error.reason == AiModelFailureReason.INCOMPATIBLE) {
                    MiGanFailureReason.INCOMPATIBLE
                } else {
                    MiGanFailureReason.RUNTIME_ERROR
                },
                error
            )
        } catch (error: RuntimeException) {
            Timber.e(error, "The MI-GAN variant could not be probed")
            return@withContext failure(MiGanFailureReason.INCOMPATIBLE, error)
        }
        Result.success(MiGanModelSelection(spec = installed, file = file, variant = variant))
    }

    private fun <T> failure(reason: MiGanFailureReason, cause: Throwable? = null): Result<T> =
        Result.failure(MiGanInpaintException(reason, cause?.message ?: reason.name, cause))
}
