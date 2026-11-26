package cn.devcxl.photosync.editor.domain

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The four zero-byte procedural skies available offline in the `AI -> 天空` tool.
 *
 * Each preset is a tiny analytic Rayleigh/Mie approximation, not a bitmap: it costs no APK space and
 * carries no licensing obligation. [wireId] is what `SkyOps.assetIdOrRelPath` stores for
 * [SkyAssetType.PROCEDURAL], so the name is part of the persisted schema.
 */
enum class ProceduralSkyPreset(val wireId: String) {
    CLEAR_BLUE("clear_blue"),
    SUNSET_GLOW("sunset_glow"),
    BLUE_HOUR("blue_hour"),
    WARM_DUSK("warm_dusk");

    companion object {

        /** First preset of the panel; also the fallback for an unknown persisted id. */
        val DEFAULT: ProceduralSkyPreset = CLEAR_BLUE

        /** Resolves a persisted wire id, or `null` when it names no known preset. */
        fun fromWireId(raw: String?): ProceduralSkyPreset? =
            entries.firstOrNull { it.wireId.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * Analytic atmospheric gradient shared by the Pass D shader and the CPU reference.
 *
 * The model is deliberately simple and deterministic: a two-stop Rayleigh gradient (horizon to
 * zenith), a Mie forward-scattering halo around the sun and a haze lift near the horizon. The GLSL in
 * `SkyShaderSources` evaluates exactly these formulas, so a preview and a full-resolution bake agree
 * without any texture download.
 */
object ProceduralSky {

    private const val CHANNELS = 3
    private const val GRADIENT_POWER = 0.80f
    private const val SUN_RADIUS = 0.16f
    private const val HAZE_POWER = 3.0f
    private const val HAZE_GAIN = 0.18f
    private const val EIGHT_BIT = 255.0f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    /**
     * One preset's colours and sun placement.
     *
     * Colours are display-referred sRGB so the shader can evaluate the gradient in the same encoding
     * Pass D works in; [sunX]/[sunY] are normalized frame coordinates.
     */
    data class Palette(
        val zenithArgb: Int,
        val horizonArgb: Int,
        val sunArgb: Int,
        val sunX: Float,
        val sunY: Float,
        val sunIntensity: Float,
        val haze: Float
    )

    /** The palette of [sky]. */
    fun palette(sky: ProceduralSkyPreset): Palette = when (sky) {
        ProceduralSkyPreset.CLEAR_BLUE -> Palette(
            zenithArgb = 0xFF2E6FD0.toInt(),
            horizonArgb = 0xFFC3DCF4.toInt(),
            sunArgb = 0xFFFFF6D8.toInt(),
            sunX = 0.78f,
            sunY = 0.16f,
            sunIntensity = 0.12f,
            haze = 0.06f
        )
        ProceduralSkyPreset.SUNSET_GLOW -> Palette(
            zenithArgb = 0xFF27406F.toInt(),
            horizonArgb = 0xFFFF9A5A.toInt(),
            sunArgb = 0xFFFFD9A0.toInt(),
            sunX = 0.24f,
            sunY = 0.50f,
            sunIntensity = 0.55f,
            haze = 0.14f
        )
        ProceduralSkyPreset.BLUE_HOUR -> Palette(
            zenithArgb = 0xFF12233F.toInt(),
            horizonArgb = 0xFF54779E.toInt(),
            sunArgb = 0xFFC9D8F0.toInt(),
            sunX = 0.50f,
            sunY = 0.46f,
            sunIntensity = 0.08f,
            haze = 0.05f
        )
        ProceduralSkyPreset.WARM_DUSK -> Palette(
            zenithArgb = 0xFF3A3550.toInt(),
            horizonArgb = 0xFFE8A87C.toInt(),
            sunArgb = 0xFFFFE0B8.toInt(),
            sunX = 0.70f,
            sunY = 0.38f,
            sunIntensity = 0.34f,
            haze = 0.10f
        )
    }

    /**
     * Samples the sRGB gradient at normalized [u], [v].
     *
     * @param horizonOffset Shifts the sky's horizon line in frame units; positive moves it down.
     * @param out Receives three display-referred `0..1` channels.
     */
    fun sampleSrgb(
        sky: ProceduralSkyPreset,
        u: Float,
        v: Float,
        horizonOffset: Float = 0.0f,
        out: FloatArray = FloatArray(CHANNELS)
    ) {
        val palette = palette(sky)
        val worldV = (v - horizonOffset).coerceIn(0.0f, 1.0f)
        val up = 1.0f - worldV
        val gradient = up.pow(GRADIENT_POWER)
        val zenithR = red(palette.zenithArgb)
        val zenithG = green(palette.zenithArgb)
        val zenithB = blue(palette.zenithArgb)
        val horizonR = red(palette.horizonArgb)
        val horizonG = green(palette.horizonArgb)
        val horizonB = blue(palette.horizonArgb)
        val dx = u - palette.sunX
        val dy = worldV - palette.sunY
        val distance = sqrt(dx * dx + dy * dy)
        val glow = exp(-(distance / SUN_RADIUS) * (distance / SUN_RADIUS))
        val haze = (1.0f - up).pow(HAZE_POWER) * palette.haze
        val sunR = red(palette.sunArgb)
        val sunG = green(palette.sunArgb)
        val sunB = blue(palette.sunArgb)
        out[0] = (mix(horizonR, zenithR, gradient) + sunR * (glow * palette.sunIntensity + haze * HAZE_GAIN))
            .coerceIn(0.0f, 1.0f)
        out[1] = (mix(horizonG, zenithG, gradient) + sunG * (glow * palette.sunIntensity + haze * HAZE_GAIN))
            .coerceIn(0.0f, 1.0f)
        out[2] = (mix(horizonB, zenithB, gradient) + sunB * (glow * palette.sunIntensity + haze * HAZE_GAIN))
            .coerceIn(0.0f, 1.0f)
    }

    /** Samples the gradient in scene-linear light, the encoding Pass B statistics use. */
    fun sampleLinear(
        sky: ProceduralSkyPreset,
        u: Float,
        v: Float,
        horizonOffset: Float = 0.0f,
        out: FloatArray = FloatArray(CHANNELS)
    ) {
        sampleSrgb(sky, u, v, horizonOffset, out)
        out[0] = ExposureShaderMath.decodeSrgb(out[0])
        out[1] = ExposureShaderMath.decodeSrgb(out[1])
        out[2] = ExposureShaderMath.decodeSrgb(out[2])
    }

    /**
     * Mean sRGB colour of [sky] over a regular grid.
     *
     * The relight statistics need one representative new-sky colour; averaging a grid is both stable
     * against a single hot sun pixel and independent of the frame's mask, so every column band of a
     * bake shares the same result.
     */
    fun meanSrgb(sky: ProceduralSkyPreset): FloatArray {
        val out = FloatArray(CHANNELS)
        val sample = FloatArray(CHANNELS)
        var count = 0
        for (row in 0 until MEAN_GRID) {
            for (column in 0 until MEAN_GRID) {
                sampleSrgb(
                    sky = sky,
                    u = (column + 0.5f) / MEAN_GRID,
                    v = (row + 0.5f) / MEAN_GRID,
                    out = sample
                )
                out[0] += sample[0]
                out[1] += sample[1]
                out[2] += sample[2]
                count++
            }
        }
        out[0] /= count
        out[1] /= count
        out[2] /= count
        return out
    }

    /** Mean scene-linear colour of [sky]; feeds the Pre-pass OKLab delta. */
    fun meanLinear(sky: ProceduralSkyPreset): FloatArray {
        val srgb = meanSrgb(sky)
        return floatArrayOf(
            ExposureShaderMath.decodeSrgb(srgb[0]),
            ExposureShaderMath.decodeSrgb(srgb[1]),
            ExposureShaderMath.decodeSrgb(srgb[2])
        )
    }

    /** True when [graph] selects a procedural sky and is not a rendering no-op. */
    fun isActive(graph: EditGraph): Boolean =
        graph.sky.assetType == SkyAssetType.PROCEDURAL && !graph.sky.isIdentity()

    /** The procedural preset selected by [graph], or `null` when the graph uses another asset type. */
    fun of(graph: EditGraph): ProceduralSkyPreset? =
        if (graph.sky.assetType == SkyAssetType.PROCEDURAL) {
            ProceduralSkyPreset.fromWireId(graph.sky.assetIdOrRelPath) ?: ProceduralSkyPreset.DEFAULT
        } else {
            null
        }

    private fun mix(from: Float, to: Float, weight: Float): Float = from + (to - from) * weight

    private fun red(argb: Int): Float = ((argb ushr RED_SHIFT) and 0xFF) / EIGHT_BIT

    private fun green(argb: Int): Float = ((argb ushr GREEN_SHIFT) and 0xFF) / EIGHT_BIT

    private fun blue(argb: Int): Float = (argb and 0xFF) / EIGHT_BIT

    private const val MEAN_GRID = 8
}
