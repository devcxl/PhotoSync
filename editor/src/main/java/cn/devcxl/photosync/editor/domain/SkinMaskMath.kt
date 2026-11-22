package cn.devcxl.photosync.editor.domain

import kotlin.math.PI

/**
 * Dual-mode `skinMask` composition for the Portrait group.
 *
 * ## Online (6-class segmentation)
 * `skinMask = feather(clamp(P_bodySkin + P_faceSkin - P_hair, 0, 1) * (1 - holes)) * skinColorGate`
 *
 * ## Offline (built-in 250 KB person mask)
 * `skinMask = feather(P_person * (1 - holes)) * strictSkinColorGate`
 *
 * Both modes share the same protective holes ([SkinProtectionMask]) and the same OKLCH colour
 * gate; the offline gate is deliberately narrower because a binary person mask also covers hair,
 * clothes and accessories and only the hue/chroma/lightness band can separate skin there.
 */
object SkinMaskMath {

    /** Edge length of the analysis mask. */
    const val MASK_SIZE = SkinProtectionMask.MASK_SIZE

    /** Multiclass index of `body-skin`. */
    const val BODY_SKIN_CLASS = 2

    /** Multiclass index of `face-skin`. */
    const val FACE_SKIN_CLASS = 3

    /** Multiclass index of `hair`, subtracted from the skin probability. */
    const val HAIR_CLASS = 1

    /** Gaussian radius applied to the composed probability before the colour gate. */
    const val FEATHER_RADIUS_PX = 3

    private const val CHANNELS = 3
    private const val SOFT_FRACTION = 0.25f
    private const val CHROMA_SOFT_BELOW_RATIO = 0.5f
    private const val CHROMA_SOFT_ABOVE_RATIO = 1.5f

    private const val ONLINE_HUE_MIN_DEGREES = 8.0f
    private const val ONLINE_HUE_MAX_DEGREES = 96.0f
    private const val ONLINE_CHROMA_MIN = 0.008f
    private const val ONLINE_CHROMA_MAX = 0.24f
    private const val ONLINE_LUMA_MIN = 0.18f
    private const val ONLINE_LUMA_MAX = 0.97f

    private const val OFFLINE_HUE_MIN_DEGREES = 15.0f
    private const val OFFLINE_HUE_MAX_DEGREES = 75.0f
    private const val OFFLINE_CHROMA_MIN = 0.02f
    private const val OFFLINE_CHROMA_MAX = 0.18f
    private const val OFFLINE_LUMA_MIN = 0.25f
    private const val OFFLINE_LUMA_MAX = 0.92f

    /** Rasterizes the eye / brow / lip protective holes of every detected face. */
    fun holeMask(faces: List<FaceLandmarks>): FloatArray = SkinProtectionMask.holeMask(faces)

    /** `clamp(body-skin + face-skin - hair, 0, 1)` per pixel, before the hole protection. */
    fun combinedSkinProbability(
        bodySkin: FloatArray,
        faceSkin: FloatArray,
        hair: FloatArray
    ): FloatArray {
        requireSameSize(bodySkin, faceSkin)
        requireSameSize(bodySkin, hair)
        return FloatArray(bodySkin.size) { index ->
            (bodySkin[index] + faceSkin[index] - hair[index]).coerceIn(0.0f, 1.0f)
        }
    }

    /** Multiplies [probability] by `1 - holeMask`, the step that protects eyes, brows and lips. */
    fun applyProtection(probability: FloatArray, holeMask: FloatArray?): FloatArray {
        if (holeMask == null) return probability.copyOf()
        requireSameSize(probability, holeMask)
        return FloatArray(probability.size) { index ->
            probability[index].coerceIn(0.0f, 1.0f) *
                (1.0f - holeMask[index].coerceIn(0.0f, 1.0f))
        }
    }

    /**
     * Composes the online 6-class skin mask.
     *
     * @param srgb Display-domain frame colours, `3` floats per mask pixel.
     */
    fun skinMaskFromClasses(
        bodySkin: FloatArray,
        faceSkin: FloatArray,
        hair: FloatArray,
        holeMask: FloatArray?,
        srgb: FloatArray
    ): FloatArray {
        val probability = combinedSkinProbability(bodySkin, faceSkin, hair)
        val base = applyProtection(probability, holeMask)
        return gateAndFeather(base, srgb, holeMask, strict = false)
    }

    /**
     * Composes the offline person-mask skin mask.
     *
     * @param person Probability of "person" from the built-in selfie segmenter.
     * @param srgb Display-domain frame colours, `3` floats per mask pixel.
     */
    fun skinMaskFromPerson(
        person: FloatArray,
        holeMask: FloatArray?,
        srgb: FloatArray
    ): FloatArray = gateAndFeather(applyProtection(person, holeMask), srgb, holeMask, strict = true)

