package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * The `AI -> 云端` sub-tool: a BYOK endpoint turns a prompt (optionally plus a painted or subject
 * mask) into one Post-Pass A `CloudEditLayer`.
 *
 * The tool owns no slider of its own; the panel drives the request and the layer controls, while
 * [cn.devcxl.photosync.editor.ui.EditorCloudController] owns the state machine and the undo steps.
 * The chip is hidden unless the global P4 switch is on, so this spec is filtered out of the AI group
 * by [EditorPanelController] instead of controlling its own visibility.
 */
internal object AiCloudSpecs {

    /** Tool specs contributed to the shared [EditorToolCatalog] for the AI group. */
    fun cloudSpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.AI_CLOUD,
            group = EditorGroup.AI,
            labelRes = R.string.editor_tool_ai_cloud,
            modifiedLabelRes = R.string.editor_tool_ai_cloud_modified,
            parameterKey = EditParameterKey.AI_CLOUD,
            controlStyle = EditorControlStyle.CLOUD,
            isModified = { graph -> graph.cloudLayer != null },
            reset = { graph -> graph.copy(cloudLayer = null) }
        )
    )
}
