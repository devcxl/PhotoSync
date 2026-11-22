package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Y-channel unsharp mask of Pass C Stage 4.
 *
 * The kernel is a separable `[0.25, 0.5, 0.25]` low-pass whose step is the full-resolution radius
 * scaled into working pixels, so one shader and one CPU routine serve every radius between `0.5` and
 * `3.0 px`. The mask is edge-gated, threshold-gated and multiplied by `(1 - skinMask)`, which is what
 * keeps a portrait's pores and blemishes from being amplified.
 */
internal object SharpenMath {

    private const val SHARPEN_MAX_STEP_PX = 3
    private const val CENTRE_WEIGHT = 0.5f
    private const val NEIGHBOUR_WEIGHT = 0.25f
    private const val CHANNELS = 3

    /**
     * Applies the unsharp mask in place on interleaved sRGB samples in `[0, 1]`.
     *
     * @param skinMask Per-pixel skin weight; `null` suppresses nothing.
     * @param resolutionScale Working-to-full resolution ratio, so the radius stays physical.
     */
    fun apply(
        srgb: FloatArray,
        width: Int,
        height: Int,
        params: SpatialDetailParams,
        skinMask: FloatArray?,
        resolutionScale: Float
    ) {
        val amount = params.sharpenAmount.coerceIn(0.0f, PassCDetailMath.MAX_SHARPEN_AMOUNT)
        if (amount <= 0.0f) return
        val scale = resolutionScale.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        val radius = (params.sharpenRadiusPx * scale)
            .coerceIn(PassCDetailMath.MIN_WORKING_RADIUS_PX, PassCDetailMath.MAX_WORKING_RADIUS_PX)
        val step = radius.roundToInt().coerceIn(1, SHARPEN_MAX_STEP_PX)
        val luma = ImageFilters.luminance(srgb)
        val blurred = blur(luma, width, height, step)
        val masking = params.sharpenMasking.coerceIn(0.0f, 1.0f)
        for (index in luma.indices) {
            val detail = luma[index] - blurred[index]
            val gate = thresholdGate(detail, params.sharpenThreshold)
            if (gate <= 0.0f) continue
            val weight = maskingWeight(luma, width, height, index, masking)
            val skin = 1.0f - (skinMask?.get(index)?.coerceIn(0.0f, 1.0f) ?: 0.0f)
            val delta = amount * detail * gate * weight * skin
            if (delta == 0.0f) continue
            addLumaDelta(srgb, index, delta)
        }
    }

    /**
     * Soft threshold gate: detail below `threshold * THRESHOLD_SCALE` is treated as noise, then the
     * gate fades in over [PassCDetailMath.THRESHOLD_SOFTNESS] so an edge does not pop.
     */
    fun thresholdGate(detail: Float, threshold: Float): Float {
        val knee = threshold.coerceIn(0.0f, 1.0f) * PassCDetailMath.THRESHOLD_SCALE
        if (knee <= 0.0f) return 1.0f
        return ToneShaderMath.smoothStep(knee, knee + PassCDetailMath.THRESHOLD_SOFTNESS, abs(detail))
    }

    /**
     * Separable `[0.25, 0.5, 0.25]` low-pass with a step of [step] pixels, so one kernel serves every
     * radius between `0.5` and `3.0` and the GLSL stage can reproduce it with nine taps.
     */
    private fun blur(luma: FloatArray, width: Int, height: Int, step: Int): FloatArray {
        val out = FloatArray(luma.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0f
                for (dy in -1..1) {
                    val sampleY = (y + dy * step).coerceIn(0, height - 1)
                    val weightY = if (dy == 0) CENTRE_WEIGHT else NEIGHBOUR_WEIGHT
                    for (dx in -1..1) {
                        val sampleX = (x + dx * step).coerceIn(0, width - 1)
                        val weightX = if (dx == 0) CENTRE_WEIGHT else NEIGHBOUR_WEIGHT
                        sum += luma[sampleY * width + sampleX] * weightX * weightY
                    }
                }
                out[y * width + x] = sum
            }
        }
        return out
    }

    private fun maskingWeight(
        luma: FloatArray,
        width: Int,
        height: Int,
        index: Int,
        masking: Float
    ): Float {
        if (masking <= 0.0f) return 1.0f
        val gradient = Srgb8Packer.neighborGradient(luma, width, height, index)
        val edge = ToneShaderMath.smoothStep(0.0f, PassCDetailMath.MASK_GRADIENT_REFERENCE, gradient)
        return 1.0f + (edge - 1.0f) * masking
    }

    private fun addLumaDelta(srgb: FloatArray, pixelIndex: Int, delta: Float) {
        val base = pixelIndex * CHANNELS
        srgb[base] = (srgb[base] + delta).coerceIn(0.0f, 1.0f)
        srgb[base + 1] = (srgb[base + 1] + delta).coerceIn(0.0f, 1.0f)
        srgb[base + 2] = (srgb[base + 2] + delta).coerceIn(0.0f, 1.0f)
    }
}
