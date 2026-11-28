package cn.devcxl.photosync.editor.domain

/**
 * One model request: the source crop a tile reads and the core it is allowed to write.
 *
 * @property index Zero-based tile order inside its component.
 * @property coreRect The region this tile owns. In the single-tile case it is the tight mask
 *   bbox; in the tiled case it is one grid cell, clipped to the image at the borders.
 * @property cropRect Source pixels sampled by the model input. It contains [coreRect] plus the
 *   context margin and never exceeds [InpaintCropSpec.maxCropSidePx] on either side.
 * @property leftOverlapPx Width of the core band shared with the tile to the left; `0` at the
 *   image border and between nominally non-overlapping fast-mode cells.
 * @property rightOverlapPx Width of the core band shared with the tile to the right.
 * @property topOverlapPx Width of the core band shared with the tile above.
 * @property bottomOverlapPx Width of the core band shared with the tile below.
 */
data class InpaintTilePlan(
    val index: Int,
    val coreRect: InpaintPixelRect,
    val cropRect: InpaintPixelRect,
    val leftOverlapPx: Int = 0,
    val rightOverlapPx: Int = 0,
    val topOverlapPx: Int = 0,
    val bottomOverlapPx: Int = 0
) {

    init {
        require(index >= 0) { "Inpaint tile index must not be negative" }
        require(cropRect.containsRect(coreRect)) { "Inpaint tile crop must contain its core" }
        require(leftOverlapPx >= 0 && rightOverlapPx >= 0) { "Inpaint tile overlap must not be negative" }
        require(topOverlapPx >= 0 && bottomOverlapPx >= 0) { "Inpaint tile overlap must not be negative" }
        require(leftOverlapPx <= coreRect.width && rightOverlapPx <= coreRect.width) {
            "Inpaint tile horizontal overlap must not exceed its core"
        }
        require(topOverlapPx <= coreRect.height && bottomOverlapPx <= coreRect.height) {
            "Inpaint tile vertical overlap must not exceed its core"
        }
    }

    /** True when a tile to the left shares an overlapping core band, so a ramp must fade to it. */
    val hasLeftNeighbor: Boolean
        get() = leftOverlapPx > 0

    /** True when a tile to the right shares an overlapping core band. */
    val hasRightNeighbor: Boolean
        get() = rightOverlapPx > 0

    /** True when a tile above shares an overlapping core band. */
    val hasTopNeighbor: Boolean
        get() = topOverlapPx > 0

    /** True when a tile below shares an overlapping core band. */
    val hasBottomNeighbor: Boolean
        get() = bottomOverlapPx > 0

    /** True when at least one neighbour shares an overlapping core band. */
    val hasNeighbor: Boolean
        get() = hasLeftNeighbor || hasRightNeighbor || hasTopNeighbor || hasBottomNeighbor
}

/**
 * One connected brush blob and the ordered tiles that repair it.
 *
 * @property index Stable index inside [InpaintCropPlan.components]; patch order follows it.
 * @property maskBounds Tight bounding box of the painted pixels.
 * @property tiles Tiles to run serially, in this order. Every tile produces its own patch.
 */
data class InpaintComponentPlan(
    val index: Int,
    val maskBounds: InpaintPixelRect,
    val tiles: List<InpaintTilePlan>
)

/**
 * Deterministic crop plan of one inpainting confirmation.
 *
 * A component is only a grouping of tiles: it no longer owns a union patch, because the emitted
 * patch granularity is one tile = one [InpaintBlendPatch] (spec §6.3 / §12 #6).
 *
 * @property sourceWidth Width of the image the mask was painted on.
 * @property sourceHeight Height of the image the mask was painted on.
 * @property spec Geometry and batch budget every tile of this plan was built from.
 * @property components Components in top-left reading order; the empty plan means "nothing painted".
 */
data class InpaintCropPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val spec: InpaintCropSpec,
    val components: List<InpaintComponentPlan>
) {

    /** Number of serial MI-GAN inferences this plan needs. */
    val tileCount: Int
        get() = components.sumOf { it.tiles.size }

    /** Every tile of every component, in execution order. */
    val tiles: List<InpaintTilePlan>
        get() = components.flatMap { it.tiles }

    /** True when the user painted nothing the planner considers a hole. */
    val isEmpty: Boolean
        get() = components.isEmpty()
}

/**
 * One lossless local repair patch plus the feathered alpha mask that applies it.
 *
 * [argb] already contains the color-matched generated pixels; [alpha] is `0` wherever the model
 * must not change the source and `255` where the generated content must fully replace it.
 * Compositing is `source * (1 - alpha) + patch * alpha`, which keeps every pixel outside the
 * feathered hole byte-identical.
 */
class InpaintBlendPatch(
    val bbox: InpaintBBox,
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val alpha: ByteArray
) {

    init {
        require(width > 0 && height > 0) { "Inpaint patch dimensions must be positive" }
        require(argb.size == width * height) { "Inpaint patch payload does not match its size" }
        require(alpha.size == width * height) { "Inpaint patch alpha does not match its size" }
    }

    /** Unsigned alpha of one patch pixel, in `0..255`. */
    fun alphaAt(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 0xFF
}

/**
 * Converts a pixel rect into the normalized pre-crop bbox persisted by the `EditGraph`.
 */
fun InpaintPixelRect.toInpaintBBox(sourceWidth: Int, sourceHeight: Int): InpaintBBox {
    require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
    return InpaintBBox(
        left = left.toFloat() / sourceWidth,
        top = top.toFloat() / sourceHeight,
        right = right.toFloat() / sourceWidth,
        bottom = bottom.toFloat() / sourceHeight
    )
}
