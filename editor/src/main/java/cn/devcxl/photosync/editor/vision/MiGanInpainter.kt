package cn.devcxl.photosync.editor.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.InpaintCropBlender
import cn.devcxl.photosync.editor.domain.InpaintCropPlan
import cn.devcxl.photosync.editor.domain.InpaintCropSpec
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintTilePlan
import cn.devcxl.photosync.editor.settings.InpaintQualityMode

/**
 * Runs the P2 inpainting vertical slice end to end: plan, serial tiles, per-tile patch, persist.
 *
 * ## Contract
 * - **Variant by signature, not by catalog**: [resolveModel] picks the installed artifact
 *   (pipeline first, legacy generator second) and asks [InpaintVariantDetector] which execution
 *   shape its bytes declare. A user who only holds the old generator file still gets a working
 *   erase tool.
 * - **Patch granularity is one tile**: every tile becomes its own `InpaintPatchRef` with a crop
 *   ≤ the tier's maximum crop side. There is no component-wide accumulator, so memory is bounded
 *   by one tile rather than by the painted area.
 * - **Alpha is owned by `InpaintTileWeight`**: this class never re-derives blend weights; the
 *   per-tile executor delegates to `InpaintTilePatch`.
 * - **Serial inference**: all tiles run through [OrtSessionManager.withSession], which also
 *   serializes the tool against the other AI models and closes the session per the memory tier.
 * - **Cancellation is observed between tiles**: a cancelled run keeps the patches it already
 *   persisted; they are valid and merely uncommitted.
 *
 * ## E2 seam
 * - [plan] returns the full tile list and `spec.batchTiles` before any inference runs.
 * - [resolveModel] exposes the selected file and variant once.
 * - [inpaintTiles] executes a caller-chosen tile slice and reports each tile's patch and time,
 *   so the caller can batch, commit progressively and reuse the measurement for the ETA.
 *
 * @param encoder PNG codec; tests inject a fake.
 * @param generatorRunner `GENERATOR_FIXED_512` executor; tests inject a fake.
 * @param pipelineRunner `PIPELINE_UINT8` executor; tests inject a fake.
 * @param variantDetector Signature probe and cache.
 * @param modelSpec Preferred catalog entry; production keeps [AiModelCatalog.MI_GAN_PIPELINE].
 * @param memoryTier Device tier that selects the crop budget.
 * @param qualityMode Fine/fast grid density.
 * @param clock Millisecond clock used for the per-tile timing.
 */
