package cn.devcxl.photosync.editor.vision

import java.io.File

import cn.devcxl.photosync.editor.domain.InpaintCropSpec
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintTilePlan

/** Why [MiGanInpainter] could not produce a patch. */
enum class MiGanFailureReason {

    /** The source buffer, mask, tile or model tensor did not match the pinned shapes. */
    INVALID_INPUT,

    /** Nothing was painted, so there is no hole to repair. */
    NO_STROKES,

    /** The MI-GAN artifact is not installed yet; the UI must start the download/import flow. */
    MODEL_MISSING,

    /** The installed file's ONNX input signature matches no supported [InpaintVariant]. */
    INCOMPATIBLE,

    /** A patch or mask PNG could not be encoded, persisted, or would exceed the asset budget. */
    ENCODING_FAILED,

    /** ONNX Runtime failed for a reason that is retryable after a hint. */
    RUNTIME_ERROR
}

/**
 * Recoverable failure raised by [MiGanInpainter].
 *
 * @property reason Machine-readable cause the UI maps to a user-facing hint.
 */
class MiGanInpaintException(
    val reason: MiGanFailureReason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * The model file an inpainting run will use, with the variant its signature declared.
 *
 * @property spec Catalog entry the bytes were verified against.
 * @property file Verified `.onnx` file on disk.
 * @property variant Execution shape chosen by [InpaintVariantDetector].
 */
data class MiGanModelSelection(
    val spec: AiModelSpec,
    val file: File,
    val variant: InpaintVariant
)

/**
 * Result of one executed tile: the patch reference plus the measured single-tile time.
 *
 * @property tile Tile the patch belongs to, including its core/crop geometry and neighbour flags.
 * @property patch Committed content-addressed patch reference.
 * @property elapsedMs Wall-clock milliseconds spent inside the model call of this tile.
 */
data class MiGanTileOutcome(
    val tile: InpaintTilePlan,
    val patch: InpaintPatchRef,
    val elapsedMs: Long
)

/**
 * Result of an inpainting run, one patch per tile (spec §6.3 / §12 #6).
 *
 * E2 (budgeted batching and progressive commit) consumes this shape: `tiles` carries each tile's
 * patch and measured time, `selection.variant` tells the UI which execution path ran, and
 * `patches` is the ordered list an `EditGraph.inpaint` append needs.
 *
 * @property selection Model file and variant that produced the patches.
 * @property cropSpec Geometry and batch budget the plan was built from.
 * @property tiles Per-tile outcomes in execution order.
 */
data class MiGanInpaintOutcome(
    val selection: MiGanModelSelection,
    val cropSpec: InpaintCropSpec,
    val tiles: List<MiGanTileOutcome>
) {

    /** Variant that produced this run. */
    val variant: InpaintVariant
        get() = selection.variant

    /** Ordered patch references, ready to append to `EditGraph.inpaint`. */
    val patches: List<InpaintPatchRef>
        get() = tiles.map { it.patch }
}
