package cn.devcxl.photosync.editor.domain

import kotlin.math.max

/**
 * Guided-filter kernels shared by the Pre-pass transmission smoothing and the Pass C luma noise
 * reduction.
 *
 * Two shapes are needed:
 * - [smooth] is the textbook radius-8 guided filter used once per Pre-pass on the 256px analysis
 *   grid, where accuracy matters more than cost.
 * - [partialWindow] is the single-window `3 x 3` form used per pixel on the full preview: the local
 *   variance estimates the guide's linear coefficient, so flat sensor noise is averaged away while a
 *   real edge (`var >> epsilon`) passes through untouched. The GLSL Pass C stage evaluates exactly
 *   the same expression, which keeps the canvas and the cached preview pixel-identical.
 */
internal object GuidedFilter {

    private const val EPSILON_FLOOR = 1e-8f

    /**
     * Guided filter of [input] with [guide] as the joint bilateral guide.
     *
     * @param radius Window radius in pixels.
     * @param epsilon Regularisation in guide units; larger values smooth across stronger edges.
     */
    fun smooth(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        epsilon: Float
    ): FloatArray {
        require(guide.size == width * height && input.size == width * height) {
            "Guide and input must match the ${width}x$height grid"
        }
        val meanGuide = ImageFilters.boxMean(guide, width, height, radius)
        val meanInput = ImageFilters.boxMean(input, width, height, radius)
        val guideSquares = FloatArray(guide.size) { guide[it] * guide[it] }
        val guideInput = FloatArray(guide.size) { guide[it] * input[it] }
        val meanGuideSquares = ImageFilters.boxMean(guideSquares, width, height, radius)
        val meanGuideInput = ImageFilters.boxMean(guideInput, width, height, radius)

        val coefficients = FloatArray(guide.size) { index ->
            val variance = max(meanGuideSquares[index] - meanGuide[index] * meanGuide[index], 0.0f)
            val covariance = meanGuideInput[index] - meanGuide[index] * meanInput[index]
            covariance / (variance + max(epsilon, EPSILON_FLOOR))
        }
        val intercepts = FloatArray(guide.size) { index ->
            meanInput[index] - coefficients[index] * meanGuide[index]
        }
        val meanCoefficients = ImageFilters.boxMean(coefficients, width, height, radius)
        val meanIntercepts = ImageFilters.boxMean(intercepts, width, height, radius)
        return FloatArray(guide.size) { index ->
            meanCoefficients[index] * guide[index] + meanIntercepts[index]
        }
    }

    /**
     * Single-window guided smoothing: `a * (value - mean) + mean` with `a = var / (var + epsilon)`.
     *
     * A hard edge keeps `a` near 1 (almost no smoothing) while grain-level variance keeps `a` near 0
     * (full local averaging).
     */
    fun partialWindow(
        values: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        epsilon: Float
    ): FloatArray {
        val mean = ImageFilters.boxMean(values, width, height, radius)
        val squares = FloatArray(values.size) { values[it] * values[it] }
        val meanSquares = ImageFilters.boxMean(squares, width, height, radius)
        val safeEpsilon = max(epsilon, EPSILON_FLOOR)
        return FloatArray(values.size) { index ->
            val variance = max(meanSquares[index] - mean[index] * mean[index], 0.0f)
            val coefficient = variance / (variance + safeEpsilon)
            coefficient * (values[index] - mean[index]) + mean[index]
        }
    }
}
