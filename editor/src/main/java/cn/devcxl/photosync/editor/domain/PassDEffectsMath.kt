package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * CPU reference implementation of the Pass D effect stack: tetrahedral 3D LUT interpolation,
 * post-crop film grain, post-crop vignette and `1/255` triangular output dithering.
 *
 * The GLSL in `EffectsOutputShaderSources` mirrors these formulas constant for constant; every
 * function here operates on display-referred `sRGB` values in `[0, 1]`.
 */
internal object PassDEffectsMath {

    /** Peak amplitude of the triangular quantization dither, one 8-bit step. */
    const val DITHER_AMPLITUDE = FilmNoise.DITHER_AMPLITUDE

    private const val RGB_COMPONENTS = 3
    private const val RGBA_COMPONENTS = 4
    private const val CHANNEL_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val EIGHT_BIT_MAX = 255.0f

    /** Peak additive grain amplitude in display units at 100% amount and a midtone pixel. */
    private const val GRAIN_MAX_AMPLITUDE = 0.06f

    private const val HALF = 0.5f
    private const val TWO = 2.0f
    private const val MIN_FEATHER = 0.0001f

    /**
     * Tetrahedrally interpolates [lut] at the normalized grid position `(red, green, blue)`.
     *
     * The base grid index is clamped to `size - 2`, so sampling `c = 1.0` reads the last valid
     * texel pair instead of running past the cube.
     *
     * @param lut Texel array, red-fastest, with [componentsPerSample] components per texel.
     * @param size Samples per axis; must be at least 2.
     * @param out Receives the interpolated RGB triple.
     */
    fun sampleTetrahedral(
        lut: FloatArray,
        size: Int,
        red: Float,
        green: Float,
        blue: Float,
        out: FloatArray,
        componentsPerSample: Int = RGB_COMPONENTS
    ) {
        if (size < 2 || lut.size < size * size * size * componentsPerSample) {
            out.fill(0.0f)
            return
        }
        val steps = (size - 1).toFloat()
        val positionR = red.coerceIn(0.0f, 1.0f) * steps
        val positionG = green.coerceIn(0.0f, 1.0f) * steps
        val positionB = blue.coerceIn(0.0f, 1.0f) * steps
        val baseR = floor(positionR).toInt().coerceIn(0, size - 2)
        val baseG = floor(positionG).toInt().coerceIn(0, size - 2)
        val baseB = floor(positionB).toInt().coerceIn(0, size - 2)
        val fR = positionR - baseR
        val fG = positionG - baseG
        val fB = positionB - baseB
        for (component in 0 until RGB_COMPONENTS) {
            out[component] = tetrahedralChannel(
                lut, size, baseR, baseG, baseB, fR, fG, fB, component, componentsPerSample
            )
        }
    }

    /** Allocating overload of [sampleTetrahedral] used by the cache pre-bake and tests. */
    fun sampleTetrahedral(
        lut: FloatArray,
        size: Int,
        red: Float,
        green: Float,
        blue: Float,
        componentsPerSample: Int = RGB_COMPONENTS
    ): FloatArray = FloatArray(RGB_COMPONENTS).also {
        sampleTetrahedral(lut, size, red, green, blue, it, componentsPerSample)
    }

    /** Midtone envelope `sqrt(4Y(1-Y))` that fades grain out of pure black and blown white. */
    fun grainEnvelope(luminance: Float): Float {
        val y = luminance.coerceIn(0.0f, 1.0f)
        return sqrt((4.0f * y * (1.0f - y)).coerceIn(0.0f, 1.0f))
    }

    /**
     * Returns the signed display-domain grain offset for post-crop `(u, v)`.
     *
     * The pattern is a two-octave value noise keyed by [PassDEffectsParams.grainSeed], so the same
     * seed reproduces the same grain across previews and tiled exports.
     */
    fun grainOffset(u: Float, v: Float, params: PassDEffectsParams, luminance: Float): Float {
        if (!params.hasGrain) return 0.0f
        val signal = FilmNoise.grainSignal(
            u = u,
            v = v,
            size = params.grainSize,
            roughness = params.grainRoughness,
            seed = params.grainSeed
        )
        return signal * params.grainAmount * GRAIN_MAX_AMPLITUDE * grainEnvelope(luminance)
    }

    /**
     * Normalized vignette radius in `[0, 1]` for post-crop `(u, v)`: `0` at the centre and `1` at
     * every corner, shaped between an ellipse, a rectangle and a normalized circle by roundness.
     */
    fun vignetteRadius(u: Float, v: Float, params: PassDEffectsParams): Float {
        val relX = (u - HALF) * TWO
        val relY = (v - HALF) * TWO
        val aspect = params.aspectRatio.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        val physX = relX * aspect
        val cornerRadius = sqrt(aspect * aspect + 1.0f)
        val ellipse = sqrt(physX * physX + relY * relY) / cornerRadius
        val rectangle = max(abs(physX), abs(relY)) / max(aspect, 1.0f)
        val circle = sqrt(relX * relX + relY * relY) / sqrt(TWO)
        val rectangularMix = (-params.vignetteRoundness).coerceIn(0.0f, 1.0f)
        val circularMix = params.vignetteRoundness.coerceIn(0.0f, 1.0f)
        val shaped = ellipse + (rectangle - ellipse) * rectangularMix
        return shaped + (circle - shaped) * circularMix
    }

