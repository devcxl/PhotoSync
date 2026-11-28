package cn.devcxl.photosync.editor.domain

/**
 * Frame-aligned CPU sky composite used by the preview-cache and `LiveTetherPreset` renderers.
 *
 * The GL pipeline samples the source-space mask and the sky texture inside the Pass D shader. The CPU
 * reference renders in the post-Pass-A frame, so both the mask probability and the replacement colour
 * are pre-sampled once into arrays; [SkyCpuComposer] then applies the same blend formula as
 * [SkyBlendMath] per pixel.
 *
 * @property width Frame width in pixels.
 * @property height Frame height in pixels.
 * @property coverage Raw sky probability per pixel in row-major order.
 * @property skySrgb Replacement sky colour per pixel, packed `0xRRGGBB`.
 * @property params Spatial and feather parameters of the graph.
 */
data class SkyCpuLayer(
    val width: Int,
    val height: Int,
    val coverage: FloatArray,
    val skySrgb: IntArray,
    val params: SkyBlendParams,
    /** The single Pre-pass OKLab delta; Pass B relights the foreground with it. */
    val stats: SkyRelightStats = SkyRelightStats.NEUTRAL,
    /** Relight slider value `0..100` captured with the layer. */
    val relight: Int = 0,
    /** The OKLab chroma delta in linear RGB, precomputed for the per-pixel CPU loop. */
    val relightDeltaRgb: FloatArray = SkyRelightMath.deltaRgb(stats.deltaA, stats.deltaB)
) {

    init {
        require(width > 0 && height > 0) { "Sky CPU layer dimensions must be positive" }
        require(coverage.size == width * height) { "Sky coverage must match the frame" }
        require(skySrgb.size == width * height) { "Sky colours must match the frame" }
    }
}

/**
 * Applies a [SkyCpuLayer] to a display-referred ARGB frame, in place.
 *
 * Call sites invoke it between Pass C and the Pass D 3D LUT/grain/vignette chain, which is the same
 * seam the GL shader uses.
 */
object SkyCpuComposer {

    private const val CHANNELS = 3

    /** Composites [layer] onto [pixels]; a mismatched frame is left untouched. */
    fun apply(pixels: IntArray, width: Int, height: Int, layer: SkyCpuLayer?) {
        if (layer == null || layer.width != width || layer.height != height) return
        if (pixels.size != width * height) return
        val foreground = FloatArray(CHANNELS)
        val sky = FloatArray(CHANNELS)
        val blended = FloatArray(CHANNELS)
        for (index in pixels.indices) {
            val raw = layer.coverage[index]
            if (raw <= 0.0f) continue
            unpack(pixels[index], foreground)
            unpackPacked(layer.skySrgb[index], sky)
            SkyBlendMath.blendSrgb(
                foreground = foreground,
                sky = sky,
                coverage = SkyBlendMath.adjustedCoverage(raw, layer.params),
                rawCoverage = raw,
                out = blended
            )
            pixels[index] = pack(blended, pixels[index])
        }
    }

    private fun unpack(packed: Int, out: FloatArray) {
        unpackPacked(packed and 0xFFFFFF, out)
    }

    private fun unpackPacked(rgb: Int, out: FloatArray) {
        out[0] = ((rgb ushr 16) and 0xFF) / 255.0f
        out[1] = ((rgb ushr 8) and 0xFF) / 255.0f
        out[2] = (rgb and 0xFF) / 255.0f
    }

    private fun pack(srgb: FloatArray, alphaBits: Int): Int {
        val alpha = alphaBits and OPAQUE_ALPHA
        return alpha or
            (quantize(srgb[0]) shl 16) or
            (quantize(srgb[1]) shl 8) or
            quantize(srgb[2])
    }

    private fun quantize(value: Float): Int =
        (value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)

    private const val OPAQUE_ALPHA = -0x1000000
}
