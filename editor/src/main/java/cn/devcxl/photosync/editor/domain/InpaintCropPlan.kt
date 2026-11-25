package cn.devcxl.photosync.editor.domain

/**
 * One MI-GAN request: a square (or border-clipped) source crop resampled to 512 x 512.
 *
 * @property index Zero-based tile order inside its component.
 * @property core The mask region this tile is responsible for. In the single-tile case it is the
 *   tight mask bbox; in the tiled case it is one 256 px grid cell.
 * @property crop Source pixels sampled by the model input. It always contains [core] plus the
 *   context margin and never exceeds 1024 px on either side.
 */
data class InpaintTilePlan(
    val index: Int,
    val core: InpaintPixelRect,
    val crop: InpaintPixelRect
)

/**
 * One connected brush blob and the ordered tiles that repair it.
 *
 * @property index Stable index inside [InpaintCropPlan.components]; patch order follows it.
 * @property maskBounds Tight bounding box of the painted pixels.
 * @property patch Pre-crop region covered by the emitted RGB patch and alpha mask. It is the union
 *   of every tile crop, so a component always produces exactly one patch.
 * @property tiles Tiles to run serially, in this order.
 */
data class InpaintComponentPlan(
    val index: Int,
    val maskBounds: InpaintPixelRect,
    val patch: InpaintPixelRect,
    val tiles: List<InpaintTilePlan>
)

/**
 * Deterministic crop plan of one inpainting confirmation.
 *
 * @property sourceWidth Width of the image the mask was painted on.
 * @property sourceHeight Height of the image the mask was painted on.
 * @property components Components in top-left reading order; the empty plan means "nothing painted".
 */
data class InpaintCropPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val components: List<InpaintComponentPlan>
) {

    /** Number of serial MI-GAN inferences this plan needs. */
    val tileCount: Int
        get() = components.sumOf { it.tiles.size }

    /** True when the user painted nothing the planner considers a hole. */
    val isEmpty: Boolean
        get() = components.isEmpty()
}

/**
 * One lossless local repair patch plus the feathered alpha mask that applies it.
 *
 * [argb] already contains the color-matched generated pixels; [alpha] is `0` outside the feathered
 * hole and `255` where the generated content must fully replace the source. Compositing is
 * `source * (1 - alpha) + patch * alpha`, which keeps every pixel outside the mask byte-identical.
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
 * Raised when a single confirmation would need more work than the device budget allows.
 *
 * The UI turns this into "paint a smaller area" guidance instead of starting a run that would
 * exhaust memory or keep the user waiting for minutes.
 */
class InpaintPlanTooLargeException(message: String) : IllegalArgumentException(message)

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
