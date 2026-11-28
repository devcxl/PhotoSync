package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Builds the deterministic crop/tile layout of one inpainting run (spec §6.3 and §7.3).
 *
 * ## Geometry contract
 * - Painted pixels are grouped by 8-neighbour connectivity; blobs whose 128 px context rects
 *   overlap are merged, so a dotted stroke does not become one model run per dab.
 * - A component whose mask bbox fits in the tier's **fine** core ([InpaintCropSpec.singleTileMaxSidePx])
 *   gets one tile: a square crop of `max(bbox + 2 × margin, 320)` centred on the bbox and clipped to
 *   the image.
 * - A larger bbox is cut on the spec's core grid; every tile samples its core plus the 128 px
 *   context margin, so a fine standard tile is exactly 640 + 2 × 128 = 896 px and a fast standard
 *   tile is 768 + 2 × 128 = 1024 px.
 * - No crop ever exceeds [InpaintCropSpec.maxCropSidePx] on either side, which bounds the per-tile
 *   tensor and keeps a 24 MP source from being resized into one huge request.
 * - Nothing here rejects a plan: an arbitrarily large stroke becomes a larger grid (spec §7.3
 *   replaced the old `InpaintPlanTooLargeException` with budgeted batching).
 */
internal class InpaintCropPlanner(private val spec: InpaintCropSpec = InpaintCropSpec.DEFAULT) {

    private val extractor = InpaintComponentExtractor(spec.contextMarginPx)

    fun plan(mask: InpaintMaskBuffer): InpaintCropPlan {
        if (!mask.hasPaintedPixels()) {
            return InpaintCropPlan(mask.width, mask.height, spec, emptyList())
        }
        val components = extractor.extract(mask).mapIndexed { index, maskBounds ->
            InpaintComponentPlan(
                index = index,
                maskBounds = maskBounds,
                tiles = buildTiles(maskBounds, mask.width, mask.height)
            )
        }
        return InpaintCropPlan(mask.width, mask.height, spec, components)
    }

    private fun buildTiles(
        maskBounds: InpaintPixelRect,
        imageWidth: Int,
        imageHeight: Int
    ): List<InpaintTilePlan> {
        val margin = spec.contextMarginPx
        if (maskBounds.width <= spec.singleTileMaxSidePx &&
            maskBounds.height <= spec.singleTileMaxSidePx
        ) {
            val context = maskBounds.expandedBy(margin).clampedTo(imageWidth, imageHeight)
            val side = max(context.width, context.height)
                .coerceIn(spec.minCropSidePx, spec.maxCropSidePx)
                .coerceAtLeast(1)
            val crop = squareCentredOn(maskBounds, side, imageWidth, imageHeight)
            return listOf(InpaintTilePlan(index = 0, coreRect = maskBounds, cropRect = crop))
        }
        val columnCores = axisCores(maskBounds.left, maskBounds.right, imageWidth)
        val rowCores = axisCores(maskBounds.top, maskBounds.bottom, imageHeight)
        val tiles = ArrayList<InpaintTilePlan>(columnCores.size * rowCores.size)
        for (rowIndex in rowCores.indices) {
            for (columnIndex in columnCores.indices) {
                val columnCore = columnCores[columnIndex]
                val rowCore = rowCores[rowIndex]
                val core = InpaintPixelRect(
                    left = columnCore.first,
                    top = rowCore.first,
                    right = columnCore.last + 1,
                    bottom = rowCore.last + 1
                )
                tiles.add(
                    InpaintTilePlan(
                        index = tiles.size,
                        coreRect = core,
                        cropRect = core.expandedBy(margin).clampedTo(imageWidth, imageHeight),
                        leftOverlapPx = overlapWithPrevious(columnCores, columnIndex),
                        rightOverlapPx = overlapWithNext(columnCores, columnIndex),
                        topOverlapPx = overlapWithPrevious(rowCores, rowIndex),
                        bottomOverlapPx = overlapWithNext(rowCores, rowIndex)
                    )
                )
            }
        }
        return tiles
    }

    /** Width of the core band shared with the previous cell; `0` when they do not overlap. */
    private fun overlapWithPrevious(cores: List<IntRange>, index: Int): Int {
        if (index == 0) return 0
        return (cores[index - 1].last + 1 - cores[index].first).coerceAtLeast(0)
    }

    /** Width of the core band shared with the next cell; `0` when they do not overlap. */
    private fun overlapWithNext(cores: List<IntRange>, index: Int): Int {
        if (index >= cores.lastIndex) return 0
        return (cores[index].last + 1 - cores[index + 1].first).coerceAtLeast(0)
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
     * Mask cells along one axis. A long axis is cut on the spec's grid stepping [InpaintCropSpec.stepPx]
     * (so adjacent cells overlap by [InpaintCropSpec.overlapPx]); a short axis gets one cell centred on
     * the bbox, which keeps the resulting crop at least the spec's minimum side.
     */
    private fun axisCores(start: Int, end: Int, axisSize: Int): List<IntRange> {
        val length = end - start
        if (length <= 0) return emptyList()
        val core = min(spec.coreSidePx, axisSize)
        if (length <= spec.coreSidePx) {
            val centre = (start + end) / 2
            val coreStart = (centre - core / 2).coerceIn(0, axisSize - core)
            return listOf(coreStart until coreStart + core)
        }
        val step = spec.stepPx.coerceAtLeast(1)
        val starts = ArrayList<Int>()
        var position = start
        while (position + spec.coreSidePx < end) {
            starts.add(position)
            position += step
        }
        val tail = end - spec.coreSidePx
        if (starts.isEmpty() || starts.last() != tail) starts.add(tail)
        return starts.map { it until it + spec.coreSidePx }
    }
}