    /**
     * Multiplicative vignette gain for post-crop `(u, v)`, smoothly transitioning across
     * `[midpoint - 0.5 * feather, midpoint + 0.5 * feather]`.
     */
    fun vignetteGain(u: Float, v: Float, params: PassDEffectsParams): Float {
        if (!params.hasVignette) return 1.0f
        val radius = vignetteRadius(u, v, params)
        val halfFeather = (params.vignetteFeather * HALF).coerceIn(MIN_FEATHER, HALF)
        val low = params.vignetteMidpoint - halfFeather
        val high = params.vignetteMidpoint + halfFeather
        val t = smoothStep(low, high, radius)
        return (1.0f + params.vignetteAmount * t).coerceAtLeast(0.0f)
    }

    /** Returns a triangular dither in `[-1/255, 1/255]` keyed by the pixel position and [seed]. */
    fun triangularDither(x: Float, y: Float, seed: Int): Float = FilmNoise.triangularDither(x, y, seed)

    /**
     * Applies the full Pass D stack to packed `ARGB_8888` sRGB pixels in place.
     *
     * @param lut Optional 3D LUT samples; ignored when [PassDEffectsParams.hasLutEffects] is false.
     */
    fun applyDisplayEffects(
        pixels: IntArray,
        width: Int,
        height: Int,
        params: PassDEffectsParams,
        lut: Lut3dSamples? = null
    ): IntArray {
        if (params.isIdentity || width < 1 || height < 1) return pixels
        val activeLut = lut?.takeIf {
            params.hasLutEffects && it.size >= PassDEffectsParams.MIN_LUT_SIZE
        }
        val scratch = FloatArray(RGB_COMPONENTS)
        val invWidth = 1.0f / width
        val invHeight = 1.0f / height
        for (index in pixels.indices) {
            val x = index % width
            val y = index / width
            pixels[index] = applyDisplayPixel(
                pixel = pixels[index],
                x = x,
                y = y,
                u = (x + HALF) * invWidth,
                v = (y + HALF) * invHeight,
                params = params,
                lut = activeLut,
                scratch = scratch
            )
        }
        return pixels
    }

    private fun applyDisplayPixel(
        pixel: Int,
        x: Int,
        y: Int,
        u: Float,
        v: Float,
        params: PassDEffectsParams,
        lut: Lut3dSamples?,
        scratch: FloatArray
    ): Int {
        var red = ((pixel ushr RED_SHIFT) and CHANNEL_MASK) / EIGHT_BIT_MAX
        var green = ((pixel ushr GREEN_SHIFT) and CHANNEL_MASK) / EIGHT_BIT_MAX
        var blue = (pixel and CHANNEL_MASK) / EIGHT_BIT_MAX
        if (lut != null) {
            sampleTetrahedral(lut.rgba, lut.size, red, green, blue, scratch, RGBA_COMPONENTS)
            val strength = params.lutStrength
            red += (scratch[0] - red) * strength
            green += (scratch[1] - green) * strength
            blue += (scratch[2] - blue) * strength
        }
        val luminance = ToneShaderMath.luminance(red, green, blue)
        val grain = grainOffset(u, v, params, luminance)
        val gain = vignetteGain(u, v, params)
        val dither = triangularDither(x.toFloat(), y.toFloat(), params.grainSeed)
        val offset = grain + dither
        return Srgb8Packer.packRgb(red * gain + offset, green * gain + offset, blue * gain + offset, pixel)
    }

    private fun tetrahedralChannel(
        lut: FloatArray,
        size: Int,
        baseR: Int,
        baseG: Int,
        baseB: Int,
        fR: Float,
        fG: Float,
        fB: Float,
        component: Int,
        componentsPerSample: Int
    ): Float {
        val c000 = texel(lut, size, baseR, baseG, baseB, component, componentsPerSample)
        val c100 = texel(lut, size, baseR + 1, baseG, baseB, component, componentsPerSample)
        val c010 = texel(lut, size, baseR, baseG + 1, baseB, component, componentsPerSample)
        val c001 = texel(lut, size, baseR, baseG, baseB + 1, component, componentsPerSample)
        val c110 = texel(lut, size, baseR + 1, baseG + 1, baseB, component, componentsPerSample)
        val c101 = texel(lut, size, baseR + 1, baseG, baseB + 1, component, componentsPerSample)
        val c011 = texel(lut, size, baseR, baseG + 1, baseB + 1, component, componentsPerSample)
        val c111 = texel(lut, size, baseR + 1, baseG + 1, baseB + 1, component, componentsPerSample)
        return when {
            fR > fG && fG > fB -> c000 + fR * (c100 - c000) + fG * (c110 - c100) + fB * (c111 - c110)
            fR > fB && fB >= fG -> c000 + fR * (c100 - c000) + fB * (c101 - c100) + fG * (c111 - c101)
            fB >= fR && fR > fG -> c000 + fB * (c001 - c000) + fR * (c101 - c001) + fG * (c111 - c101)
            fG >= fR && fR > fB -> c000 + fG * (c010 - c000) + fR * (c110 - c010) + fB * (c111 - c110)
            fG > fB && fB >= fR -> c000 + fG * (c010 - c000) + fB * (c011 - c010) + fR * (c111 - c011)
            else -> c000 + fB * (c001 - c000) + fG * (c011 - c001) + fR * (c111 - c011)
        }
    }

    private fun texel(
        lut: FloatArray,
        size: Int,
        r: Int,
        g: Int,
        b: Int,
        component: Int,
        componentsPerSample: Int
    ): Float = lut[((b * size + g) * size + r) * componentsPerSample + component]

    private fun smoothStep(low: Float, high: Float, value: Float): Float {
        if (high <= low) return if (value >= high) 1.0f else 0.0f
        val t = ((value - low) / (high - low)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }
}
