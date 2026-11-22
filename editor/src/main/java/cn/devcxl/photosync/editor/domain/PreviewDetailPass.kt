package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies Pass C to the 8-bit display pixels of the gallery preview cache.
 *
 * The cache is the only place a photographer sees an edited frame outside the editor, so it must run
 * the same spatial-detail chain as the GL canvas — including the 1024px Pre-pass that produces the
 * atmospheric light, the coarse transmission and the two luminance guide layers.
 */
object PreviewDetailPass {

    /** Long edge of the frame the Pre-pass analyses, matching the GL Pre-pass contract. */
    const val PREPASS_MAX_EDGE = 1024

    private const val CHANNELS = 3
    private const val EIGHT_BIT_MAX = 255.0f
    private const val MIN_RESOLUTION_SCALE = 0.05f

    /**
     * Runs Pass C over [argb] pixels and returns the updated pixels.
     *
     * @param argb Packed `ARGB_8888` pixels of the post-`Pass B` frame, row-major.
     * @param fullResolutionShortEdge Short edge of the original photo, used to keep the sharpening
     *   radius physically identical to a full-resolution bake.
     * @return The same array when Pass C is short-circuited, otherwise a new pixel array.
     */
    fun apply(
        argb: IntArray,
        width: Int,
        height: Int,
        graph: EditGraph,
        fullResolutionShortEdge: Int = min(width, height)
    ): IntArray {
        require(argb.size == width * height) { "Pixels must match the ${width}x$height frame" }
        val params = SpatialDetailParams.from(graph)
        if (params.isZeroDrawCallShortCircuit) return argb
        val srgb = FloatArray(argb.size * CHANNELS)
        for (index in argb.indices) {
            val pixel = argb[index]
            val base = index * CHANNELS
            srgb[base] = ((pixel ushr 16) and 0xFF) / EIGHT_BIT_MAX
            srgb[base + 1] = ((pixel ushr 8) and 0xFF) / EIGHT_BIT_MAX
            srgb[base + 2] = (pixel and 0xFF) / EIGHT_BIT_MAX
        }
        val analysis = if (params.needsPrepass) buildAnalysis(srgb, width, height) else null
        val scale = min(width, height).toFloat() /
            fullResolutionShortEdge.coerceAtLeast(1).toFloat()
        PassCDetailMath.apply(
            srgb = srgb,
            width = width,
            height = height,
            params = params,
            analysis = analysis,
            resolutionScale = scale.coerceIn(MIN_RESOLUTION_SCALE, 1.0f)
        )
        return repack(srgb, argb, width, height)
    }

    private fun repack(
        srgb: FloatArray,
        source: IntArray,
        width: Int,
        height: Int
    ): IntArray {
        val out = IntArray(width * height)
        for (index in out.indices) {
            val base = index * CHANNELS
            out[index] = Srgb8Packer.packRgb(
                srgb[base],
                srgb[base + 1],
                srgb[base + 2],
                alphaBits = source[index]
            )
        }
        return out
    }

    private fun buildAnalysis(
        srgb: FloatArray,
        width: Int,
        height: Int
    ): PrepassMath.Analysis {
        val longEdge = max(width, height)
        if (longEdge <= PREPASS_MAX_EDGE) return PrepassMath.analyze(srgb, width, height)
        val shrink = PREPASS_MAX_EDGE.toFloat() / longEdge.toFloat()
        val prepassWidth = (width * shrink).roundToInt().coerceAtLeast(1)
        val prepassHeight = (height * shrink).roundToInt().coerceAtLeast(1)
        val small = ImageFilters.resampleRgb(
            srgb,
            width,
            height,
            prepassWidth,
            prepassHeight
        )
        return PrepassMath.analyze(small, prepassWidth, prepassHeight)
    }
}
