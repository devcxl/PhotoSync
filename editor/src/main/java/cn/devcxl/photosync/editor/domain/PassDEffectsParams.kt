package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

/**
 * Immutable snapshot of the Pass D uniforms: 3D LUT strength, film grain, post-crop vignette and
 * the output dithering amplitude.
 *
 * Grain and vignette are evaluated on the post-crop frame, so [aspectRatio] is the aspect of the
 * cropped output rather than of the source image.
 */
internal data class PassDEffectsParams(
    /** 3D LUT blend strength in `0..1`. */
    val lutStrength: Float = 0.0f,
    /** Samples per axis of the loaded LUT, or `0` when none is bound. */
    val lutSize: Int = 0,
    /** True when the graph references a LUT; the texture must also be uploaded to sample it. */
    val hasLut: Boolean = false,
    /** Film grain amount in `0..1`. */
    val grainAmount: Float = 0.0f,
    /** Grain size in `0..1` (`0` is the finest, `1` the coarsest cell). */
    val grainSize: Float = DEFAULT_GRAIN_SIZE,
    /** Grain roughness in `0..1`, blending in a half-amplitude octave. */
    val grainRoughness: Float = DEFAULT_GRAIN_ROUGHNESS,
    /** Deterministic grain seed persisted in the edit graph. */
    val grainSeed: Int = DEFAULT_GRAIN_SEED,
    /** Vignette amount in `-1..1`; negative darkens the corners, positive brightens them. */
    val vignetteAmount: Float = 0.0f,
    /** Vignette midpoint as a normalized radius in `0..1`. */
    val vignetteMidpoint: Float = DEFAULT_VIGNETTE_MIDPOINT,
    /** Width of the smooth transition band as a normalized radius in `0..1`. */
    val vignetteFeather: Float = DEFAULT_VIGNETTE_FEATHER,
    /** Vignette shape in `-1..1`; positive is rounder, negative is more rectangular. */
    val vignetteRoundness: Float = 0.0f,
    /** Width/height ratio of the post-crop frame. */
    val aspectRatio: Float = 1.0f
) {

    /** True when the 3D LUT stage must run. */
    val hasLutEffects: Boolean
        get() = hasLut && lutSize >= MIN_LUT_SIZE && lutStrength > NEUTRAL_EPSILON

    /** True when the film grain stage must run. */
    val hasGrain: Boolean
        get() = grainAmount > NEUTRAL_EPSILON

    /** True when the post-crop vignette stage must run. */
    val hasVignette: Boolean
        get() = abs(vignetteAmount) > NEUTRAL_EPSILON

    /** True when Pass D leaves every pixel untouched. */
    val isIdentity: Boolean
        get() = !hasLutEffects && !hasGrain && !hasVignette

    /** Returns a copy bound to a LUT texture with [size] samples per axis. */
    fun withLutSize(size: Int): PassDEffectsParams =
        copy(lutSize = size, hasLut = hasLut && size >= MIN_LUT_SIZE)

    companion object {
        /** Default deterministic grain seed. */
        const val DEFAULT_GRAIN_SEED = 104729

        /** Default grain size slider value as a normalized fraction. */
        const val DEFAULT_GRAIN_SIZE = 0.25f

        /** Default grain roughness slider value as a normalized fraction. */
        const val DEFAULT_GRAIN_ROUGHNESS = 0.5f

        /** Default vignette midpoint slider value as a normalized fraction. */
        const val DEFAULT_VIGNETTE_MIDPOINT = 0.5f

        /** Default vignette feather slider value as a normalized fraction. */
        const val DEFAULT_VIGNETTE_FEATHER = 0.5f

        /** Smallest 3D LUT grid the shader can interpolate. */
        const val MIN_LUT_SIZE = 2

        private const val NEUTRAL_EPSILON = 1e-6f
        private const val SLIDER_SCALE = 100.0f

        /** Builds the Pass D snapshot for [graph], evaluated on a frame of [aspectRatio]. */
        fun from(graph: EditGraph, aspectRatio: Float): PassDEffectsParams {
            val grain = graph.effects.grain
            val vignette = graph.effects.vignette
            val lut = graph.color.lut
            val sanitizedAspect = aspectRatio.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
            return PassDEffectsParams(
                lutStrength = (lut?.strength ?: 0) / SLIDER_SCALE,
                lutSize = if (lut != null && lut.strength > 0) Lut16fCache.GRID_SIZE else 0,
                hasLut = lut != null && lut.strength > 0,
                grainAmount = grain.amount / SLIDER_SCALE,
                grainSize = grain.size / SLIDER_SCALE,
                grainRoughness = grain.roughness / SLIDER_SCALE,
                grainSeed = grain.seed,
                vignetteAmount = vignette.amount / SLIDER_SCALE,
                vignetteMidpoint = vignette.midpoint / SLIDER_SCALE,
                vignetteFeather = vignette.feather / SLIDER_SCALE,
                vignetteRoundness = vignette.roundness / SLIDER_SCALE,
                aspectRatio = sanitizedAspect
            )
        }
    }
}
