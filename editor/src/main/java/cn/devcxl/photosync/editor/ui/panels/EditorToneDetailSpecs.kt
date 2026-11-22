package cn.devcxl.photosync.editor.ui.panels

import kotlin.math.abs
import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Tone, white balance and lateral CA tool definitions consumed by [EditorToolCatalog].
 */
internal object EditorToneDetailSpecs {

    private const val SLIDER_MIN = -100.0f
    private const val SLIDER_MAX = 100.0f
    private const val SLIDER_STEP = 1.0f
    private const val SLIDER_ZERO_SNAP = 2.0f

    private val SIGNED_DIAL = DialSpec(
        min = SLIDER_MIN,
        max = SLIDER_MAX,
        step = SLIDER_STEP,
        zeroSnapThreshold = SLIDER_ZERO_SNAP,
        valueFormatRes = R.string.editor_value_signed_fmt
    )

    fun toneSpecs(): List<EditorToolSpec> =
        listOf(exposureSpec()) + basicToneSpecs() + tonalRangeSpecs() + listOf(glowSpec())

    fun colourSpecs(): List<EditorToolSpec> = listOf(
        signedSlider(
            tool = EditorTool.TEMPERATURE,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_temperature,
            modifiedLabelRes = R.string.editor_tool_temperature_modified,
            parameterKey = EditParameterKey.COLOR_TEMP,
            read = { it.color.temp },
            write = { graph, value -> graph.copy(color = graph.color.copy(temp = value)) }
        ),
        signedSlider(
            tool = EditorTool.TINT,
            group = EditorGroup.COLOR,
            labelRes = R.string.editor_tool_tint,
            modifiedLabelRes = R.string.editor_tool_tint_modified,
            parameterKey = EditParameterKey.COLOR_TINT,
            read = { it.color.tint },
            write = { graph, value -> graph.copy(color = graph.color.copy(tint = value)) }
        )
    )

    fun detailSpecs(): List<EditorToolSpec> = listOf(
        signedSlider(
            tool = EditorTool.DISPERSION_RED_CYAN,
            group = EditorGroup.DETAIL,
            labelRes = R.string.editor_tool_ca_red_cyan,
            modifiedLabelRes = R.string.editor_tool_ca_red_cyan_modified,
            parameterKey = EditParameterKey.DETAIL_CA_RED_CYAN,
            read = { it.effects.ca.redCyan },
            write = { graph, value ->
                graph.copy(effects = graph.effects.copy(ca = graph.effects.ca.copy(redCyan = value)))
            }
        ),
        signedSlider(
            tool = EditorTool.DISPERSION_BLUE_YELLOW,
            group = EditorGroup.DETAIL,
            labelRes = R.string.editor_tool_ca_blue_yellow,
            modifiedLabelRes = R.string.editor_tool_ca_blue_yellow_modified,
            parameterKey = EditParameterKey.DETAIL_CA_BLUE_YELLOW,
            read = { it.effects.ca.blueYellow },
            write = { graph, value ->
                graph.copy(effects = graph.effects.copy(ca = graph.effects.ca.copy(blueYellow = value)))
            }
        )
    )

    private fun exposureSpec(): EditorToolSpec = EditorToolSpec(
        tool = EditorTool.EXPOSURE,
        group = EditorGroup.TONE,
        labelRes = R.string.editor_tool_exposure,
        modifiedLabelRes = R.string.editor_tool_exposure_modified,
        parameterKey = EditParameterKey.TONE_EXPOSURE,
        controlStyle = EditorControlStyle.DIAL,
        dial = DialSpec(
            min = -5.0f,
            max = 5.0f,
            step = 0.05f,
            zeroSnapThreshold = 0.10f,
            valueFormatRes = R.string.editor_exposure_value_fmt
        ),
        readValue = { it.tone.exposure },
        writeValue = { graph, value -> graph.copy(tone = graph.tone.copy(exposure = value)) },
        isModified = { abs(it.tone.exposure) >= 1e-4f },
        reset = { it.copy(tone = it.tone.copy(exposure = 0.0f)) }
    )