    /**
     * OKLCH skin-colour gate, `1` for a saturated warm midtone and `0` for neutral, blue or very
     * dark pixels.
     *
     * @param strict True for the offline band (`h 15..75`, `C 0.02..0.18`, `L 0.25..0.92`).
     */
    fun skinColorGate(red: Float, green: Float, blue: Float, strict: Boolean): Float {
        val linear = floatArrayOf(
            ExposureShaderMath.decodeSrgb(red.coerceIn(0.0f, 1.0f)),
            ExposureShaderMath.decodeSrgb(green.coerceIn(0.0f, 1.0f)),
            ExposureShaderMath.decodeSrgb(blue.coerceIn(0.0f, 1.0f))
        )
        val lab = FloatArray(CHANNELS)
        OklabColorMath.linearSrgbToOklab(linear, lab)
        val lch = FloatArray(CHANNELS)
        OklabColorMath.oklabToOklch(lab, lch)
        val hueDegrees = (lch[2] * RADIANS_TO_DEGREES)
        val hueMin = if (strict) OFFLINE_HUE_MIN_DEGREES else ONLINE_HUE_MIN_DEGREES
        val hueMax = if (strict) OFFLINE_HUE_MAX_DEGREES else ONLINE_HUE_MAX_DEGREES
        val chromaMin = if (strict) OFFLINE_CHROMA_MIN else ONLINE_CHROMA_MIN
        val chromaMax = if (strict) OFFLINE_CHROMA_MAX else ONLINE_CHROMA_MAX
        val lumaMin = if (strict) OFFLINE_LUMA_MIN else ONLINE_LUMA_MIN
        val lumaMax = if (strict) OFFLINE_LUMA_MAX else ONLINE_LUMA_MAX
        return band(hueDegrees, hueMin, hueMax) *
            chromaBand(lch[1], chromaMin, chromaMax) *
            band(lch[0], lumaMin, lumaMax)
    }

    /** Bilinearly samples a `maskSize x maskSize` mask at normalized [u], [v]. */
    fun sampleMask(mask: FloatArray, maskSize: Int, u: Float, v: Float): Float {
        if (maskSize <= 0 || mask.size < maskSize * maskSize) return 0.0f
        if (!u.isFinite() || !v.isFinite()) return 0.0f
        val x = (u * maskSize - 0.5f).coerceIn(0.0f, maskSize - 1.0f)
        val y = (v * maskSize - 0.5f).coerceIn(0.0f, maskSize - 1.0f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(maskSize - 1)
        val y1 = (y0 + 1).coerceAtMost(maskSize - 1)
        val fx = x - x0
        val fy = y - y0
        val topLeft = mask[y0 * maskSize + x0]
        val topRight = mask[y0 * maskSize + x1]
        val bottomLeft = mask[y1 * maskSize + x0]
        val bottomRight = mask[y1 * maskSize + x1]
        val top = topLeft + (topRight - topLeft) * fx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
        return (top + (bottom - top) * fy).coerceIn(0.0f, 1.0f)
    }

    private fun gateAndFeather(
        base: FloatArray,
        srgb: FloatArray,
        holeMask: FloatArray?,
        strict: Boolean
    ): FloatArray {
        require(srgb.size >= base.size * CHANNELS) {
            "the colour gate needs ${base.size * CHANNELS} samples, got ${srgb.size}"
        }
        val feathered = SeparableGaussian.blur(base, MASK_SIZE, MASK_SIZE, FEATHER_RADIUS_PX)
        return FloatArray(base.size) { index ->
            val offset = index * CHANNELS
            val gate = skinColorGate(srgb[offset], srgb[offset + 1], srgb[offset + 2], strict)
            // The feather bleeds a few percent back into a hole; re-applying the protection pins
            // the interior of every eye, brow and lip polygon to exactly zero.
            val protection = 1.0f - (holeMask?.get(index)?.coerceIn(0.0f, 1.0f) ?: 0.0f)
            (feathered[index].coerceIn(0.0f, 1.0f) * protection * gate).coerceIn(0.0f, 1.0f)
        }
    }

    /**
     * Chroma band with a proportional knee, so a neutral gray (`C = 0`) is rejected outright: the
     * rise starts at half the minimum chroma instead of a fixed fraction of the whole band, which
     * for a `0.24`-wide band would otherwise let nearly gray pixels through.
     */
    private fun chromaBand(chroma: Float, minimum: Float, maximum: Float): Float {
        if (!chroma.isFinite()) return 0.0f
        val rise = ToneShaderMath.smoothStep(minimum * CHROMA_SOFT_BELOW_RATIO, minimum, chroma)
        val fall = 1.0f - ToneShaderMath.smoothStep(maximum, maximum * CHROMA_SOFT_ABOVE_RATIO, chroma)
        return rise * fall
    }

    private fun band(value: Float, minimum: Float, maximum: Float): Float {
        if (!value.isFinite()) return 0.0f
        val span = (maximum - minimum).coerceAtLeast(1e-6f)
        val soft = span * SOFT_FRACTION
        val rise = ToneShaderMath.smoothStep(minimum - soft, minimum, value)
        val fall = 1.0f - ToneShaderMath.smoothStep(maximum, maximum + soft, value)
        return rise * fall
    }

    private fun requireSameSize(first: FloatArray, second: FloatArray) {
        require(first.size == second.size) {
            "portrait masks must agree on their size: ${first.size} vs ${second.size}"
        }
    }

    private val RADIANS_TO_DEGREES = (180.0 / PI).toFloat()
}
