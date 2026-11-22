package cn.devcxl.photosync.editor.ui.panels

import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.HslOps
import cn.devcxl.photosync.editor.domain.SplitToningOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Pass B1 white balance and Pass B2 display-domain color tool specs consumed by [EditorToolCatalog].
 */
internal object EditorColorSpecs {

    fun colourSpecs(): List<EditorToolSpec> =
        whiteBalanceSpecs() + chromaSpecs() + panelColorSpecs() + listOf(fadeSpec(), lutSpec())

    private fun whiteBalanceSpecs(): List<EditorToolSpec> = listOf(
        EditorToneDetailSpecs.signedSlider(
            tool = EditorTool.TEMPERATURE,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_temperature,
            modifiedLabelRes = R.string.editor_tool_temperature_modified,
            parameterKey = EditParameterKey.COLOR_TEMP,
            read = { it.color.temp },
            write = { graph, value -> graph.copy(color = graph.color.copy(temp = value)) }
        ),
        EditorToneDetailSpecs.signedSlider(
            tool = EditorTool.TINT,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_tint,
            modifiedLabelRes = R.string.editor_tool_tint_modified,
            parameterKey = EditParameterKey.COLOR_TINT,
            read = { it.color.tint },
            write = { graph, value -> graph.copy(color = graph.color.copy(tint = value)) }
        )
    )

    private fun chromaSpecs(): List<EditorToolSpec> = listOf(
        EditorToneDetailSpecs.signedSlider(
            tool = EditorTool.SATURATION,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_saturation,
            modifiedLabelRes = R.string.editor_tool_saturation_modified,
            parameterKey = EditParameterKey.COLOR_SATURATION,
            read = { it.color.saturation },
            write = { graph, value -> graph.copy(color = graph.color.copy(saturation = value)) }
        ),
        EditorToneDetailSpecs.signedSlider(
            tool = EditorTool.VIBRANCE,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_vibrance,
            modifiedLabelRes = R.string.editor_tool_vibrance_modified,
            parameterKey = EditParameterKey.COLOR_VIBRANCE,
            read = { it.color.vibrance },
            write = { graph, value -> graph.copy(color = graph.color.copy(vibrance = value)) }
        )
    )

    private fun panelColorSpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.CURVE,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_curve,
            modifiedLabelRes = R.string.editor_tool_curve_modified,
            parameterKey = EditParameterKey.COLOR_CURVE,
            controlStyle = EditorControlStyle.CURVE,
            isModified = { !it.color.curve.isIdentity() },
            reset = { it.copy(color = it.color.copy(curve = CurveOps())) }
        ),
        EditorToolSpec(
            tool = EditorTool.HSL,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_hsl,
            modifiedLabelRes = R.string.editor_tool_hsl_modified,
            parameterKey = EditParameterKey.COLOR_HSL,
            controlStyle = EditorControlStyle.HSL,
            isModified = { !it.color.hsl.isIdentity() },
            reset = { it.copy(color = it.color.copy(hsl = HslOps())) }
        ),
        EditorToolSpec(
            tool = EditorTool.SPLIT_TONING,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_split_toning,
            modifiedLabelRes = R.string.editor_tool_split_toning_modified,
            parameterKey = EditParameterKey.COLOR_SPLIT_TONING,
            controlStyle = EditorControlStyle.SPLIT_TONING,
            isModified = { !it.color.split.isIdentity() },
            reset = { it.copy(color = it.color.copy(split = SplitToningOps())) }
        )
    )

    private fun fadeSpec(): EditorToolSpec = EditorToneDetailSpecs.unsignedSlider(
        tool = EditorTool.FADE,
        group = EditorGroup.COLOR,
        labelRes = R.string.editor_tool_fade,
        modifiedLabelRes = R.string.editor_tool_fade_modified,
        parameterKey = EditParameterKey.COLOR_FADE,
        read = { it.color.fade },
        write = { graph, value -> graph.copy(color = graph.color.copy(fade = value)) }
    )

    private fun lutSpec(): EditorToolSpec = EditorToolSpec(
        tool = EditorTool.LUT,
        group = EditorGroup.COLOR,
        labelRes = R.string.editor_tool_lut,
        modifiedLabelRes = R.string.editor_tool_lut_modified,
        parameterKey = EditParameterKey.COLOR_LUT,
        controlStyle = EditorControlStyle.LUT,
        isModified = { it.color.lut != null && it.color.lut.strength > 0 },
        reset = { it.copy(color = it.color.copy(lut = null)) }
    )
}
