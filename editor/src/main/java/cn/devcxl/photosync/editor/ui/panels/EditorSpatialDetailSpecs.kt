package cn.devcxl.photosync.editor.ui.panels

import kotlin.math.abs

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.NoiseReductionOps
import cn.devcxl.photosync.editor.domain.SharpenOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Physical Pass C tool definitions: Clarity, Texture, Dehaze, Sharpening and Noise Reduction.
 *
 * The three sliders keep their own undo bucket and dial range `-100..100`; Sharpening and Noise
 * Reduction own multi-row panels, so their specs carry no dial and delegate the values to
 * [SharpenOps] / [NoiseReductionOps].
 */
internal object EditorSpatialDetailSpecs {

    private const val DEFAULT_SHARPEN_RADIUS = 1.0f
    private const val DEFAULT_NR_DETAIL = 50
    private const val RADIUS_TOLERANCE = 1e-4f

    /** Returns the Pass C spatial-detail tool specs in drawer chip order. */
    fun spatialDetailSpecs(): List<EditorToolSpec> = listOf(
        claritySpec(),
        textureSpec(),
        dehazeSpec(),
        sharpenSpec(),
        noiseReductionSpec()
    )

    private fun claritySpec(): EditorToolSpec = EditorToneDetailSpecs.signedSlider(
        tool = EditorTool.CLARITY,
        group = EditorGroup.DETAIL,
        labelRes = R.string.editor_tool_clarity,
        modifiedLabelRes = R.string.editor_tool_clarity_modified,
        parameterKey = EditParameterKey.DETAIL_CLARITY,
        read = { it.detail.clarity },
        write = { graph, value -> graph.copy(detail = graph.detail.copy(clarity = value)) }
    )

    private fun textureSpec(): EditorToolSpec = EditorToneDetailSpecs.signedSlider(
        tool = EditorTool.TEXTURE,
        group = EditorGroup.DETAIL,
        labelRes = R.string.editor_tool_texture,
        modifiedLabelRes = R.string.editor_tool_texture_modified,
        parameterKey = EditParameterKey.DETAIL_TEXTURE,
        read = { it.detail.texture },
        write = { graph, value -> graph.copy(detail = graph.detail.copy(texture = value)) }
    )

    private fun dehazeSpec(): EditorToolSpec = EditorToneDetailSpecs.signedSlider(
        tool = EditorTool.DEHAZE,
        group = EditorGroup.DETAIL,
        labelRes = R.string.editor_tool_dehaze,
        modifiedLabelRes = R.string.editor_tool_dehaze_modified,
        parameterKey = EditParameterKey.DETAIL_DEHAZE,
        read = { it.detail.dehaze },
        write = { graph, value -> graph.copy(detail = graph.detail.copy(dehaze = value)) }
    )

    private fun sharpenSpec(): EditorToolSpec = EditorToolSpec(
        tool = EditorTool.SHARPEN,
        group = EditorGroup.DETAIL,
        labelRes = R.string.editor_tool_sharpen,
        modifiedLabelRes = R.string.editor_tool_sharpen_modified,
        parameterKey = EditParameterKey.DETAIL_SHARPEN,
        controlStyle = EditorControlStyle.SHARPEN,
        isModified = { graph -> isSharpenModified(graph.detail.sharpen) },
        reset = { graph -> graph.copy(detail = graph.detail.copy(sharpen = SharpenOps())) }
    )

    private fun noiseReductionSpec(): EditorToolSpec = EditorToolSpec(
        tool = EditorTool.NOISE_REDUCTION,
        group = EditorGroup.DETAIL,
        labelRes = R.string.editor_tool_noise_reduction,
        modifiedLabelRes = R.string.editor_tool_noise_reduction_modified,
        parameterKey = EditParameterKey.DETAIL_NOISE_REDUCTION,
        controlStyle = EditorControlStyle.NOISE_REDUCTION,
        isModified = { graph -> isNoiseReductionModified(graph.detail.nr) },
        reset = { graph -> graph.copy(detail = graph.detail.copy(nr = NoiseReductionOps())) }
    )

    private fun isSharpenModified(sharpen: SharpenOps): Boolean =
        sharpen.amount != 0 ||
            sharpen.threshold != 0 ||
            sharpen.masking != 0 ||
            abs(sharpen.radius - DEFAULT_SHARPEN_RADIUS) > RADIUS_TOLERANCE

    private fun isNoiseReductionModified(nr: NoiseReductionOps): Boolean =
        !nr.isIdentity() || nr.detail != DEFAULT_NR_DETAIL
}
