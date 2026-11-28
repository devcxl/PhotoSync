package cn.devcxl.photosync.editor.vision

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.GeneratedTilePixels
import cn.devcxl.photosync.editor.domain.InpaintBlendPatch
import cn.devcxl.photosync.editor.domain.InpaintCropBlender
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintTilePatch
import cn.devcxl.photosync.editor.domain.InpaintTilePlan

/**
 * Runs and persists exactly one tile: one model call, one [MiGanTileOutcome].
 *
 * The two variants are dispatched here instead of in the facade so the tensor contract and the
 * persistence budget stay in one place:
 * - `GENERATOR_FIXED_512` resamples the crop to 512, runs the float runner and decodes the
 *   `[1,3,512,512]` output back onto the crop.
 * - `PIPELINE_UINT8` feeds the crop at native resolution as planar RGB plus the hard inverted mask,
 *   runs the uint8 runner and reads the uint8 result directly.
 *
 * @param encoder PNG codec; tests inject a fake.
 * @param generatorRunner Legacy float executor.
 * @param pipelineRunner uint8 executor.
 * @param assetStore Content-addressed store for the patch/mask pair.
 * @param blender Generator pre-process (crop → `[1,4,512,512]` floats).
 * @param clock Millisecond clock used for the per-tile timing.
 */
internal class MiGanTileExecutor(
    private val encoder: InpaintPatchEncoder,
    private val generatorRunner: MiGanTileRunner,
    private val pipelineRunner: MiganPipelineRunner,
    private val assetStore: AiAssetStore,
    private val blender: InpaintCropBlender,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Executes and persists [tile].
     *
     * @throws MiGanInpaintException carrying `INVALID_INPUT`, `ENCODING_FAILED` or `RUNTIME_ERROR`.
     */
    fun execute(
        handle: OrtSessionHandle,
        variant: InpaintVariant,
        photoId: Long,
        tile: InpaintTilePlan,
        mask: InpaintMaskBuffer,
        sourceArgb: IntArray,
        width: Int,
        height: Int
    ): MiGanTileOutcome {
        val started = clock()
        val patch = when (variant) {
            InpaintVariant.GENERATOR_FIXED_512 -> runGeneratorTile(
                handle, tile, mask, sourceArgb, width, height
            )
            InpaintVariant.PIPELINE_UINT8 -> runPipelineTile(
                handle, tile, mask, sourceArgb, width, height
            )
        }
        val elapsed = (clock() - started).coerceAtLeast(0L)
        val reference = persistTile(photoId, patch)
        return MiGanTileOutcome(tile = tile, patch = reference, elapsedMs = elapsed)
    }

    private fun runGeneratorTile(
        handle: OrtSessionHandle,
        tile: InpaintTilePlan,
        mask: InpaintMaskBuffer,
        sourceArgb: IntArray,
        width: Int,
        height: Int
    ): InpaintBlendPatch {
        val input = blender.preprocessTile(
            sourceArgb = sourceArgb,
            sourceWidth = width,
            sourceHeight = height,
            mask = mask,
            tile = tile
        )
        if (input.size != InpaintCropBlender.MODEL_INPUT_ELEMENTS) {
            throw invalidInput("MI-GAN generator input tensor has an unexpected size")
        }
        val output = generatorRunner.run(handle, input)
        if (output.size != InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) {
            throw invalidInput("MI-GAN generator output tensor has an unexpected size")
        }
        return InpaintTilePatch.build(
            tile = tile,
            mask = mask,
            sourceArgb = sourceArgb,
            sourceWidth = width,
            sourceHeight = height,
            generated = GeneratedTilePixels.fromModelFloats(output)
        )
    }

    private fun runPipelineTile(
        handle: OrtSessionHandle,
        tile: InpaintTilePlan,
        mask: InpaintMaskBuffer,
        sourceArgb: IntArray,
        width: Int,
        height: Int
    ): InpaintBlendPatch {
        val crop = tile.cropRect
        val image = MiganPipelineCodec.encodeImageRgbUint8(sourceArgb, width, crop)
        val maskInput = MiganPipelineCodec.encodeInvertedHardMaskUint8(mask, crop)
        val output = pipelineRunner.run(handle, image, maskInput, crop.width, crop.height)
        if (output.size != MiganPipelineCodec.PIXEL_CHANNELS * crop.width * crop.height) {
            throw invalidInput("MI-GAN pipeline output tensor has an unexpected size")
        }
        val argb = MiganPipelineCodec.decodeResultRgbUint8(output, crop.width, crop.height)
        return InpaintTilePatch.build(
            tile = tile,
            mask = mask,
            sourceArgb = sourceArgb,
            sourceWidth = width,
            sourceHeight = height,
            generated = GeneratedTilePixels.fromRgb(argb, crop.width, crop.height)
        )
    }

    /**
     * Encodes and stores one tile patch.
     *
     * Both payloads are size-guarded before the write. A tile crop is ≤ 1024², so the guard cannot
     * trigger in practice; it exists so a future planner regression fails loudly instead of
     * persisting an asset the reader (`AiAssetStore.MAX_ASSET_BYTES`) would refuse (spec §7.3/§10).
     */
    private fun persistTile(photoId: Long, patch: InpaintBlendPatch): InpaintPatchRef {
        val budget = tileWorstCaseBytes(patch.width, patch.height)
        if (budget > AiAssetStore.MAX_ASSET_BYTES) {
            throw MiGanInpaintException(
                MiGanFailureReason.ENCODING_FAILED,
                "Inpaint tile ${patch.width}x${patch.height} exceeds the asset budget"
            )
        }
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
        if (patchPng.size.toLong() > AiAssetStore.MAX_ASSET_BYTES ||
            maskPng.size.toLong() > AiAssetStore.MAX_ASSET_BYTES
        ) {
            // Defensive only: a ≤ 1024² PNG is orders of magnitude below the read ceiling.
            // E2's batch retry may re-run this tile in fast mode; E1 refuses to persist bytes the
            // reader would reject.
            throw MiGanInpaintException(
                MiGanFailureReason.ENCODING_FAILED,
                "Inpaint payload exceeds the asset budget"
            )
        }
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

    /** Uncompressed RGBA patch plus 8-bit mask: PNG cannot exceed these raw sizes. */
    private fun tileWorstCaseBytes(width: Int, height: Int): Long {
        val pixels = width.toLong() * height.toLong()
        return pixels * (BYTES_PER_RGBA + BYTES_PER_MASK)
    }

    private fun invalidInput(message: String): MiGanInpaintException =
        MiGanInpaintException(MiGanFailureReason.INVALID_INPUT, message)

    private companion object {
        /** Composite RGBA bytes per patch pixel, the uncompressed PNG ceiling. */
        const val BYTES_PER_RGBA = 4L

        /** One 8-bit coverage byte per mask pixel. */
        const val BYTES_PER_MASK = 1L
    }
}
