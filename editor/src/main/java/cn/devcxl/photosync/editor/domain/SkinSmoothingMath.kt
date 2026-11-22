package cn.devcxl.photosync.editor.domain

import kotlin.math.min

/**
 * Frequency-separation skin smoothing, the **Stage 0** of physical Pass C.
 *
 * The luminance is split into a large-radius base layer and its high-frequency detail:
 * `y' = base + (y - base) * (1 - smooth * (1 - texture))`. `smooth` therefore decides how much of
 * the detail is removed, while `texture` decides how much of it survives at full smoothing (so a
 * photographer can keep pores and fabric weave). `evenTone` blends the mid-frequency Cb/Cr towards
 * its own low-frequency average, which evens blotchy colour without shifting the overall skin tone.
 *
 * Every term is weighted by the dual-mode `skinMask`, so eyes, brows, lips, hair and background keep
 * their micro detail.
 */
object SkinSmoothingMath {

    /** Base-layer blur radius as a fraction of the working short edge. */
    const val BASE_RADIUS_FRACTION = 0.012f

    /** Smallest base-layer blur radius, in working pixels. */
    const val MIN_BASE_RADIUS = 2

    /** Largest base-layer blur radius, in working pixels. */
    const val MAX_BASE_RADIUS = 24

    /** The chroma guide is blurred this many times wider than the luminance base layer. */
    const val EVEN_TONE_RADIUS_FACTOR = 4

    private const val CHANNELS = 3
    private const val CB_SCALE = 1.8556f
    private const val CR_SCALE = 1.5748f

    /**
     * Applies skin smoothing in place on interleaved sRGB samples in `[0, 1]`.
     *
     * @param skinMask `maskSize x maskSize` skin weights, or `null` when no analysis is available.
     *   A `null` mask is a hard no-op: smoothing the whole frame on a guess would blur hair and
     *   background.
     */
    fun apply(
        srgb: FloatArray,
        width: Int,
        height: Int,
        skinMask: FloatArray?,
        maskSize: Int = SkinMaskMath.MASK_SIZE,
        smooth: Float,
        texture: Float,
        evenTone: Float
    ) {
        require(srgb.size == width * height * CHANNELS) {
            "an RGB buffer needs ${width * height * CHANNELS} samples, got ${srgb.size}"
        }

        val strength = smooth.coerceIn(0.0f, 1.0f)
        val tone = evenTone.coerceIn(0.0f, 1.0f)
        if ((strength <= 0.0f && tone <= 0.0f) || skinMask == null) return

        val luma = ImageFilters.luminance(srgb)
        val adjusted = luma.copyOf()
        if (strength > 0.0f) {
            val radius = baseRadius(width, height)
            val base = SeparableGaussian.blur(luma, width, height, radius)
            val retention = 1.0f - strength * (1.0f - texture.coerceIn(0.0f, 1.0f))
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val weight = weightAt(skinMask, maskSize, width, height, x, y)
                    if (weight <= 0.0f) continue
                    val smoothed = base[index] + (luma[index] - base[index]) * retention
                    adjusted[index] = luma[index] + (smoothed - luma[index]) * weight
                }
            }
        }
        writeLuma(srgb, luma, adjusted)
        if (tone > 0.0f) applyEvenTone(srgb, width, height, adjusted, skinMask, maskSize, tone)
    }

    /** Skin weight of working-pixel `(x, y)`, bilinearly sampled from the analysis mask. */
    fun weightAt(
        skinMask: FloatArray,
        maskSize: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Float = SkinMaskMath.sampleMask(
        skinMask,
        maskSize,
        (x + 0.5f) / width.coerceAtLeast(1),
        (y + 0.5f) / height.coerceAtLeast(1)
    )

    /** Base-layer radius for a working frame of `width x height`. */
    fun baseRadius(width: Int, height: Int): Int = SeparableGaussian.radiusFor(
        shortEdge = min(width, height).coerceAtLeast(1),
        fraction = BASE_RADIUS_FRACTION,
        minimum = MIN_BASE_RADIUS,
        maximum = MAX_BASE_RADIUS
    )

    private fun applyEvenTone(
        srgb: FloatArray,
        width: Int,
        height: Int,
        luma: FloatArray,
        skinMask: FloatArray,
        maskSize: Int,
        strength: Float
    ) {
        val cb = FloatArray(luma.size)
        val cr = FloatArray(luma.size)
        for (index in luma.indices) {
            val offset = index * CHANNELS
            val y = luma[index]
            cb[index] = (srgb[offset + 2] - y) / CB_SCALE
            cr[index] = (srgb[offset] - y) / CR_SCALE
        }
        val radius = (baseRadius(width, height) * EVEN_TONE_RADIUS_FACTOR)
            .coerceAtMost(SeparableGaussian.MAX_RADIUS)
        val lowCb = SeparableGaussian.blur(cb, width, height, radius)
        val lowCr = SeparableGaussian.blur(cr, width, height, radius)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val weight = weightAt(skinMask, maskSize, width, height, x, y) * strength
                if (weight <= 0.0f) continue
                val blendedCb = cb[index] + (lowCb[index] - cb[index]) * weight
                val blendedCr = cr[index] + (lowCr[index] - cr[index]) * weight
                val luminance = luma[index]
                val red = (luminance + CR_SCALE * blendedCr).coerceIn(0.0f, 1.0f)
                val blue = (luminance + CB_SCALE * blendedCb).coerceIn(0.0f, 1.0f)
                val green = ((luminance - 0.2126f * red - 0.0722f * blue) / 0.7152f)
                    .coerceIn(0.0f, 1.0f)
                val offset = index * CHANNELS
                srgb[offset] = red
                srgb[offset + 1] = green
                srgb[offset + 2] = blue
            }
        }
    }

    private fun writeLuma(srgb: FloatArray, original: FloatArray, adjusted: FloatArray) {
        for (index in original.indices) {
            val delta = adjusted[index] - original[index]
            if (delta == 0.0f) continue
            val offset = index * CHANNELS
            srgb[offset] = (srgb[offset] + delta).coerceIn(0.0f, 1.0f)
            srgb[offset + 1] = (srgb[offset + 1] + delta).coerceIn(0.0f, 1.0f)
            srgb[offset + 2] = (srgb[offset + 2] + delta).coerceIn(0.0f, 1.0f)
        }
    }
}
