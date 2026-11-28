package cn.devcxl.photosync.editor.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.InpaintComponentBlend
import cn.devcxl.photosync.editor.domain.InpaintCropBlender
import cn.devcxl.photosync.editor.domain.InpaintCropPlan
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintPlanTooLargeException

/** Why [MiGanInpainter] could not produce a patch. */
enum class MiGanFailureReason {

    /** The source buffer, mask or model tensor did not match the pinned shapes. */
    INVALID_INPUT,

    /** Nothing was painted, so there is no hole to repair. */
    NO_STROKES,

    /** The MI-GAN artifact is not installed yet; the UI must start the download/import flow. */
    MODEL_MISSING,

    /** The painted area would need more tiles or a larger patch than the device budget allows. */
    PLAN_TOO_LARGE,

    /** A patch or mask PNG could not be encoded or persisted. */
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
 * Runs the P2 inpainting vertical slice end to end: plan, serial MI-GAN tiles, feather, persist.
 *
 * ## Contract
 * - The catalog artifact is the pinned **FP32 generator-only** export; an INT8/QDQ model is never
 *   requested or accepted, because the P0 gate record only verified the FP32 bytes.
 * - Tiles run serially through [OrtSessionManager.withSession], which also serializes the tool
 *   against portrait matting and closes the session when the memory tier demands it.
 * - [kotlinx.coroutines.CancellationException] is observed between tiles. A cancelled run persists
 *   no further patch; patches already committed before the cancellation stay valid and simply
 *   become unreferenced (the AI asset GC reclaims them).
 * - [onProgress] receives `0..100` on the dispatcher the call runs on; the UI posts it to the main
 *   thread itself.
 *
 * @param modelSpec Model to run. Production keeps the default [AiModelCatalog.MI_GAN_PIPELINE];
 *   the parameter exists so JVM tests can install a tiny pinned artifact. E1 adds the runtime
 *   variant detector and the uint8 pipeline runner that execute this artifact.
 */
class MiGanInpainter(
    private val sessionManager: OrtSessionManager,
    private val downloadManager: AiModelDownloadManager,
    private val assetStore: AiAssetStore,
    private val encoder: InpaintPatchEncoder = BitmapInpaintPatchCodec,
    private val blender: InpaintCropBlender = InpaintCropBlender(),
    private val tileRunner: MiGanTileRunner = OnnxMiGanTileRunner(),
    private val modelSpec: AiModelSpec = AiModelCatalog.MI_GAN_PIPELINE
) {

    /**
     * Repairs every painted hole and persists one ordered [InpaintPatchRef] per component.
     *
     * @param photoId Photo the patches belong to; must be a persisted id.
     * @param sourceArgb Opaque `ARGB_8888` source pixels in pre-crop original space.
     * @param width Source width.
     * @param height Source height.
     * @param mask User mask in source pixels; `255` marks a hole.
     * @param onProgress Completion in `0..100`, called after every finished tile.
     * @return The committed patch references, or a failure carrying [MiGanInpaintException].
     */
    suspend fun inpaint(
        photoId: Long,
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        mask: InpaintMaskBuffer,
        onProgress: (Int) -> Unit = {}
    ): Result<List<InpaintPatchRef>> = withContext(Dispatchers.Default) {
        validateInput(photoId, sourceArgb, width, height, mask)?.let { return@withContext it }
        val plan = try {
            blender.plan(mask)
        } catch (error: InpaintPlanTooLargeException) {
            Timber.w(error, "Inpaint plan exceeds the device budget")
            return@withContext failure(MiGanFailureReason.PLAN_TOO_LARGE, error)
        }
        if (plan.isEmpty) {
            return@withContext failure(MiGanFailureReason.NO_STROKES)
        }
        if (!downloadManager.isInstalled(modelSpec)) {
            return@withContext failure(MiGanFailureReason.MODEL_MISSING)
        }
        val coroutineContext = currentCoroutineContext()
        onProgress(0)
        try {
            val run = sessionManager.withSession(downloadManager.modelFile(modelSpec)) { handle ->
                execute(plan, mask, sourceArgb, photoId, handle, coroutineContext, onProgress)
            }
            run.fold(
                onSuccess = { patches ->
                    onProgress(100)
                    Result.success(patches)
                },
                onFailure = { error -> mapSessionFailure(error) }
            )
        } catch (error: MiGanInpaintException) {
            Timber.e(error, "MI-GAN inpainting failed")
            Result.failure(error)
        } catch (error: OutOfMemoryError) {
            Timber.e(error, "MI-GAN inpainting ran out of memory")
            failure(MiGanFailureReason.RUNTIME_ERROR, error)
        }
    }

    private fun execute(
        plan: InpaintCropPlan,
        mask: InpaintMaskBuffer,
        sourceArgb: IntArray,
        photoId: Long,
        handle: OrtSessionHandle,
        coroutineContext: kotlin.coroutines.CoroutineContext,
        onProgress: (Int) -> Unit
    ): List<InpaintPatchRef> {
        val patches = ArrayList<InpaintPatchRef>(plan.components.size)
        val totalTiles = plan.tileCount.coerceAtLeast(1)
        var completedTiles = 0
        for (component in plan.components) {
            coroutineContext.ensureActive()
            val builder = blender.beginComponent(plan, mask, component.index, sourceArgb)
            for (tile in component.tiles) {
                coroutineContext.ensureActive()
                val input = blender.preprocessTile(
                    sourceArgb = sourceArgb,
                    sourceWidth = plan.sourceWidth,
                    sourceHeight = plan.sourceHeight,
                    mask = mask,
                    tile = tile
                )
                if (input.size != InpaintCropBlender.MODEL_INPUT_ELEMENTS) {
                    throw MiGanInpaintException(
                        MiGanFailureReason.INVALID_INPUT,
                        "MI-GAN input tensor has an unexpected size"
                    )
                }
                val output = tileRunner.run(handle, input)
                if (output.size != InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) {
                    throw MiGanInpaintException(
                        MiGanFailureReason.INVALID_INPUT,
                        "MI-GAN output tensor has an unexpected size"
                    )
                }
                builder.addTile(tile, output)
                completedTiles++
                onProgress(completedTiles * 100 / totalTiles)
            }
            coroutineContext.ensureActive()
            patches.add(persist(photoId, builder))
        }
        return patches
    }

    /**
     * Encodes and stores one component patch.
     *
     * `InpaintPatchRef.sha256` carries the patch digest; the mask digest is recoverable from its
     * content-addressed file name, so the schema needs no second digest field.
     */
    private fun persist(photoId: Long, builder: InpaintComponentBlend): InpaintPatchRef {
        val patch = builder.finish()
        val patchPng = encoder.encodeRgb(patch.argb, patch.width, patch.height)
            ?: throw MiGanInpaintException(
                MiGanFailureReason.ENCODING_FAILED,
                "Inpaint patch PNG could not be encoded"
            )
        val maskPng = encoder.encodeAlpha(patch.alpha, patch.width, patch.height)
            ?: throw MiGanInpaintException(
                MiGanFailureReason.ENCODING_FAILED,
                "Inpaint mask PNG could not be encoded"
            )
        val patchRef = assetStore.write(photoId, AiAssetKind.INPAINT_PATCH, patchPng)
            ?: throw MiGanInpaintException(
                MiGanFailureReason.ENCODING_FAILED,
                "Inpaint patch could not be persisted"
            )
        val maskRef = assetStore.write(photoId, AiAssetKind.INPAINT_MASK, maskPng)
            ?: throw MiGanInpaintException(
                MiGanFailureReason.ENCODING_FAILED,
                "Inpaint mask could not be persisted"
            )
        return InpaintPatchRef(
            bbox = patch.bbox,
            patchRelPath = patchRef.relativePath,
            maskRelPath = maskRef.relativePath,
            sha256 = patchRef.sha256
        )
    }

    private fun validateInput(
        photoId: Long,
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        mask: InpaintMaskBuffer
    ): Result<List<InpaintPatchRef>>? {
        if (photoId <= 0L) {
            return invalidInput("Photo id must be positive")
        }
        if (width <= 0 || height <= 0 || sourceArgb.size != width * height) {
            return invalidInput("Source buffer does not match its size")
        }
        if (mask.width != width || mask.height != height) {
            return invalidInput("Mask and source dimensions differ")
        }
        return null
    }

    private fun invalidInput(message: String): Result<List<InpaintPatchRef>> =
        Result.failure(MiGanInpaintException(MiGanFailureReason.INVALID_INPUT, message))

    private fun mapSessionFailure(error: Throwable): Result<List<InpaintPatchRef>> {
        val reason = when ((error as? OrtSessionUnavailableException)?.reason) {
            OrtSessionFailureReason.MODEL_MISSING -> MiGanFailureReason.MODEL_MISSING
            OrtSessionFailureReason.OUT_OF_MEMORY -> MiGanFailureReason.RUNTIME_ERROR
            OrtSessionFailureReason.RUNTIME_ERROR, null -> MiGanFailureReason.RUNTIME_ERROR
        }
        Timber.e(error, "MI-GAN session could not run the inpainting tiles")
        return failure(reason, error)
    }

    private fun <T> failure(reason: MiGanFailureReason, cause: Throwable? = null): Result<T> {
        val message = cause?.message ?: reason.name
        return Result.failure(MiGanInpaintException(reason, message, cause))
    }
}
