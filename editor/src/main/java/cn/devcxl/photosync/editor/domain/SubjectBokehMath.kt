package cn.devcxl.photosync.editor.domain

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU reference for the Pass C simulated background bokeh.
 *
 * The blur is evaluated on **display-domain sRGB** samples, exactly where the GLSL Pass C bokeh
 * stage runs. Each tap contributes only its own background weight `(1 - alpha)`, so a foreground
 * hair does not bleed its colour into the blurred backdrop; the centre pixel is then mixed with the
 * blurred backdrop by its own background weight, which keeps the hair edge sharp without a halo or
 * a visible step.
 *
 * Sampling uses a golden-angle disc of [TAP_COUNT] points. The same offsets are computed in
 * `DetailShaderSources`, so preview, cached thumbnail and bake share one blur shape.
 */
object SubjectBokehMath {

    /** Radius cap as a fraction of the frame's short edge at `blur = 100`. */
    const val MAX_RADIUS_FRACTION = 0.015f

    /** Number of disc taps, excluding the centre sample. */
    const val TAP_COUNT = 12

    private const val GOLDEN_ANGLE_RAD = 2.3999632f
    private const val CHANNELS = 3

    /**
     * Converts the `0..100` slider into a radius in pixels of a frame with [shortEdge].
     *
     * @param maxRadiusPx Optional hard cap, used by a banded bake so taps stay inside the halo.
     */
    fun radiusPixels(blur: Int, shortEdge: Int, maxRadiusPx: Int = Int.MAX_VALUE): Int {
        if (blur <= 0 || shortEdge <= 0) return 0
        return radiusPixels(blur.coerceIn(0, 100) / 100.0f, shortEdge, maxRadiusPx)
    }

    /** Fractional variant used by the normalized [SpatialDetailParams.subjectBlur] bundle. */
    fun radiusPixels(blurFraction: Float, shortEdge: Int, maxRadiusPx: Int = Int.MAX_VALUE): Int {
        if (blurFraction <= 0.0f || shortEdge <= 0) return 0
        val fraction = blurFraction.coerceIn(0.0f, 1.0f) * MAX_RADIUS_FRACTION
        return (shortEdge * fraction).roundToInt().coerceIn(1, maxRadiusPx.coerceAtLeast(1))
    }

    /**
     * Replaces every background pixel of [srgb] with the background-weighted blur of its disc.
     *
     * @param srgb Interleaved sRGB samples in `[0, 1]`, modified in place.
     * @param weight Frame-aligned subject alpha in `[0, 1]`, one value per pixel.
     * @param radiusPx Blur radius in pixels; `0` is a no-op.
     */
    fun apply(srgb: FloatArray, width: Int, height: Int, weight: FloatArray, radiusPx: Int) {
        if (radiusPx <= 0 || width <= 0 || height <= 0) return
        if (srgb.size != width * height * CHANNELS || weight.size != width * height) return
        val offsets = discOffsets(radiusPx)
        val source = srgb.copyOf()
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val centerAlpha = weight[index].coerceIn(0.0f, 1.0f)
                val backgroundWeight = 1.0f - centerAlpha
                if (backgroundWeight <= 0.0f) continue
                var accumR = source[index * CHANNELS] * backgroundWeight
                var accumG = source[index * CHANNELS + 1] * backgroundWeight
                var accumB = source[index * CHANNELS + 2] * backgroundWeight
                var weightSum = backgroundWeight
                for (tap in offsets.indices step 2) {
                    val sampleColumn = (column + offsets[tap]).toInt().coerceIn(0, width - 1)
                    val sampleRow = (row + offsets[tap + 1]).toInt().coerceIn(0, height - 1)
                    val sampleIndex = sampleRow * width + sampleColumn
                    val tapWeight = 1.0f - weight[sampleIndex].coerceIn(0.0f, 1.0f)
                    if (tapWeight <= 0.0f) continue
                    accumR += source[sampleIndex * CHANNELS] * tapWeight
                    accumG += source[sampleIndex * CHANNELS + 1] * tapWeight
                    accumB += source[sampleIndex * CHANNELS + 2] * tapWeight
                    weightSum += tapWeight
                }
                if (weightSum <= 1e-4f) continue
                val blurredR = accumR / weightSum
                val blurredG = accumG / weightSum
                val blurredB = accumB / weightSum
                srgb[index * CHANNELS] = mix(source[index * CHANNELS], blurredR, backgroundWeight)
                srgb[index * CHANNELS + 1] = mix(source[index * CHANNELS + 1], blurredG, backgroundWeight)
                srgb[index * CHANNELS + 2] = mix(source[index * CHANNELS + 2], blurredB, backgroundWeight)
            }
        }
    }

    /** Flattened `(dx, dy)` disc offsets, shared shape with the GLSL tap loop. */
    internal fun discOffsets(radiusPx: Int): FloatArray {
        val offsets = FloatArray(TAP_COUNT * 2)
        for (tap in 0 until TAP_COUNT) {
            val angle = tap * GOLDEN_ANGLE_RAD
            val distance = sqrt((tap + 0.5f) / TAP_COUNT.toFloat()) * radiusPx
            offsets[tap * 2] = cos(angle) * distance
            offsets[tap * 2 + 1] = sin(angle) * distance
        }
        return offsets
    }

    private fun mix(from: Float, to: Float, weight: Float): Float = from + (to - from) * weight
}
