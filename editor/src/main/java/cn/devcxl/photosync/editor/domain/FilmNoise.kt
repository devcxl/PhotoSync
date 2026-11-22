package cn.devcxl.photosync.editor.domain

import kotlin.math.floor

/**
 * Deterministic value-noise and triangular-dither primitives shared by the Pass D film grain and
 * the 8-bit output quantization.
 *
 * Both are keyed only by the pixel position and a persisted seed, so the same edit reproduces the
 * same grain on the preview and on a tiled full-resolution export. The GLSL in
 * `EffectsOutputShaderSources` mirrors these formulas.
 */
internal object FilmNoise {

    /** Peak amplitude of the triangular quantization dither, one 8-bit step. */
    const val DITHER_AMPLITUDE = 1.0f / 255.0f

    private const val FINE_FREQUENCY = 1400.0f
    private const val COARSE_FREQUENCY = 120.0f
    private const val OCTAVE_FREQUENCY_SCALE = 2.0f
    private const val OCTAVE_OFFSET_U = 37.0f
    private const val OCTAVE_OFFSET_V = 11.0f
    private const val OCTAVE_SEED_OFFSET = 7
    private const val DITHER_SEED_OFFSET = 31
    private const val HALF = 0.5f
    private const val TWO = 2.0f
    private const val HASH_SHIFT_15 = 15
    private const val HASH_SHIFT_13 = 13
    private const val HASH_SHIFT_16 = 16
    private const val HASH_SHIFT_8 = 8
    private const val HASH_MANTISSA_MASK = 0x00FF_FFFF
    private const val HASH_MANTISSA_SCALE = 16_777_216.0f

    /**
     * Returns the normalized signed grain signal in `[-1, 1]` for post-crop `(u, v)`.
     *
     * @param size Grain size in `0..1`; `0` selects the finest cell, `1` the coarsest.
     * @param roughness octave blend in `0..1` that adds a half-amplitude high-frequency layer.
     */
    fun grainSignal(u: Float, v: Float, size: Float, roughness: Float, seed: Int): Float {
        val frequency = FINE_FREQUENCY + (COARSE_FREQUENCY - FINE_FREQUENCY) * size.coerceIn(0.0f, 1.0f)
        val coarse = valueNoise(u, v, frequency, seed)
        val octave = valueNoise(
            u * OCTAVE_FREQUENCY_SCALE + OCTAVE_OFFSET_U,
            v * OCTAVE_FREQUENCY_SCALE + OCTAVE_OFFSET_V,
            frequency,
            seed + OCTAVE_SEED_OFFSET
        )
        val layered = coarse + ((coarse + HALF * octave) / 1.5f - coarse) * roughness.coerceIn(0.0f, 1.0f)
        return (layered - HALF) * TWO
    }

    /** Returns a triangular dither in `[-1/255, 1/255]` keyed by the pixel position and [seed]. */
    fun triangularDither(x: Float, y: Float, seed: Int): Float {
        val column = floor(x).toInt()
        val row = floor(y).toInt()
        val first = hash(column, row, seed)
        val second = hash(column, row, seed + DITHER_SEED_OFFSET)
        return (first - second) * DITHER_AMPLITUDE
    }

    private fun valueNoise(u: Float, v: Float, frequency: Float, seed: Int): Float {
        val x = u * frequency
        val y = v * frequency
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val wx = fx * fx * (3.0f - 2.0f * fx)
        val wy = fy * fy * (3.0f - 2.0f * fy)
        val top = lerp(hash(x0, y0, seed), hash(x0 + 1, y0, seed), wx)
        val bottom = lerp(hash(x0, y0 + 1, seed), hash(x0 + 1, y0 + 1, seed), wx)
        return lerp(top, bottom, wy)
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 0x27D4EB2D + y * 0x165667B1 + seed * 0x9E3779B1.toInt()
        h = h xor (h ushr HASH_SHIFT_15)
        h *= 0x85EBCA6B.toInt()
        h = h xor (h ushr HASH_SHIFT_13)
        h *= 0xC2B2AE35.toInt()
        h = h xor (h ushr HASH_SHIFT_16)
        return ((h ushr HASH_SHIFT_8) and HASH_MANTISSA_MASK) / HASH_MANTISSA_SCALE
    }

    private fun lerp(from: Float, to: Float, weight: Float): Float = from + (to - from) * weight
}
