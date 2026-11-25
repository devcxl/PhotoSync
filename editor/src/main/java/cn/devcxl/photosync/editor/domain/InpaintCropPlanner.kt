package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Builds the deterministic crop/tile layout of one inpainting run.
 *
 * ## Geometry contract (spec §4.2)
 * - Painted pixels are grouped by 8-neighbour connectivity; blobs whose 128 px context rects
 *   overlap are merged, so a dotted stroke does not become one model run per dab.
 * - A component whose mask bbox fits in 256 x 256 gets one crop: `max(bbox, 512)` square, centred on
 *   the bbox, clipped to the image, with at least 128 px of context around the mask.
 * - A larger bbox is cut on a 256 px grid with 128 px overlap; every tile samples its core plus the
 *   128 px context margin, so the common case is exactly 512 x 512 at 1:1.
 * - No crop ever exceeds [MAX_CROP_SIDE_PX] on either side, which bounds the per-tile tensor and
 *   keeps a 24 MP source from being resized into one huge request.
 */
internal class InpaintCropPlanner(
    private val contextMarginPx: Int = CONTEXT_MARGIN_PX,
    private val minCropSidePx: Int = MIN_CROP_SIDE_PX,
    private val maxCropSidePx: Int = MAX_CROP_SIDE_PX,
    private val tileCorePx: Int = TILE_CORE_PX,
    private val tileOverlapPx: Int = TILE_OVERLAP_PX,
    private val maxTilesPerComponent: Int = MAX_TILES_PER_COMPONENT,
    private val maxPatchPixels: Int = MAX_PATCH_PIXELS
) {

    private val extractor = InpaintComponentExtractor(contextMarginPx)

    init {
        require(contextMarginPx >= 0) { "Context margin must not be negative" }
        require(minCropSidePx > 0) { "Minimum crop side must be positive" }
        require(maxCropSidePx >= minCropSidePx) { "Maximum crop side must cover the minimum" }
        require(tileCorePx > 0 && tileOverlapPx in 0 until tileCorePx) { "Invalid tile grid" }
    }

    fun plan(mask: InpaintMaskBuffer): InpaintCropPlan {
        if (!mask.hasPaintedPixels()) {
            return InpaintCropPlan(mask.width, mask.height, emptyList())
        }
        val components = extractor.extract(mask).mapIndexed { index, maskBounds ->
            val tiles = buildTiles(maskBounds, mask.width, mask.height)
            val patch = tiles.map { it.crop }.reduce(InpaintPixelRect::union)
            if (patch.width * patch.height > maxPatchPixels) {
                throw InpaintPlanTooLargeException(
                    "Inpaint patch ${patch.width}x${patch.height} exceeds the ${maxPatchPixels}px budget"
                )
            }
            if (tiles.size > maxTilesPerComponent) {
                throw InpaintPlanTooLargeException(
                    "Inpaint component needs ${tiles.size} tiles, over the $maxTilesPerComponent budget"
                )
            }
            InpaintComponentPlan(
                index = index,
                maskBounds = maskBounds,
                patch = patch,
                tiles = tiles
            )
        }
        return InpaintCropPlan(mask.width, mask.height, components)
    }

    private fun buildTiles(
        maskBounds: InpaintPixelRect,
        imageWidth: Int,
        imageHeight: Int
    ): List<InpaintTilePlan> {
        if (maskBounds.width <= tileCorePx && maskBounds.height <= tileCorePx) {
            val context = maskBounds.expandedBy(contextMarginPx).clampedTo(imageWidth, imageHeight)
            val side = max(context.width, context.height)
                .coerceIn(minCropSidePx, maxCropSidePx)
                .coerceAtLeast(1)
            val crop = squareCentredOn(maskBounds, side, imageWidth, imageHeight)
            return listOf(InpaintTilePlan(index = 0, core = maskBounds, crop = crop))
        }
        val columnCores = axisCores(maskBounds.left, maskBounds.right, imageWidth)
        val rowCores = axisCores(maskBounds.top, maskBounds.bottom, imageHeight)
        val tiles = ArrayList<InpaintTilePlan>(columnCores.size * rowCores.size)
        for (rowCore in rowCores) {
            for (columnCore in columnCores) {
                val core = InpaintPixelRect(
                    left = columnCore.first,
                    top = rowCore.first,
                    right = columnCore.last + 1,
                    bottom = rowCore.last + 1
                )
                tiles.add(
                    InpaintTilePlan(
                        index = tiles.size,
                        core = core,
                        crop = core.expandedBy(contextMarginPx).clampedTo(imageWidth, imageHeight)
                    )
                )
            }
        }
        return tiles
    }

    /**
     * Square crop centred on [maskBounds], shifted back inside the image and finally clipped when
     * the source itself is smaller than [side].
     */
    private fun squareCentredOn(
        maskBounds: InpaintPixelRect,
        side: Int,
        imageWidth: Int,
        imageHeight: Int
    ): InpaintPixelRect {
        val centreX = (maskBounds.left + maskBounds.right) / 2
        val centreY = (maskBounds.top + maskBounds.bottom) / 2
        var left = centreX - side / 2
        var top = centreY - side / 2
        var right = left + side
        var bottom = top + side
        if (left < 0) {
            right -= left
            left = 0
        }
        if (top < 0) {
            bottom -= top
            top = 0
        }
        if (right > imageWidth) {
            left -= right - imageWidth
            right = imageWidth
        }
        if (bottom > imageHeight) {
            top -= bottom - imageHeight
            bottom = imageHeight
        }
        return InpaintPixelRect(left, top, right, bottom).clampedTo(imageWidth, imageHeight)
    }

    /**
     * Mask cells along one axis. A long axis is cut on a 256 px grid stepping 128 px (so adjacent
     * cells overlap by exactly 128 px); a short axis gets one 256 px cell centred on the bbox, which
     * keeps the resulting crop at least 512 px even for a thin stroke.
     */
    private fun axisCores(start: Int, end: Int, axisSize: Int): List<IntRange> {
        val length = end - start
        if (length <= 0) return emptyList()
        val core = min(tileCorePx, axisSize)
        if (length <= tileCorePx) {
            val centre = (start + end) / 2
            val coreStart = (centre - core / 2).coerceIn(0, axisSize - core)
            return listOf(coreStart until coreStart + core)
        }
        val step = tileCorePx - tileOverlapPx
        val starts = ArrayList<Int>()
        var position = start
        while (position + tileCorePx < end) {
            starts.add(position)
            position += step
        }
        val tail = end - tileCorePx
        if (starts.isEmpty() || starts.last() != tail) starts.add(tail)
        return starts.map { it until it + tileCorePx }
    }

    companion object {
        /** Context margin added on every side of a mask bbox (MI-GAN's official constant). */
        const val CONTEXT_MARGIN_PX: Int = 128

        /** Smallest crop side; a smaller hole is upscaled to keep the model's 512 frame useful. */
        const val MIN_CROP_SIDE_PX: Int = 512

        /** Hard cap of one crop, keeping the per-tile tensor and memory bounded. */
        const val MAX_CROP_SIDE_PX: Int = 1024

        /** Grid cell used when a mask bbox does not fit in one 1:1 crop. */
        const val TILE_CORE_PX: Int = 256

        /** Overlap between neighbouring grid cells, on top of the 128 px context margin. */
        const val TILE_OVERLAP_PX: Int = 128

        /** Upper bound of serial inferences for one connected component. */
        const val MAX_TILES_PER_COMPONENT: Int = 256

        /** Upper bound of the emitted patch area; larger requests must be split by the user. */
        const val MAX_PATCH_PIXELS: Int = 2048 * 2048
    }
}
