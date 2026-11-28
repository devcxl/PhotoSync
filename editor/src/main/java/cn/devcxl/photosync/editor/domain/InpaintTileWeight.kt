package cn.devcxl.photosync.editor.domain

import kotlin.math.floor

/**
 * The single implementation of the inpainting tile alpha rule (spec §6.3).
 *
 * `alpha = feather(mask) × rampWeights`, with two hard invariants:
 *
 * 1. **Unpainted pixels never change**: wherever `mask == 0`, alpha is `0`. The measured model
 *    changes the surroundings of a hole by up to 14 levels, and this invariant is what keeps the
 *    "outside the painted area is byte-identical" promise.
 * 2. **Outside the core, alpha is always 0**: a tile only writes inside its own
 *    [InpaintTilePlan.coreRect]. The 128 px context margin exists to give the model context, not to
 *    let it overwrite a neighbour's territory.
 *
 * The ramps are only active towards a neighbour whose core overlaps this one. At an image border,
 * or between nominally non-overlapping fast-mode cells, the weight is exactly `1`. The overlap
 * widths are the **measured** ones of the plan ([InpaintTilePlan.leftOverlapPx] etc.), not a
 * constant: a bbox whose tail is clamped can overlap more than the nominal 128 px, and the two
 * neighbours always agree on that width. Over the band, the two ramps are `r` and `1 - r`.
 * At a four-tile junction the weight is the **product** of the two ramps: `r_x × r_y`. The product
 * is the bilinear partition of unity, so the four weights sum to exactly one; a `min(r_x, r_y)`
 * rule would reach `2` at that junction and double-expose the patch.
 *
 * Alpha bytes use `floor`, never round-half-up: the byte sum of adjacent tiles can be at most the
 * feathered coverage (one level below it in the worst case), so overlapping patches can never
 * exceed full opacity.
 */
object InpaintTileWeight {

    /**
     * Computes the alpha of one tile.
     *
     * @param mask Source-resolution user mask; `255` marks a hole, `0` keeps the source.
     * @param tile Tile whose crop defines the returned buffer's size and whose overlap widths
     *   define the ramps.
     * @return Row-major alpha bytes of `tile.cropRect`, `0..255`.
     */
    fun alpha(mask: InpaintMaskBuffer, tile: InpaintTilePlan): ByteArray {
        val crop = tile.cropRect
        val width = crop.width
        val height = crop.height
        val feather = InpaintMaskFeather.feather(mask, crop)
        val alpha = ByteArray(width * height)
        for (y in 0 until height) {
            val sourceY = crop.top + y
            for (x in 0 until width) {
                val sourceX = crop.left + x
                val index = y * width + x
                if (!tile.coreRect.contains(sourceX, sourceY)) continue
                if (mask.coverageAt(sourceX, sourceY) == 0) continue
                val ramp = rampWeight(tile, sourceX, sourceY)
                if (ramp <= 0.0f) continue
                val feathered = feather[index].toInt() and 0xFF
                alpha[index] = floor(feathered * ramp).toInt().coerceIn(0, 255).toByte()
            }
        }
        return alpha
    }

    /**
     * Weight of one pixel before the feather is applied.
     *
     * Exposed separately so the partition-of-unity invariant can be tested directly instead of
     * inferred from rounded bytes.
     *
     * @param tile Tile that owns the pixel.
     * @param x Source-space pixel X.
     * @param y Source-space pixel Y.
     * @return `0` outside the core, otherwise `r_x × r_y` in `[0, 1]`.
     */
    fun rampWeight(tile: InpaintTilePlan, x: Int, y: Int): Float {
        val core = tile.coreRect
        if (!core.contains(x, y)) return 0.0f
        var weight = 1.0f
        if (tile.hasLeftNeighbor && x < core.left + tile.leftOverlapPx) {
            weight *= (x - core.left).toFloat() / tile.leftOverlapPx
        }
        if (tile.hasRightNeighbor && x >= core.right - tile.rightOverlapPx) {
            weight *= (core.right - x).toFloat() / tile.rightOverlapPx
        }
        if (tile.hasTopNeighbor && y < core.top + tile.topOverlapPx) {
            weight *= (y - core.top).toFloat() / tile.topOverlapPx
        }
        if (tile.hasBottomNeighbor && y >= core.bottom - tile.bottomOverlapPx) {
            weight *= (core.bottom - y).toFloat() / tile.bottomOverlapPx
        }
        return weight
    }
}
