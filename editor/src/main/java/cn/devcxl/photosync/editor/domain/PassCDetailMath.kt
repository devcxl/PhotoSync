package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

/**
 * CPU reference of physical Pass C (`SpatialDetailChain`), the spatial-detail stage that sits
 * between `Pass B` and the effects pass.
 *
 * The stages run in the contract order:
 * 0. **Skin smoothing** — Portrait frequency separation weighted by `skinMask`: the luminance detail
 *    is attenuated by `smooth` (keeping `texture` of it) and the mid-frequency chroma is blended
 *    towards its own local average by `evenTone`.
 * 1. **Noise reduction** — luminance `3 x 3` guided filter with a `detail` blend-back, then CbCr
 *    `5 x 5` edge-guided chroma smoothing.
 * 2. **Dehaze** — `I' = (I - A (1 - t)) / t` with `t = max(t_low, 0.1)`, or the inverse haze
 *    synthesis for a negative slider.
 * 3. **Clarity / Texture** — high-pass `clamp(Y - base, -0.08, 0.08)` against the `2.0%` and `0.3%`
 *    short-edge Pre-pass guides, weighted by a midtone bell so neither control clips the endpoints.
 * 4. **Y-channel sharpening** — edge-gated unsharp mask multiplied by `(1 - skinMask)`.
 *
 * Every stage is a pure function of the incoming display-domain samples, so `PreviewCacheWriter` and
 * the GLES chain consume the same contract.
 */
object PassCDetailMath {

    /** Clamp applied to every Clarity / Texture high-pass term, in luminance units. */
    const val HIGHPASS_LIMIT = 0.08f

    /** Clarity high-pass gain; a full `+100` slider moves the midtone luminance by up to `0.08`. */
    const val CLARITY_GAIN = 1.0f

    /** Texture high-pass gain, deliberately equal to Clarity so the two sliders stay comparable. */
    const val TEXTURE_GAIN = 1.0f

    /** Lowest transmission Pass C will divide by. */
    const val TRANSMISSION_FLOOR = PrepassMath.TRANSMISSION_FLOOR

    /** Maps the `0..100` sharpening threshold onto luminance units. */
    const val THRESHOLD_SCALE = 0.10f

    /** Softness of the threshold knee, so a detail edge fades in instead of popping. */
    const val THRESHOLD_SOFTNESS = 0.02f

    /** Luminance gradient at which edge masking reaches full strength. */
    const val MASK_GRADIENT_REFERENCE = 0.12f

    /** Ceiling of the normalised sharpening amount (`0..150` slider). */
    const val MAX_SHARPEN_AMOUNT = 1.5f

    /** Smallest working-space unsharp radius, in pixels. */
    const val MIN_WORKING_RADIUS_PX = 0.5f

    /** Largest working-space unsharp radius, in pixels. */
    const val MAX_WORKING_RADIUS_PX = 3.0f

    private const val LUMA_NR_RADIUS = 1
    private const val LUMA_NR_EPSILON = 1e-3f
    private const val CHANNELS = 3

    /**
     * Runs the whole Pass C chain in place on interleaved sRGB samples in `[0, 1]`.
     *
     * @param srgb Display-domain samples, row-major, modified in place.
     * @param params Normalised Pass C bundle from [SpatialDetailParams.from].
     * @param analysis Pre-pass result; required by Dehaze and Clarity/Texture, ignored otherwise.
     * @param skinMask Optional per-pixel skin weight in `[0, 1]`; skin smoothing is weighted by it
     *   and sharpening is scaled by `(1 - skinMask)`. A `null` mask disables skin smoothing: without
     *   the analysis the stage would have to guess which pixels are skin.
     * @param resolutionScale Working-to-full resolution ratio, used to keep the sharpening radius
     *   physically identical between the 1024px proxy, the preview cache and a full-resolution bake.
     */
    fun apply(
        srgb: FloatArray,
        width: Int,
        height: Int,
        params: SpatialDetailParams,
        analysis: PrepassMath.Analysis? = null,
        skinMask: FloatArray? = null,
        resolutionScale: Float = 1.0f
    ) {
        require(srgb.size == width * height * CHANNELS) {
            "An RGB buffer needs ${width * height * CHANNELS} samples, got ${srgb.size}"
        }
        require(width > 0 && height > 0) { "Frame size must be positive" }
        if (params.isZeroDrawCallShortCircuit) return
        if (params.hasSkinFrequencySeparation) {
            SkinSmoothingMath.apply(
                srgb = srgb,
                width = width,
                height = height,
                skinMask = skinMask,
                smooth = params.skinSmooth,
                texture = params.skinTexture,
                evenTone = params.skinEvenTone
            )
        }
        if (params.hasNoiseReduction) denoise(srgb, width, height, params)
        if (params.hasDehaze) removeHaze(srgb, width, height, params, analysis)
        if (params.hasClarityTexture) applyClarityTexture(srgb, width, height, params, analysis)
        if (params.hasSharpen) {
            SharpenMath.apply(srgb, width, height, params, skinMask, resolutionScale)
        }
    }

