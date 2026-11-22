package cn.devcxl.photosync.editor.domain

import kotlin.math.exp

/**
 * CbCr chroma planes of a display-domain frame, split out so luminance and chroma noise reduction
 * can be resolved from the same source samples in one pass.
 */
internal class ChromaPlanes(val cb: FloatArray, val cr: FloatArray)

/**
 * CbCr `5 x 5` edge-guided chroma noise reduction, matching `DetailShaderSources.noiseReduction`
 * tap for tap.
 *
 * Each tap `(dx, dy)` in `[-2..2]^2` uses `w(dx) * w(dy) * exp(-(L_tap - L_center)^2 / 2*sigma^2)`,
 * so diagonal colour edges receive the exact same edge attenuation on CPU and GPU.
 */
internal object ChromaNoiseReducer {

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

    /** 2D `5 x 5` edge-guided smoothing of [chroma], guided by [luma]. */
    fun smooth(
        chroma: ChromaPlanes,
        luma: FloatArray,
        width: Int,
        height: Int
    ): ChromaPlanes {
        val outCb = FloatArray(chroma.cb.size)
        val outCr = FloatArray(chroma.cr.size)
        val invTwoSigmaSq = 1.0f / (2.0f * EDGE_SIGMA * EDGE_SIGMA)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val centerIndex = y * width + x
                val centerY = luma[centerIndex]
                var sumCb = 0.0f
                var sumCr = 0.0f
                var weightSum = 0.0f
                for (dy in -RADIUS..RADIUS) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val wy = SPATIAL_TAPS[dy + RADIUS]
                    for (dx in -RADIUS..RADIUS) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sampleIndex = sy * width + sx
                        val diff = luma[sampleIndex] - centerY
                        val w = wy * SPATIAL_TAPS[dx + RADIUS] * exp(-diff * diff * invTwoSigmaSq)
                        sumCb += chroma.cb[sampleIndex] * w
                        sumCr += chroma.cr[sampleIndex] * w
                        weightSum += w
                    }
                }
                if (weightSum > WEIGHT_EPSILON) {
                    outCb[centerIndex] = sumCb / weightSum
                    outCr[centerIndex] = sumCr / weightSum
                } else {
                    outCb[centerIndex] = chroma.cb[centerIndex]
                    outCr[centerIndex] = chroma.cr[centerIndex]
                }
            }
        }
        return ChromaPlanes(cb = outCb, cr = outCr)
    }

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
}