    private fun basicToneSpecs(): List<EditorToolSpec> = listOf(
        signedSlider(
            group = EditorGroup.TONE,
            tool = EditorTool.BRIGHTNESS,
            labelRes = R.string.editor_tool_brightness,
            modifiedLabelRes = R.string.editor_tool_brightness_modified,
            parameterKey = EditParameterKey.TONE_BRIGHTNESS,
            read = { it.tone.brightness },
            write = { graph, value -> graph.copy(tone = graph.tone.copy(brightness = value)) }
        ),
        signedSlider(
            group = EditorGroup.TONE,
            tool = EditorTool.CONTRAST,
            labelRes = R.string.editor_tool_contrast,
            modifiedLabelRes = R.string.editor_tool_contrast_modified,
            parameterKey = EditParameterKey.TONE_CONTRAST,
            read = { it.tone.contrast },
            write = { graph, value -> graph.copy(tone = graph.tone.copy(contrast = value)) }
        )
    )

    private fun tonalRangeSpecs(): List<EditorToolSpec> = listOf(
        signedSlider(
            group = EditorGroup.TONE,
            tool = EditorTool.HIGHLIGHTS,
            labelRes = R.string.editor_tool_highlights,
            modifiedLabelRes = R.string.editor_tool_highlights_modified,
            parameterKey = EditParameterKey.TONE_HIGHLIGHTS,
            read = { it.tone.highlights },
            write = { graph, value -> graph.copy(tone = graph.tone.copy(highlights = value)) }
        ),
        signedSlider(
            group = EditorGroup.TONE,
            tool = EditorTool.SHADOWS,
            labelRes = R.string.editor_tool_shadows,
            modifiedLabelRes = R.string.editor_tool_shadows_modified,
            parameterKey = EditParameterKey.TONE_SHADOWS,
            read = { it.tone.shadows },
            write = { graph, value -> graph.copy(tone = graph.tone.copy(shadows = value)) }
        ),
        signedSlider(
            group = EditorGroup.TONE,
            tool = EditorTool.WHITES,
            labelRes = R.string.editor_tool_whites,
            modifiedLabelRes = R.string.editor_tool_whites_modified,
            parameterKey = EditParameterKey.TONE_WHITES,
            read = { it.tone.whites },
            write = { graph, value -> graph.copy(tone = graph.tone.copy(whites = value)) }
        ),
        signedSlider(
            group = EditorGroup.TONE,
            tool = EditorTool.BLACKS,
            labelRes = R.string.editor_tool_blacks,
            modifiedLabelRes = R.string.editor_tool_blacks_modified,
            parameterKey = EditParameterKey.TONE_BLACKS,
            read = { it.tone.blacks },
            write = { graph, value -> graph.copy(tone = graph.tone.copy(blacks = value)) }
        )
    )

    private fun glowSpec(): EditorToolSpec = signedSlider(
        group = EditorGroup.TONE,
        tool = EditorTool.GLOW,
        labelRes = R.string.editor_tool_glow,
        modifiedLabelRes = R.string.editor_tool_glow_modified,
        parameterKey = EditParameterKey.TONE_GLOW,
        read = { it.tone.glow },
        write = { graph, value -> graph.copy(tone = graph.tone.copy(glow = value)) }
    )

    private fun signedSlider(
        tool: EditorTool,
        group: EditorGroup,
        labelRes: Int,
        modifiedLabelRes: Int,
        parameterKey: EditParameterKey,
        read: (EditGraph) -> Int,
        write: (EditGraph, Int) -> EditGraph
    ): EditorToolSpec = EditorToolSpec(
        tool = tool,
        group = group,
        labelRes = labelRes,
        modifiedLabelRes = modifiedLabelRes,
        parameterKey = parameterKey,
        controlStyle = EditorControlStyle.DIAL,
        dial = SIGNED_DIAL,
        readValue = { read(it).toFloat() },
        writeValue = { graph, value ->
            write(graph, value.roundToInt().coerceIn(SLIDER_MIN.toInt(), SLIDER_MAX.toInt()))
        },
        isModified = { read(it) != 0 },
        reset = { graph -> write(graph, 0) }
    )
}
