package cn.devcxl.photosync.editor.vision

import java.io.File

/**
 * On-demand installer/fetcher for [SkyModelCatalog] packs.
 *
 * It is a thin facade over the shared [AiModelDownloadManager], so the CC0 sky pack inherits the
 * multi-mirror fallback, `.part` resume, exact byte-count check and SHA-256 verification of every
 * other AI artifact. The viewer reads the returned file through `SkyAssetStore`'s pack resolver, so
 * no second integrity path exists.
 *
 * Until an artifact is pinned in the catalog, [install] fails with
 * [AiModelFailureReason.NOT_AVAILABLE] instead of accepting unverifiable bytes.
 */
class SkyPackInstaller(
    private val modelsRootDir: File,
    private val downloads: AiModelDownloadManager = AiModelDownloadManager(modelsRootDir)
) {

    /** True when [packId] names a catalog pack that is fully installed. */
    fun isInstalled(packId: String): Boolean {
        val spec = SkyModelCatalog.packById(packId) ?: return false
        return downloads.isInstalled(spec)
    }

    /** The verified file of [packId], or `null` when it is not installed. */
    fun installedFile(packId: String): File? {
        val spec = SkyModelCatalog.packById(packId) ?: return null
        return downloads.modelFile(spec).takeIf { downloads.isInstalled(spec) }
    }

    /**
     * Installs [packId] through the shared multi-mirror manager.
     *
     * @param onProgress Completion ratio in `[0, 1]`, invoked on the IO dispatcher.
     * @return The verified file, or a recoverable [AiModelInstallException] reason.
     */
    suspend fun install(packId: String, onProgress: (Float) -> Unit = {}): Result<File> {
        val spec = SkyModelCatalog.packById(packId)
            ?: return Result.failure(
                AiModelInstallException(
                    AiModelFailureReason.NOT_AVAILABLE,
                    "Unknown sky pack: $packId"
                )
            )
        return downloads.download(spec, onProgress)
    }

    /** Deletes the pack directory, including a partial download. */
    fun delete(packId: String): Boolean {
        val spec = SkyModelCatalog.packById(packId) ?: return false
        return downloads.delete(spec)
    }
}
