package cn.devcxl.photosync.editor.domain

/**
 * One physical sub-pass of `SpatialDetailChain`.
 */
enum class SpatialDetailStage {
    /** Portrait skin frequency separation (smooth / texture / even tone) weighted by `skinMask`. */
    SKIN_SMOOTHING,

    /** Luminance guided NR + CbCr edge-guided chroma NR. */
    NOISE_REDUCTION,

    /** Dark-channel dehaze against the Pre-pass atmospheric light and coarse transmission. */
    DEHAZE,

    /** Clarity (`clarity + 0.20 * glow`) and Texture high-pass against the Pre-pass guides. */
    CLARITY_TEXTURE,

    /** Y-channel edge-gated unsharp mask with the `(1 - skinMask)` suppression slot. */
    SHARPEN,

    /**
     * Terminal simulated background bokeh: a background-weighted disc blur mixed back by
     * `1 - subjectAlpha`, so the subject (and its hair) keeps the sharp Pass C output.
     */
    SUBJECT_BOKEH
}

/**
 * The resolved Pass C draw plan: which sub-passes will actually rasterize this frame.
 *
 * Exposing the plan separately from the renderer is what makes the "0 draw call" contract testable
 * without a GLES context, and lets the renderer snapshot the same decision it then executes.
 */
class SpatialDetailPlan(
    /** Sub-passes that will run, in chain order. */
    val stages: List<SpatialDetailStage>,
    /** True when the plan needs the 1024px Pre-pass output (`A`, `t_low`, guide pyramid). */
    val prepassRequired: Boolean,
    /** Whether a Pre-pass result was available when the plan was resolved. */
    val prepassAvailable: Boolean,
    /** Parameterised stages that were dropped because their inputs were not ready yet. */
    val skippedStages: List<SpatialDetailStage>
) {

    /** Number of FBO draw calls Pass C will issue for this frame. */
    val drawCalls: Int
        get() = stages.size

    /** True when Pass C must not touch the frame buffer at all. */
    val isZeroDrawCallShortCircuit: Boolean
        get() = stages.isEmpty()
}

/**
 * Resolves the physical Pass C draw plan from a normalized parameter bundle.
 *
 * Dehaze and Clarity/Texture are global operations: without the Pre-pass guide they would have to
 * invent a base layer, which is exactly the per-tile seam the Pre-pass exists to prevent. Skin
 * smoothing likewise needs the analysis `skinMask`, because smoothing the whole frame on a guess
 * would blur hair and background. Missing inputs therefore drop their stage (and report it in
 * [SpatialDetailPlan.skippedStages]) instead of being faked.
 */
object SpatialDetailPlanner {

    /**
     * @param prepassAvailable True once the 1024px Pre-pass texture for the current graph exists.
     * @param skinMaskAvailable True when the portrait analysis produced a `skinMask` for this photo.
     * @param subjectMaskAvailable True when a refined subject alpha is bound for the bokeh stage.
     */
    fun plan(
        params: SpatialDetailParams,
        prepassAvailable: Boolean,
        skinMaskAvailable: Boolean = true,
        subjectMaskAvailable: Boolean = false
    ): SpatialDetailPlan {
        val stages = mutableListOf<SpatialDetailStage>()
        if (params.hasSkinFrequencySeparation && skinMaskAvailable) {
            stages += SpatialDetailStage.SKIN_SMOOTHING
        }
        if (params.hasNoiseReduction) stages += SpatialDetailStage.NOISE_REDUCTION
        if (params.hasDehaze && prepassAvailable) stages += SpatialDetailStage.DEHAZE
        if (params.hasClarityTexture && prepassAvailable) {
            stages += SpatialDetailStage.CLARITY_TEXTURE
        }
        if (params.hasSharpen) stages += SpatialDetailStage.SHARPEN
        if (params.hasSubjectBokeh && subjectMaskAvailable) stages += SpatialDetailStage.SUBJECT_BOKEH
        val skipped = mutableListOf<SpatialDetailStage>()
        if (params.hasSkinFrequencySeparation && !skinMaskAvailable) {
            skipped += SpatialDetailStage.SKIN_SMOOTHING
        }
        if (params.hasDehaze && !prepassAvailable) skipped += SpatialDetailStage.DEHAZE
        if (params.hasClarityTexture && !prepassAvailable) {
            skipped += SpatialDetailStage.CLARITY_TEXTURE
        }
        if (params.hasSubjectBokeh && !subjectMaskAvailable) {
            skipped += SpatialDetailStage.SUBJECT_BOKEH
        }
        return SpatialDetailPlan(
            stages = stages,
            prepassRequired = params.needsPrepass,
            prepassAvailable = prepassAvailable,
            skippedStages = skipped
        )
    }
}
