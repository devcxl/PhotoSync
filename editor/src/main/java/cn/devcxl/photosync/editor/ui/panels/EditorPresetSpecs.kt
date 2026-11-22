package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.PresetGraphMerger
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * The `预设` group's single tool.
 *
 * A preset is not a slider: it carries its whole control surface in `PresetPanelView`, so the tier-1
 * slot shows no dial and the chip exists only to make the category reachable — replacing the dead
 * "coming soon" end the preset tab used to be.
 */
internal object EditorPresetSpecs {

    /** Tool spec of the preset group. */
    fun presetSpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.PRESET,
            group = EditorGroup.PRESET,
            labelRes = R.string.editor_tool_preset,
            modifiedLabelRes = R.string.editor_tool_preset_modified,
            parameterKey = EditParameterKey.PRESET,
            controlStyle = EditorControlStyle.PRESET,
            isModified = { graph -> !PresetGraphMerger.hasNeutralPresetAxes(graph) },
            reset = { graph -> PresetGraphMerger.resetPresetAxes(graph) }
        )
    )
}
