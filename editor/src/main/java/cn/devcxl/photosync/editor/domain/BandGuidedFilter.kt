package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Fast guided filter used to lift the 512px MODNet alpha onto a preview proxy or one bake band.
 *
 * The textbook [GuidedFilter.smooth] keeps at least twelve full-size float planes alive, which is
 * fine for a `2048 x 640` band (about 63 MB) but impossible for a 24MP frame (about 1.15 GB). This
 * implementation follows He & Sun (arXiv:1505.00996): the linear coefficients are computed on a
 * subsampled grid, bilinearly upsampled and then evaluated against the full-resolution guide. With
 * the default `s = 4` the coefficient pass costs `1/16` of the pixels while the visible difference is
 * below the precision of the 8-bit export.
 *
 * The result is always expressed in the caller's target grid, so a preview proxy and a bake band use
 * exactly the same code path.
 */
internal object BandGuidedFilter {

    /** Default subsampling factor of the fast guided filter. */
    const val DEFAULT_SUBSAMPLE = 4

    /** Hair-raising radius at a 1024px proxy, scaled by the target resolution. */
    const val RADIUS_AT_1024 = 14

    /** Regularisation in `[1e-4, 1e-3]`; `5e-4` keeps hair edges while smoothing the 512px steps. */
    const val DEFAULT_EPSILON = 5e-4f

    /**
     * Refines [alpha] with [guide] at the full [width] x [height] resolution.
     *
     * @param guide Single-channel guide in `[0, 1]`, typically Rec.709 luma of the band.
     * @param alpha Upsampled model alpha in `[0, 1]`.
     * @param radius Window radius in **target** pixels.
     * @param epsilon Guided-filter regularisation in guide units.
     * @param subsample Coefficient-grid subsampling factor.
     * @return The refined alpha, or a feathered copy of [alpha] when the coefficient pass runs out of
     *   memory. The fallback never throws, so a bake can always finish.
     */
    fun refine(
        guide: FloatArray,
        alpha: FloatArray,
        width: Int,
        height: Int,
        radius: Int = scaledRadius(width),
        epsilon: Float = DEFAULT_EPSILON,
        subsample: Int = DEFAULT_SUBSAMPLE,
        allocate: (Int) -> FloatArray = { FloatArray(it) }
    ): FloatArray {
        require(guide.size == width * height && alpha.size == width * height) {
            "Guide and alpha must match the ${width}x$height grid"
        }
        return try {
            refineInternal(guide, alpha, width, height, radius, epsilon, subsample, allocate)
        } catch (oom: OutOfMemoryError) {
            feather(alpha, width, height, radius / 2, allocate)
        }
    }

    /**
     * Scales [RADIUS_AT_1024] to a target grid, clamped to the `[8, 32]` px range that the halo of a
     * bake band can safely cover.
     */
    fun scaledRadius(width: Int): Int =
        (RADIUS_AT_1024.toFloat() * width / 1024.0f).toInt().coerceIn(8, 32)

    /**
     * Edge feathering used when the guided filter cannot run.
     *
     * Only the transition band is softened: an opaque interior keeps its original alpha and a fully
     * transparent exterior stays transparent, so a fallback cut-out still has clean edges instead of
     * a uniform blur.
     */
    fun feather(
        alpha: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        allocate: (Int) -> FloatArray = { FloatArray(it) }
    ): FloatArray {
        val safeRadius = max(radius, 1)
        return try {
            val blurred = ImageFilters.boxMean(alpha, width, height, safeRadius)
            FloatArray(alpha.size) { index ->
                val transition = 1.0f - abs(alpha[index] * 2.0f - 1.0f)
                val weight = (transition * 0.65f).coerceIn(0.0f, 0.65f)
                alpha[index] * (1.0f - weight) + blurred[index] * weight
            }
        } catch (oom: OutOfMemoryError) {
            allocate(alpha.size).also { alpha.copyInto(it) }
        }
    }

    private fun refineInternal(
        guide: FloatArray,
        alpha: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        epsilon: Float,
        subsample: Int,
        allocate: (Int) -> FloatArray
    ): FloatArray {
        val factor = subsample.coerceIn(1, 8)
        if (factor == 1 || width < 4 || height < 4) {
            return GuidedFilter.smooth(guide, alpha, width, height, radius, epsilon)
        }
        val lowWidth = ((width + factor - 1) / factor).coerceAtLeast(2)
        val lowHeight = ((height + factor - 1) / factor).coerceAtLeast(2)
        val lowGuide = ImageFilters.resamplePlane(guide, width, height, lowWidth, lowHeight)
        val lowAlpha = ImageFilters.resamplePlane(alpha, width, height, lowWidth, lowHeight)
        val coefficients = GuidedFilter.coefficients(
            guide = lowGuide,
            input = lowAlpha,
            width = lowWidth,
            height = lowHeight,
            radius = max(1, radius / factor),
            epsilon = epsilon
        )
        val fullA = ImageFilters.resamplePlane(coefficients.a, lowWidth, lowHeight, width, height)
        val fullB = ImageFilters.resamplePlane(coefficients.b, lowWidth, lowHeight, width, height)
        val output = allocate(width * height)
        for (index in output.indices) {
            output[index] = (fullA[index] * guide[index] + fullB[index]).coerceIn(0.0f, 1.0f)
        }
        return output
    }
}
