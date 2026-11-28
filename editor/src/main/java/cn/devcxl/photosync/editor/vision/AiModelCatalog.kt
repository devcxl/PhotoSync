package cn.devcxl.photosync.editor.vision

/**
 * Execution shape of an inpainting model, decided by its ONNX input signature.
 *
 * The hint on [AiModelSpec] only documents the expected shape of a pinned artifact; the runtime
 * judge is still the signature probe (E1), so a locally imported file is classified by its bytes
 * rather than by its catalog entry.
 */
enum class InpaintVariant {

    /** Legacy generator export: `input` is float32 `[1,4,512,512]`. */
    GENERATOR_FIXED_512,

    /** Upstream pipeline export: uint8 `image` + uint8 `mask`, dynamic height and width. */
    PIPELINE_UINT8
}

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
 * @property variantHint Expected inpaint variant for inpainting models; `null` for other tools.
 */
data class AiModelSpec(
    val id: String,
    val version: String,
    val fileName: String,
    val mirrorUrls: List<String>,
    val byteSize: Long,
    val sha256: String,
    val license: AiModelLicense,
    val variantHint: InpaintVariant? = null
) {

    /** Sibling `.part` file kept across attempts so a transfer can resume with `Range`. */
    val partFileName: String
        get() = "$fileName.part"

    /**
     * True when the artifact has the size and SHA-256 an install must match.
     *
     * A read-only legacy entry can be pinned but not downloadable; the SAF import path still accepts
     * it because the bytes are verifiable.
     */
    val hasPinnedArtifact: Boolean
        get() = byteSize > 0L && sha256.isNotBlank()

    /**
     * True when the artifact is pinned well enough to download or import it.
     *
     * An entry without mirrors, size and hash is intentionally visible in the settings page but
     * cannot be installed: accepting bytes that cannot be verified would defeat the integrity
     * contract shared by the download and SAF import paths.
     */
    val isPinned: Boolean
        get() = hasPinnedArtifact

    /** True when the catalog entry also has at least one mirror a download may use. */
    val isDownloadable: Boolean
        get() = hasPinnedArtifact && mirrorUrls.isNotEmpty()
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
     * The old GitHub Releases asset (release `ai-models-v1`) returns HTTP 404 and was removed as
     * the primary source. HuggingFace is the upstream and `hf-mirror.com` is the in-country
     * reachable mirror; both were measured byte-identical. Every source must serve the exact
     * pinned bytes, which the size and SHA-256 checks enforce before the file is promoted.
     */
    val MODNET_PORTRAIT = AiModelSpec(
        id = "modnet-portrait-512",
        version = "1",
        fileName = "modnet_portrait_512_v1.onnx",
        mirrorUrls = listOf(
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
     * MI-GAN-512 pipeline export used by the P2 inpainting tool (MIT, code and weights).
     *
     * The upstream community export keeps the crop/paste-back control flow inside the graph:
     * `image` and `mask` are uint8 tensors with dynamic height/width, output `result` is uint8 RGB,
     * and mask semantics are `255 = keep, 0 = inpaint` (the opposite of the domain mask). The three
     * mirrors below were measured byte-identical on 2026-11-27.
     */
    val MI_GAN_PIPELINE = AiModelSpec(
        id = "migan-pipeline-512",
        version = "1",
        fileName = "migan_pipeline_512_v1.onnx",
        mirrorUrls = listOf(
            "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx",
            "https://hf-mirror.com/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx",
            "https://huggingface.co/anyisalin/migan-onnx/resolve/main/onnx/model.onnx"
        ),
        byteSize = 28_079_181L,
        sha256 = "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b",
        license = AiModelLicense(
            name = "MIT",
            url = "https://github.com/Picsart-AI-Research/MI-GAN/blob/main/LICENSE-WEIGHTS"
        ),
        variantHint = InpaintVariant.PIPELINE_UINT8
    )

    /**
     * Legacy generator-only MI-GAN export, kept for recognition only.
     *
     * The artifact was never published on a release, so this entry has no mirrors and never appears
     * on a download button. It exists so the SAF import path can still recognise a file the user
     * already holds (28,026,303 B / `522048b4…`), and so the E1 variant detector can classify it as
     * [InpaintVariant.GENERATOR_FIXED_512].
     */
    val MI_GAN_GENERATOR_LEGACY = AiModelSpec(
        id = "migan-generator-512",
        version = "1",
        fileName = "migan_generator_512_v1.onnx",
        mirrorUrls = emptyList(),
        byteSize = 28_026_303L,
        sha256 = "522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe",
        license = AiModelLicense(
            name = "MIT",
            url = "https://github.com/Picsart-AI-Research/MI-GAN/blob/main/LICENSE-WEIGHTS"
        ),
        variantHint = InpaintVariant.GENERATOR_FIXED_512
    )

    /**
     * Deprecated alias for callers written before the pipeline switch.
     *
     * The download entry is now [MI_GAN_PIPELINE]; code that needs the old generator bytes must
     * reference [MI_GAN_GENERATOR_LEGACY] explicitly.
     */
    @Deprecated(
        message = "MI-GAN now ships the pipeline export; use MI_GAN_PIPELINE",
        replaceWith = ReplaceWith("AiModelCatalog.MI_GAN_PIPELINE")
    )
    val MI_GAN_GENERATOR: AiModelSpec
        get() = MI_GAN_PIPELINE

    /** Every model the AI settings page lists, in display order. */
    val onDemandModels: List<AiModelSpec> = listOf(MODNET_PORTRAIT, MI_GAN_PIPELINE)

    /**
     * Every pinned entry, including the read-only legacy generator pin.
     *
     * Used by the catalog guard and by tooling that needs to recognise an already-held file rather
     * than advertise it for download.
     */
    val supportedModels: List<AiModelSpec> = onDemandModels + MI_GAN_GENERATOR_LEGACY

    /**
     * Catalog entries a SAF import may resolve to.
     *
     * MI-GAN accepts both the pipeline bytes and the old generator pin: a user who already owns the
     * legacy file must not be told that their model is "wrong" merely because the distribution
     * entry moved. The first element is the preferred target, so a matching primary pin still wins.
     */
    fun importCandidates(spec: AiModelSpec): List<AiModelSpec> = when (spec.id) {
        MI_GAN_PIPELINE.id -> listOf(MI_GAN_PIPELINE, MI_GAN_GENERATOR_LEGACY)
        MI_GAN_GENERATOR_LEGACY.id -> listOf(MI_GAN_GENERATOR_LEGACY, MI_GAN_PIPELINE)
        else -> listOf(spec)
    }
}