    /**
     * `clamp(Y - base, -HIGHPASS_LIMIT, HIGHPASS_LIMIT)`: the bounded high-pass that lets Clarity and
     * Texture add structure without ringing a hard edge into a halo.
     */
    fun highPass(luma: Float, base: Float): Float =
        (luma - base).coerceIn(-HIGHPASS_LIMIT, HIGHPASS_LIMIT)

    /** Midtone bell `4 y (1 - y)`: `1` at middle gray, `0` at both endpoints. */
    fun midtoneBell(luma: Float): Float {
        val y = luma.coerceIn(0.0f, 1.0f)
        return (4.0f * y * (1.0f - y)).coerceIn(0.0f, 1.0f)
    }

    /** Transmission floor `max(t, 0.1)`; a pitch-black region can therefore never divide by zero. */
    fun transmission(raw: Float): Float = raw.coerceAtLeast(TRANSMISSION_FLOOR)

    private fun denoise(srgb: FloatArray, width: Int, height: Int, params: SpatialDetailParams) {
        val luma = ImageFilters.luminance(srgb)
        val lumaAdjusted = if (params.nrLuma > 0.0f && params.nrDetail < 1.0f) {
            val smoothed = GuidedFilter.partialWindow(
                luma,
                width,
                height,
                LUMA_NR_RADIUS,
                LUMA_NR_EPSILON
            )
            val strength = params.nrLuma.coerceIn(0.0f, 1.0f)
            val detail = params.nrDetail.coerceIn(0.0f, 1.0f)
            FloatArray(luma.size) { index ->
                mix(luma[index], mix(smoothed[index], luma[index], detail), strength)
            }
        } else {
            luma
        }
        if (params.nrChroma <= 0.0f) {
            for (index in luma.indices) {
                val delta = lumaAdjusted[index] - luma[index]
                if (delta != 0.0f) addLumaDelta(srgb, index, delta)
            }
            return
        }
        val chroma = ChromaNoiseReducer.split(srgb)
        val smoothedChroma = ChromaNoiseReducer.smooth(chroma, luma, width, height)
        val chromaStrength = params.nrChroma.coerceIn(0.0f, 1.0f)
        val outCb = FloatArray(luma.size) {
            mix(chroma.cb[it], smoothedChroma.cb[it], chromaStrength)
        }
        val outCr = FloatArray(luma.size) {
            mix(chroma.cr[it], smoothedChroma.cr[it], chromaStrength)
        }
        ChromaNoiseReducer.merge(lumaAdjusted, outCb, outCr, srgb)
    }

    private fun removeHaze(
        srgb: FloatArray,
        width: Int,
        height: Int,
        params: SpatialDetailParams,
        analysis: PrepassMath.Analysis?
    ) {
        val guide = analysis ?: return
        val strength = params.dehaze.coerceIn(-1.0f, 1.0f)
        if (strength == 0.0f) return
        val light = guide.atmosphericLight
        val blend = abs(strength)
        for (y in 0 until height) {
            val v = (y + 0.5f) / height.toFloat()
            for (x in 0 until width) {
                val u = (x + 0.5f) / width.toFloat()
                val t = transmission(guide.transmissionAt(u, v))
                val base = (y * width + x) * CHANNELS
                for (channel in 0 until CHANNELS) {
                    val value = srgb[base + channel]
                    val target = if (strength > 0.0f) {
                        (value - light[channel] * (1.0f - t)) / t
                    } else {
                        value * t + light[channel] * (1.0f - t)
                    }
                    srgb[base + channel] = (value + (target - value) * blend).coerceIn(0.0f, 1.0f)
                }
            }
        }
    }

    private fun applyClarityTexture(
        srgb: FloatArray,
        width: Int,
        height: Int,
        params: SpatialDetailParams,
        analysis: PrepassMath.Analysis?
    ) {
        val guide = analysis ?: return
        for (y in 0 until height) {
            val v = (y + 0.5f) / height.toFloat()
            for (x in 0 until width) {
                val u = (x + 0.5f) / width.toFloat()
                val base = (y * width + x) * CHANNELS
                val luma = ToneShaderMath.luminance(srgb[base], srgb[base + 1], srgb[base + 2])
                var delta = 0.0f
                if (params.clarity != 0.0f) {
                    delta += highPass(luma, guide.clarityBaseAt(u, v)) *
                        params.clarity * CLARITY_GAIN
                }
                if (params.texture != 0.0f) {
                    delta += highPass(luma, guide.textureBaseAt(u, v)) *
                        params.texture * TEXTURE_GAIN
                }
                if (delta == 0.0f) continue
                addLumaDelta(srgb, y * width + x, delta * midtoneBell(luma))
            }
        }
    }

    private fun addLumaDelta(srgb: FloatArray, pixelIndex: Int, delta: Float) {
        val base = pixelIndex * CHANNELS
        srgb[base] = (srgb[base] + delta).coerceIn(0.0f, 1.0f)
        srgb[base + 1] = (srgb[base + 1] + delta).coerceIn(0.0f, 1.0f)
        srgb[base + 2] = (srgb[base + 2] + delta).coerceIn(0.0f, 1.0f)
    }

    private fun mix(from: Float, to: Float, weight: Float): Float =
        from * (1.0f - weight) + to * weight
}
