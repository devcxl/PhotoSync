package cn.devcxl.photosync.editor.vision

/**
 * Optional on-demand sky artifacts.
 *
 * The shipped v1 sky tool is **zero-model**: [ClassicSkySegmenter] needs no download and the four
 * procedural skies need no file. This catalog only reserves the hot-swap slots promised by the P3
 * specification:
 * - a future 2–5 MB MIT/Apache-2.0 sky segmenter that implements the same `SkySegmenter` interface;
 * - a CC0 high-resolution cloud pack downloaded through [AiModelDownloadManager] with the shared
 *   mirror fallback and SHA-256 verification.
 *
 * Entries without a pinned artifact are deliberately listed but `isPinned == false`, so the settings
 * page can show the slot while an install attempt fails cleanly with `NOT_AVAILABLE` instead of
 * accepting unverifiable bytes.
 */
object SkyModelCatalog {

    /** Directory name shared with the AI model manager; packs live next to the ONNX models. */
    const val MODELS_DIR_NAME = AiModelCatalog.MODELS_DIR_NAME

    /**
     * Hot-swap slot for a lightweight, commercially licensed sky segmentation model.
     *
     * No artifact is pinned yet: shipping a placeholder hash would either reject every real file or
     * accept an unverified one, and the ticket explicitly keeps v1 zero-model.
     */
    val SKY_SEGMENTER = AiModelSpec(
        id = "sky-segmenter-lite",
        version = "0",
        fileName = "sky_segmenter_lite_v1.onnx",
        mirrorUrls = emptyList(),
        byteSize = 0L,
        sha256 = "",
        license = AiModelLicense(
            name = "MIT",
            url = "https://opensource.org/license/mit"
        )
    )

    /**
     * Optional CC0 high-resolution cloud pack.
     *
     * The pack is a single JPEG served by the project's release channel; its bytes and digest will be
     * pinned when the curated assets are published. Until then the entry documents the slot, the
     * license and the intended distribution path (`SkyAssetType.PACK`).
     */
    val CC0_CLOUD_PACK = AiModelSpec(
        id = "sky-pack-cc0-clouds",
        version = "0",
        fileName = "cc0_cloud_pack_01.jpg",
        mirrorUrls = emptyList(),
        byteSize = 0L,
        sha256 = "",
        license = AiModelLicense(
            name = "CC0-1.0",
            url = "https://creativecommons.org/publicdomain/zero/1.0/"
        )
    )

    /** Every optional sky artifact, in display order. */
    val optionalAssets: List<AiModelSpec> = listOf(CC0_CLOUD_PACK, SKY_SEGMENTER)

    /** Resolves a persisted `SkyAssetType.PACK` id, or `null` when it names no catalog entry. */
    fun packById(id: String): AiModelSpec? =
        optionalAssets.firstOrNull { it.id == id && it.id.startsWith(PACK_ID_PREFIX) }

    /**
     * Resolves the installed file of a pack id under `<modelsRoot>/<pack-id>/<fileName>`.
     *
     * @return The file when the pack is installed, or `null` for an unknown/uninstalled id.
     */
    fun packFile(modelsRoot: java.io.File, id: String): java.io.File? {
        val spec = packById(id) ?: return null
        val file = java.io.File(java.io.File(modelsRoot, spec.id), spec.fileName)
        return file.takeIf { it.isFile }
    }

    private const val PACK_ID_PREFIX = "sky-pack-"
}
