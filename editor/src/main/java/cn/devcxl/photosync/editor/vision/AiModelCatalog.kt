package cn.devcxl.photosync.editor.vision

/**
 * License that covers one model, both code and weights.
 *
 * @property name SPDX-style identifier shown in the settings page.
 * @property url Canonical license text, kept here so the page never hardcodes a legal claim.
 */
data class AiModelLicense(val name: String, val url: String)

/**
 * One on-device model that is downloaded on demand.
 *
 * @property id Stable directory name under `<filesDir>/models/`; also the UI's stable key.
 * @property version Artifact version; stale files in the model directory are deleted after a new
 *   version is installed.
 * @property fileName Name of the verified `.onnx` file inside the model directory.
 * @property mirrorUrls Ordered mirrors. The first source that yields a file matching [byteSize] and
 *   [sha256] wins; any failure falls through to the next source.
 * @property byteSize Exact size in bytes, verified before the atomic rename.
 * @property sha256 Lower-case hex SHA-256 of the whole file, verified before the atomic rename.
 * @property license License of code and weights.
 */
data class AiModelSpec(
    val id: String,
    val version: String,
    val fileName: String,
    val mirrorUrls: List<String>,
    val byteSize: Long,
    val sha256: String,
    val license: AiModelLicense
) {

    /** Sibling `.part` file kept across attempts so a transfer can resume with `Range`. */
    val partFileName: String
        get() = "$fileName.part"

    /**
     * True when the artifact is pinned well enough to download or import it.
     *
     * An entry without mirrors, size and hash is intentionally visible in the settings page but
     * cannot be installed: accepting bytes that cannot be verified would defeat the integrity
     * contract shared by the download and SAF import paths.
     */
    val isPinned: Boolean
        get() = mirrorUrls.isNotEmpty() && byteSize > 0L && sha256.isNotBlank()
}

/**
 * True when [displayName] is an `.onnx` file, the only format the SAF import accepts.
 *
 * The file manager picker cannot be relied on for a MIME type (`application/octet-stream` is the
 * common report for ONNX), so the name suffix is the actual gate.
 */
fun isOnnxModelFileName(displayName: String?): Boolean =
    displayName?.endsWith(".onnx", ignoreCase = true) == true

/** The on-demand models the editor knows about, with their pinned artifacts and licenses. */
object AiModelCatalog {

    /** Directory under `filesDir` that holds one sub-directory per [AiModelSpec.id]. */
    const val MODELS_DIR_NAME = "models"

    /**
     * MODNet portrait matting, FP32, 512×512.
     *
     * Byte count and SHA-256 were measured against the HuggingFace artifact on 2026-11-26; the
     * file is Apache-2.0, including the weights.
     *
     * Mirror order follows the distribution policy: the GitHub Releases asset is the primary source,
     * HuggingFace is the upstream fallback, and `hf-mirror.com` is the in-country reachable mirror.
     * Every source must serve the exact pinned bytes, which the size and SHA-256 checks enforce
     * before the file is promoted into place.
     */
    val MODNET_PORTRAIT = AiModelSpec(
        id = "modnet-portrait-512",
        version = "1",
        fileName = "modnet_portrait_512_v1.onnx",
        mirrorUrls = listOf(
            "https://github.com/devcxl/PhotoSync/releases/download/ai-models-v1/" +
                "modnet_photographic_portrait_matting.onnx",
            "https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx",
            "https://hf-mirror.com/Xenova/modnet/resolve/main/onnx/model.onnx"
        ),
        byteSize = 25_888_640L,
        sha256 = "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9",
        license = AiModelLicense(
            name = "Apache-2.0",
            url = "https://github.com/ZHKKKe/MODNet/blob/master/LICENSE"
        )
    )

    /**
     * MI-GAN-512 generator-only export used by the P2 inpainting tool (MIT, code and weights).
     *
     * Exported from the official `migan_512_places2.pt` checkpoint with the legacy TorchScript
     * exporter (opset 17, input `float32 [1,4,512,512]`, output `float32 [1,3,512,512]`). The
     * dynamic crop/paste-back control flow of the upstream pipeline is gone: the graph has no
     * `NonZero`, `ScatterND`, `GatherND`, `Where`, `Range`, `Loop`, `If`, `Scan` or `TopK`, and
     * every `Resize`/`Reshape`/`Slice`/`Pad` shape parameter is constant-foldable.
     *
     * The export was verified bit-exactly against `migan_pipeline_v2.onnx` on all 20 Places2
     * samples (MAE = 0, PSNR = ∞). The GitHub Releases asset is the primary source; the release
     * asset is not uploaded yet, so until it lands the SAF import path is the working install
     * route — it accepts only a file matching the pinned size and SHA-256 below, and so does every
     * mirror.
     */
    val MI_GAN_GENERATOR = AiModelSpec(
        id = "migan-generator-512",
        version = "1",
        fileName = "migan_generator_512_v1.onnx",
        mirrorUrls = listOf(
            "https://github.com/devcxl/PhotoSync/releases/download/ai-models-v1/" +
                "migan_generator_512.onnx"
        ),
        byteSize = 28_026_303L,
        sha256 = "522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe",
        license = AiModelLicense(
            name = "MIT",
            url = "https://github.com/Picsart-AI-Research/MI-GAN/blob/main/LICENSE-WEIGHTS"
        )
    )

    /** Every model the AI settings page lists, in display order. */
    val onDemandModels: List<AiModelSpec> = listOf(MODNET_PORTRAIT, MI_GAN_GENERATOR)
}
