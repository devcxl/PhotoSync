package cn.devcxl.photosync.editor.domain

/**
 * Immutable Pass C bundle: every spatial-detail slider already normalised into shader units.
 *
 * The GL thread adopts one instance per frame, so `EditorGlRenderer` never observes a half-updated
 * slider. Build instances with [from], which owns the `clarity + 0.20 * glow` coupling so the CPU
 * preview cache and the GLSL chain cannot drift apart.
 */
data class SpatialDetailParams(
    /** Clarity in `-1..1`, already including the `0.20 * glow` coupling. */
    val clarity: Float = 0.0f,
    /** Texture in `-1..1`. */
    val texture: Float = 0.0f,
    /** Dehaze in `-1..1`; negative values add atmospheric haze. */
    val dehaze: Float = 0.0f,
    /** Y-channel unsharp mask amount in `0..1.5` (slider `0..150`). */
    val sharpenAmount: Float = 0.0f,
    /** Unsharp mask radius in full-resolution pixels, `0.5..3.0`. */
    val sharpenRadiusPx: Float = DEFAULT_SHARPEN_RADIUS_PX,
    /** Unsharp mask threshold in `0..1`; detail below it is treated as noise. */
    val sharpenThreshold: Float = 0.0f,
    /** Edge masking in `0..1`; `1` confines sharpening to strong edges. */
    val sharpenMasking: Float = 0.0f,
    /** Luminance noise reduction in `0..1`. */
    val nrLuma: Float = 0.0f,
    /** Chroma noise reduction in `0..1`. */
    val nrChroma: Float = 0.0f,
    /** Detail blend-back of the luminance NR in `0..1`; `1` restores the original luma. */
    val nrDetail: Float = DEFAULT_NR_DETAIL,
    /** True when the Portrait panel's skin smoothing occupies Pass C first. */
    val hasSkinSmoothing: Boolean = false
) {

    /** True when luminance or chroma noise reduction has work to do. */
    val hasNoiseReduction: Boolean
        get() = nrLuma > 0.0f || nrChroma > 0.0f

    /** True when the Y-channel unsharp mask must run. */
    val hasSharpen: Boolean
        get() = sharpenAmount > 0.0f

    /** True when Clarity or Texture needs the Pre-pass guide pyramid. */
    val hasClarityTexture: Boolean
        get() = clarity != 0.0f || texture != 0.0f

    /** True when the Dehaze stage must run. */
    val hasDehaze: Boolean
        get() = dehaze != 0.0f

    /** True when at least one Pre-pass quantity (`A`, `t_low`, guides) is required. */
    val needsPrepass: Boolean
        get() = hasDehaze || hasClarityTexture

    /**
     * The physical 0-draw-call contract: when every spatial slider **and** the glow coupling are
     * neutral, Pass C is skipped entirely instead of rendering a copy of its input.
     */
    val isZeroDrawCallShortCircuit: Boolean
        get() = !hasSkinSmoothing &&
            nrLuma == 0.0f &&
            nrChroma == 0.0f &&
            dehaze == 0.0f &&
            clarity == 0.0f &&
            texture == 0.0f &&
            sharpenAmount == 0.0f

    companion object {
        /** `detail.sharpen.radius` default, in full-resolution pixels. */
        const val DEFAULT_SHARPEN_RADIUS_PX = 1.0f

        /** Smallest accepted unsharp mask radius, in full-resolution pixels. */
        const val MIN_SHARPEN_RADIUS_PX = 0.5f

        /** Largest accepted unsharp mask radius, in full-resolution pixels. */
        const val MAX_SHARPEN_RADIUS_PX = 3.0f

        /** `detail.nr.detail` default, which keeps half of the removed grain. */
        const val DEFAULT_NR_DETAIL = 0.5f

        private const val SLIDER_SCALE = 100.0f
        private const val SHARPEN_AMOUNT_SCALE = 100.0f

        /**
         * Captures the `detail` group of [graph] plus the `tone.glow` clarity coupling.
         *
         * `clarity` uses the same `0.20 * glow` term as `ToneShaderMath.glowClarityBoost`, so a Glow
         * drag adds local contrast at the exact strength the tone stage assumes.
         */
        fun from(graph: EditGraph): SpatialDetailParams {
            val detail = graph.detail
            val sharpen = detail.sharpen
            return SpatialDetailParams(
                clarity = detail.clarity / SLIDER_SCALE + ToneShaderMath.glowClarityBoost(graph.tone.glow),
                texture = detail.texture / SLIDER_SCALE,
                dehaze = detail.dehaze / SLIDER_SCALE,
                sharpenAmount = sharpen.amount / SHARPEN_AMOUNT_SCALE,
                sharpenRadiusPx = sharpen.radius.coerceIn(MIN_SHARPEN_RADIUS_PX, MAX_SHARPEN_RADIUS_PX),
                sharpenThreshold = sharpen.threshold / SLIDER_SCALE,
                sharpenMasking = sharpen.masking / SLIDER_SCALE,
                nrLuma = detail.nr.luma / SLIDER_SCALE,
                nrChroma = detail.nr.chroma / SLIDER_SCALE,
                nrDetail = detail.nr.detail / SLIDER_SCALE,
                hasSkinSmoothing = graph.portrait.skin.smooth != 0
            )
        }
    }
}
