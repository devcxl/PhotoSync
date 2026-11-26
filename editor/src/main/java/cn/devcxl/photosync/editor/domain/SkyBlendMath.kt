package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * Pass D sky-composite parameters of one frame.
 *
 * @property procedural The analytic preset, or `null` when the graph uses a custom/pack texture.
 * @property horizonOffset Vertical sky shift in `-0.30..+0.30`; positive moves the horizon down.
 * @property horizonFeather Atmospheric transition width `0..100`.
 * @property edgeShift Mask micro-shrink in `-2..+2 px`; positive grows the foreground.
 */
data class SkyBlendParams(
    val procedural: ProceduralSkyPreset? = null,
    val horizonOffset: Float = 0.0f,
    val horizonFeather: Int = 0,
    val edgeShift: Int = 0
) {

    /** Feather as a normalized `0..1` band half-width. */
    val featherFraction: Float
        get() = horizonFeather.coerceIn(0, 100) / 100.0f

    /** True when the graph carries a sky that Pass D must composite. */
    val isActive: Boolean
        get() = procedural != null

    companion object {

        /**
         * Builds the Pass D parameters of [graph], or `null` when the graph is a no-op.
         *
         * A `CUSTOM`/`PACK` graph has no procedural preset but is still active; [procedural] stays
         * `null` and the shader samples the bound asset texture instead.
         */
        fun from(graph: EditGraph): SkyBlendParams? {
            val sky = graph.sky
            if (sky.isIdentity() || sky.assetType == SkyAssetType.NONE) return null
            val procedural = if (sky.assetType == SkyAssetType.PROCEDURAL) {
                ProceduralSkyPreset.fromWireId(sky.assetIdOrRelPath) ?: ProceduralSkyPreset.DEFAULT
            } else {
                null
            }
            return SkyBlendParams(
                procedural = procedural,
                horizonOffset = sky.horizonOffset,
                horizonFeather = sky.horizonFeather,
                edgeShift = sky.edgeShift
            )
        }

        /** A custom/pack graph has no procedural id but still needs the Pass D branch. */
        fun hasTextureAsset(graph: EditGraph): Boolean =
            graph.sky.assetType == SkyAssetType.CUSTOM || graph.sky.assetType == SkyAssetType.PACK
    }
}

/**
 * CPU reference of the Pass D sky composite, shared with `EffectsOutputShaderSources`.
 *
 * The composite runs **before** the 3D LUT, grain and vignette so the whole final frame — new sky and
 * original foreground alike — receives the same grade. Three rules keep the seam invisible:
 * 1. `horizonFeather` widens the mask transition, giving the new sky a hazy atmospheric edge.
 * 2. `edgeShift` samples the mask at a slightly scaled radius, eroding the sky by up to two pixels so
 *    a bright pixel row of the original sky cannot remain as a rim.
 * 3. In the `0.05 < coverage < 0.95` band a small fraction of the neighbouring new-sky colour is
 *    mixed into the foreground, suppressing cyan/white colour spill on branches and roof edges.
 */
object SkyBlendMath {

    /** Lower edge of the colour-spill transition band. */
    const val SPILL_LOW = 0.05f

    /** Upper edge of the colour-spill transition band. */
    const val SPILL_HIGH = 0.95f

    private const val CHANNELS = 3
    private const val SHARP_BAND = 0.05f
    private const val FEATHER_BAND = 0.45f
    private const val SPILL_STRENGTH = 0.20f
    private const val COVERAGE_EPSILON = 1e-4f

    /**
     * Applies the `edgeShift` radial sampling offset to a normalized coordinate.
     *
     * @param edgeShift Signed pixels; positive scales the sample radius outwards, shrinking the sky.
     */
    fun shiftedCoordinate(
        u: Float,
        v: Float,
        edgeShift: Int,
        width: Int,
        height: Int
    ): Pair<Float, Float> {
        val shortEdge = min(width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (edgeShift == 0 || shortEdge <= 0) return u to v
        val scale = 1.0f + (edgeShift * 2.0f / shortEdge)
        val safeScale = scale.coerceIn(0.5f, 1.5f)
        return (0.5f + (u - 0.5f) * safeScale) to (0.5f + (v - 0.5f) * safeScale)
    }

    /** The feathered sky weight of one raw mask value. */
    fun adjustedCoverage(raw: Float, params: SkyBlendParams): Float {
        val band = SHARP_BAND + (FEATHER_BAND - SHARP_BAND) * params.featherFraction
        return ToneShaderMath.smoothStep(0.5f - band, 0.5f + band, raw.coerceIn(0.0f, 1.0f))
    }

    /** True when [raw] is inside the edge transition band where spill suppression applies. */
    fun isSpillBand(raw: Float): Boolean = raw > SPILL_LOW && raw < SPILL_HIGH

    /**
     * Composites one display-referred sRGB pixel.
     *
     * @param coverage Feathered sky weight in `0..1`.
     * @param rawCoverage Unfeathered probability, which selects the spill band.
     */
    fun blendSrgb(
        foreground: FloatArray,
        sky: FloatArray,
        coverage: Float,
        rawCoverage: Float,
        out: FloatArray
    ) {
        val weight = coverage.coerceIn(0.0f, 1.0f)
        for (channel in 0 until CHANNELS) {
            out[channel] = foreground[channel] + (sky[channel] - foreground[channel]) * weight
        }
        if (!isSpillBand(rawCoverage)) return
        val edge = ToneShaderMath.smoothStep(SPILL_LOW, 0.5f, rawCoverage) *
            (1.0f - ToneShaderMath.smoothStep(0.5f, SPILL_HIGH, rawCoverage))
        val spill = edge * SPILL_STRENGTH * (1.0f - weight)
        if (spill <= 0.0f) return
        for (channel in 0 until CHANNELS) {
            out[channel] += (sky[channel] - out[channel]) * spill
        }
    }

    /**
     * Composites a procedural sky into a display-referred ARGB frame **in place**.
     *
     * @param coverage Frame-aligned raw mask probability, one value per pixel.
     */
    fun applyProceduralToArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        coverage: FloatArray,
        params: SkyBlendParams
    ) {
        val sky = params.procedural ?: return
        if (coverage.size != pixels.size) return
        val foreground = FloatArray(CHANNELS)
        val skyColor = FloatArray(CHANNELS)
        val blended = FloatArray(CHANNELS)
        for (index in pixels.indices) {
            val raw = coverage[index]
            if (raw <= COVERAGE_EPSILON) continue
            val row = index / width
            val column = index % width
            val u = (column + 0.5f) / width
            val v = (row + 0.5f) / height
            ProceduralSky.sampleSrgb(sky, u, v, params.horizonOffset, skyColor)
            unpack(pixels[index], foreground)
            blendSrgb(foreground, skyColor, adjustedCoverage(raw, params), raw, blended)
            pixels[index] = pack(blended, pixels[index])
        }
    }

    /** True when [params] carries any non-neutral spatial adjustment. */
    fun hasSpatialAdjustment(params: SkyBlendParams): Boolean =
        abs(params.horizonOffset) > 1e-4f || params.horizonFeather != 0 || params.edgeShift != 0

    private fun unpack(packed: Int, out: FloatArray) {
        out[0] = ((packed ushr 16) and 0xFF) / 255.0f
        out[1] = ((packed ushr 8) and 0xFF) / 255.0f
        out[2] = (packed and 0xFF) / 255.0f
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