class MiGanInpainter(
    private val sessionManager: OrtSessionManager,
    private val downloadManager: AiModelDownloadManager,
    private val assetStore: AiAssetStore,
    private val encoder: InpaintPatchEncoder = BitmapInpaintPatchCodec,
    private val generatorRunner: MiGanTileRunner = OnnxMiGanTileRunner(),
    private val pipelineRunner: MiganPipelineRunner = OnnxMiganPipelineRunner(),
    private val variantDetector: InpaintVariantDetector = InpaintVariantDetector(),
    private val modelSpec: AiModelSpec = AiModelCatalog.MI_GAN_PIPELINE,
    private val memoryTier: DeviceMemoryTier = DeviceMemoryTier.STANDARD,
    private val qualityMode: InpaintQualityMode = InpaintQualityMode.FINE,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val cropSpec: InpaintCropSpec = InpaintCropSpec.of(memoryTier, qualityMode)
    private val blender = InpaintCropBlender(cropSpec)
    private val resolver = MiGanModelResolver(downloadManager, variantDetector, modelSpec)
    private val executor = MiGanTileExecutor(
        encoder = encoder,
        generatorRunner = generatorRunner,
        pipelineRunner = pipelineRunner,
        assetStore = assetStore,
        blender = blender,
        clock = clock
    )

    /** Geometry and batch budget this instance plans with. */
    fun cropSpec(): InpaintCropSpec = cropSpec

    /** Builds the deterministic tile plan of [mask] without touching the model. */
    fun plan(mask: InpaintMaskBuffer): InpaintCropPlan = blender.plan(mask)

    /**
     * Resolves the installed artifact and its variant.
     *
     * @return The selected file and variant, or a failure carrying [MiGanInpaintException]
     *   (`MODEL_MISSING`, `INCOMPATIBLE`, `RUNTIME_ERROR`).
     */
    suspend fun resolveModel(): Result<MiGanModelSelection> = resolver.resolve()

    /**
     * Repairs every painted hole and persists one patch per tile.
     *
     * @param photoId Photo the patches belong to; must be a persisted id.
     * @param sourceArgb Opaque `ARGB_8888` source pixels in pre-crop original space.
     * @param width Source width.
     * @param height Source height.
     * @param mask User mask in source pixels; `255` marks a hole.
     * @param onProgress Completion in `0..100`, called after every finished tile.
     * @return Per-tile outcomes, or a failure carrying [MiGanInpaintException].
     */
    suspend fun inpaint(
        photoId: Long,
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        mask: InpaintMaskBuffer,
        onProgress: (Int) -> Unit = {}
    ): Result<MiGanInpaintOutcome> = withContext(Dispatchers.Default) {
        validateInput(photoId, sourceArgb, width, height, mask)?.let { return@withContext it }
        val plan = blender.plan(mask)
        if (plan.isEmpty) {
            return@withContext failure(MiGanFailureReason.NO_STROKES)
        }
        val selection = resolveModel().getOrElse { return@withContext Result.failure(it) }
        execute(
            photoId = photoId,
            sourceArgb = sourceArgb,
            width = width,
            height = height,
            mask = mask,
            tiles = plan.tiles,
            selection = selection,
            scale = plan.spec,
            onTile = {},
            onProgress = onProgress
        )
    }

    /**
     * Executes a caller-chosen slice of the plan, one patch per tile.
     *
     * This is the E2 batch entry point: a caller splits `plan.tiles` into batches of
     * `plan.spec.batchTiles`, passes the [selection] it already resolved (optional), commits the
     * returned [MiGanTileOutcome]s after each batch and persists the measured `elapsedMs` for the
     * ETA. Tiles do not have to come from the same plan object, but their crop must stay inside
     * the source and respect the tier limit.
     *
     * @param tiles Tiles to execute, in order.
     * @param selection Reuse of an earlier [resolveModel]; `null` re-resolves and re-probes.
     * @param onTile Called after each tile is persisted, on the caller's dispatcher.
     * @param onProgress Batch-local completion in `0..100`.
     */
    suspend fun inpaintTiles(
        photoId: Long,
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        mask: InpaintMaskBuffer,
        tiles: List<InpaintTilePlan>,
        selection: MiGanModelSelection? = null,
        onTile: (MiGanTileOutcome) -> Unit = {},
        onProgress: (Int) -> Unit = {}
    ): Result<MiGanInpaintOutcome> = withContext(Dispatchers.Default) {
        validateInput(photoId, sourceArgb, width, height, mask)?.let { return@withContext it }
        validateTiles(tiles, width, height)?.let { return@withContext it }
        if (tiles.isEmpty()) {
            return@withContext failure(MiGanFailureReason.NO_STROKES)
        }
        val resolved = selection ?: resolveModel().getOrElse { return@withContext Result.failure(it) }
        execute(
            photoId = photoId,
            sourceArgb = sourceArgb,
            width = width,
            height = height,
            mask = mask,
            tiles = tiles,
            selection = resolved,
            scale = cropSpec,
            onTile = onTile,
            onProgress = onProgress
        )
    }

    private suspend fun execute(
        photoId: Long,
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        mask: InpaintMaskBuffer,
        tiles: List<InpaintTilePlan>,
        selection: MiGanModelSelection,
        scale: InpaintCropSpec,
        onTile: (MiGanTileOutcome) -> Unit,
        onProgress: (Int) -> Unit
    ): Result<MiGanInpaintOutcome> {
        val coroutineContext = currentCoroutineContext()
        onProgress(0)
        return try {
            val run = sessionManager.withSession(selection.file) { handle ->
                val outcomes = ArrayList<MiGanTileOutcome>(tiles.size)
                val total = tiles.size.coerceAtLeast(1)
                for (tile in tiles) {
                    coroutineContext.ensureActive()
                    val outcome = executor.execute(
                        handle = handle,
                        variant = selection.variant,
                        photoId = photoId,
                        tile = tile,
                        mask = mask,
                        sourceArgb = sourceArgb,
                        width = width,
                        height = height
                    )
                    outcomes.add(outcome)
                    onTile(outcome)
                    onProgress(outcomes.size * 100 / total)
                }
                outcomes.toList()
            }
            run.fold(
                onSuccess = { completed ->
                    Result.success(
                        MiGanInpaintOutcome(
                            selection = selection,
                            cropSpec = scale,
                            tiles = completed
                        )
                    )
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

    private fun validateInput(
        photoId: Long,
        sourceArgb: IntArray,
        width: Int,
        height: Int,
        mask: InpaintMaskBuffer
    ): Result<MiGanInpaintOutcome>? {
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

    private fun validateTiles(
        tiles: List<InpaintTilePlan>,
        width: Int,
        height: Int
    ): Result<MiGanInpaintOutcome>? {
        for (tile in tiles) {
            val crop = tile.cropRect
            if (crop.isEmpty || crop.right > width || crop.bottom > height) {
                return invalidInput("Inpaint tile crop escapes the source")
            }
            if (crop.width > cropSpec.maxCropSidePx || crop.height > cropSpec.maxCropSidePx) {
                return invalidInput("Inpaint tile crop exceeds the device budget")
            }
        }
        return null
    }

    private fun invalidInput(message: String): Result<MiGanInpaintOutcome> =
        failure(MiGanFailureReason.INVALID_INPUT, message = message)

    private fun mapSessionFailure(error: Throwable): Result<MiGanInpaintOutcome> {
        val reason = when ((error as? OrtSessionUnavailableException)?.reason) {
            OrtSessionFailureReason.MODEL_MISSING -> MiGanFailureReason.MODEL_MISSING
            OrtSessionFailureReason.OUT_OF_MEMORY -> MiGanFailureReason.RUNTIME_ERROR
            OrtSessionFailureReason.RUNTIME_ERROR, null -> MiGanFailureReason.RUNTIME_ERROR
        }
        Timber.e(error, "MI-GAN session could not run the inpainting tiles")
        return failure(reason, error)
    }

    private fun <T> failure(
        reason: MiGanFailureReason,
        cause: Throwable? = null,
        message: String = cause?.message ?: reason.name
    ): Result<T> = Result.failure(MiGanInpaintException(reason, message, cause))
}
