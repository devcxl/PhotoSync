package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode

/**
 * The ordered stages Pass D executes once a subject composite is active.
 *
 * The composite must happen **before** the 3D LUT, grain and vignette, so those effects act on the
 * final frame instead of on a transparent cut-out or on the old background. Keeping the order as
 * data is what lets the JVM contract test lock it down without a GL context.
 */
internal enum class SubjectPassStage {
    SUBJECT_COMPOSITE,
    LUT,
    GRAIN,
    VIGNETTE
}

/**
 * How the renderer must interpret `EditGraph.subject` for one frame.
 *
 * @property mode Background mode the shader must apply.
 * @property solidColorRgb Packed `0xRRGGBB` background used by [SubjectBackgroundMode.SOLID].
 * @property transparentAlpha True when the exported pixels keep their real alpha channel.
 * @property passOrder Pass D stage order, always subject first.
 */
internal data class SubjectRenderPlan(
    val mode: SubjectBackgroundMode,
    val solidColorRgb: Int,
    val transparentAlpha: Boolean,
    val passOrder: List<SubjectPassStage>
) {

    /** Shader mode value: `1` solid, `2` transparent. */
    val shaderMode: Int
        get() = when (mode) {
            SubjectBackgroundMode.SOLID -> 1
            SubjectBackgroundMode.TRANSPARENT -> 2
            SubjectBackgroundMode.ORIGINAL -> 0
        }
}

/**
 * Pure decision table of the subject composite: whether it runs at all, which shader mode it uses
 * and whether the export must keep the alpha channel.
 *
 * A mask reference alone is not a render: an `ORIGINAL` background ignores the mask completely, and
 * a mode that needs a cut-out cannot run without a mask. Both cases return `null`, which is the
 * renderer's zero-cost short circuit.
 */
internal object SubjectRenderContract {

    /** Pass D order shared by the preview, the cached preview and the full-resolution bake. */
    val PASS_D_ORDER: List<SubjectPassStage> = listOf(
        SubjectPassStage.SUBJECT_COMPOSITE,
        SubjectPassStage.LUT,
        SubjectPassStage.GRAIN,
        SubjectPassStage.VIGNETTE
    )

    /**
     * Resolves the plan of [graph], or `null` when the subject stage must be short-circuited.
     *
     * @param hasMask True when the caller successfully loaded `subject.maskRef`.
     */
    fun plan(graph: EditGraph, hasMask: Boolean): SubjectRenderPlan? {
        val subject = graph.subject
        if (subject.bgMode == SubjectBackgroundMode.ORIGINAL) return null
        if (!hasMask || subject.maskRef.isBlank()) return null
        return SubjectRenderPlan(
            mode = subject.bgMode,
            solidColorRgb = subject.bgColorArgb and RGB_MASK,
            transparentAlpha = subject.bgMode == SubjectBackgroundMode.TRANSPARENT,
            passOrder = PASS_D_ORDER
        )
    }

    /** True when [graph] asks for an alpha-preserving PNG export. */
    fun exportsAlpha(graph: EditGraph): Boolean =
        graph.subject.bgMode == SubjectBackgroundMode.TRANSPARENT && graph.subject.maskRef.isNotBlank()

    private const val RGB_MASK = 0x00FFFFFF
}

/**
 * Uniform bundle of one subject composite draw.
 *
 * @property textureId `uSubjectTex` holding `RGB = foreground` and `A = refined alpha`.
 * @property mode Shader mode: `0` off, `1` solid, `2` transparent.
 * @property solidColorRgb Packed `0xRRGGBB` background of the solid mode.
 * @property localSpace True when [textureId] is already in the target grid (bake band); false when
 *   it is a source-space composite that must be mapped by [uvMatrix].
 * @property uvMatrix Column-major output-UV to pre-crop-UV matrix; ignored in local space.
 * @property warpTextureId `uWarpTex` to mirror the Pass A liquify/portrait displacement.
 * @property warpScale Decode scale of the warp texture.
 * @property warpOffset Decode offset of the warp texture.
 * @property checkerboard True to draw the editor's transparency checkerboard behind the subject.
 */
internal data class SubjectDrawParams(
    val textureId: Int,
    val mode: Int,
    val solidColorRgb: Int,
    val localSpace: Boolean,
    val uvMatrix: FloatArray,
    val warpTextureId: Int = 0,
    val warpScale: Float = 1.0f,
    val warpOffset: Float = 0.0f,
    val checkerboard: Boolean
) {

    init {
        require(uvMatrix.size == 9) { "The subject UV matrix must be column-major 3x3" }
    }
}
