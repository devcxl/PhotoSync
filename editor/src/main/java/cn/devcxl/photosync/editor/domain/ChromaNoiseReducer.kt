package cn.devcxl.photosync.editor.domain

import kotlin.math.exp

/**
 * CbCr chroma planes of a display-domain frame, split out so luminance and chroma noise reduction
 * can be resolved from the same source samples in one pass.
 */
internal class ChromaPlanes(val cb: FloatArray, val cr: FloatArray)

/**
 * CbCr `5 x 5` edge-guided chroma noise reduction.
 *
 * Colour mottling is low-frequency noise, so it can be smoothed far more aggressively than
 * luminance — but not across a real colour edge. Each tap is therefore weighted by a Gaussian of the
 * centre-to-neighbour luminance difference: a flat patch averages its neighbours, while a
 * luma-bounded edge keeps its own chroma.
 */
internal object ChromaNoiseReducer {

    /** Width of the separable CbCr kernel, mirrored by the GLSL Pass C contract. */
    const val KERNEL_SIZE = 5

    private const val RADIUS = 2
    private const val EDGE_SIGMA = 0.08f
    private const val WEIGHT_EPSILON = 1e-6f
    private const val CB_SCALE = 1.8556f
    private const val CR_SCALE = 1.5748f
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
    private const val CHANNELS = 3

    /** Binomial `[1, 2, 3, 2, 1]` spatial weights of the separable 5-tap kernel. */
    private val SPATIAL_TAPS = floatArrayOf(1.0f, 2.0f, 3.0f, 2.0f, 1.0f)

    /** Splits the interleaved sRGB [srgb] frame into BT.709 CbCr planes. */
    fun split(srgb: FloatArray): ChromaPlanes {
        val luma = ImageFilters.luminance(srgb)
        val cb = FloatArray(luma.size)
        val cr = FloatArray(luma.size)
        for (index in luma.indices) {
            val base = index * CHANNELS
            cb[index] = (srgb[base + 2] - luma[index]) / CB_SCALE
            cr[index] = (srgb[base] - luma[index]) / CR_SCALE
        }
        return ChromaPlanes(cb, cr)
    }

    /** Separable edge-guided smoothing of [chroma], guided by [luma]. */
    fun smooth(
        chroma: ChromaPlanes,
        luma: FloatArray,
        width: Int,
        height: Int
    ): ChromaPlanes = ChromaPlanes(
        cb = separableEdgeGuided(chroma.cb, luma, width, height),
        cr = separableEdgeGuided(chroma.cr, luma, width, height)
    )

    /** Rebuilds sRGB from [luma], [cb] and [cr], clamping every channel into `[0, 1]`. */
    fun merge(luma: FloatArray, cb: FloatArray, cr: FloatArray, out: FloatArray) {
        for (index in luma.indices) {
            val base = index * CHANNELS
            val y = luma[index]
            val red = y + CR_SCALE * cr[index]
            val blue = y + CB_SCALE * cb[index]
            val green = (y - LUMA_R * red - LUMA_B * blue) / LUMA_G
            out[base] = red.coerceIn(0.0f, 1.0f)
            out[base + 1] = green.coerceIn(0.0f, 1.0f)
            out[base + 2] = blue.coerceIn(0.0f, 1.0f)
        }
    }

    private fun separableEdgeGuided(
        plane: FloatArray,
        luma: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        val horizontal = edgeGuidedPass(plane, luma, width, height, horizontal = true)
        return edgeGuidedPass(horizontal, luma, width, height, horizontal = false)
    }

    private fun edgeGuidedPass(
        plane: FloatArray,
        luma: FloatArray,
        width: Int,
        height: Int,
        horizontal: Boolean
    ): FloatArray {
        val out = FloatArray(plane.size)
        val inverseTwoSigmaSquared = 1.0f / (2.0f * EDGE_SIGMA * EDGE_SIGMA)
        for (index in out.indices) {
            val x = index % width
            val y = index / width
            var sum = 0.0f
            var weight = 0.0f
            for (tap in -RADIUS..RADIUS) {
                val sampleX = if (horizontal) (x + tap).coerceIn(0, width - 1) else x
                val sampleY = if (horizontal) y else (y + tap).coerceIn(0, height - 1)
                val sampleIndex = sampleY * width + sampleX
                val difference = luma[sampleIndex] - luma[index]
                val tapWeight = SPATIAL_TAPS[tap + RADIUS] *
                    exp(-difference * difference * inverseTwoSigmaSquared)
                sum += plane[sampleIndex] * tapWeight
                weight += tapWeight
            }
            out[index] = if (weight > WEIGHT_EPSILON) {
                sum / weight
            } else {
                plane[index]
            }
        }
        return out
    }
}
