package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * The `AI -> 消除` sub-tool: MI-GAN inpainting driven by an on-canvas brush instead of a dial.
 *
 * The tool owns no parameter of its own; its "modified" marker is the ordered patch list the erase
 * runs append to, and its reset drops that list in one undo step. Everything asynchronous — model
 * install, inference, patch persistence — belongs to
 * [cn.devcxl.photosync.editor.ui.EditorInpaintController].
 */
internal object AiEraseSpecs {

    /** Tool specs contributed to the shared [EditorToolCatalog] for the AI group. */
    fun eraseSpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.AI_ERASE,
            group = EditorGroup.AI,
            labelRes = R.string.editor_tool_ai_erase,
            modifiedLabelRes = R.string.editor_tool_ai_erase_modified,
            parameterKey = EditParameterKey.AI_INPAINT,
            controlStyle = EditorControlStyle.INPAINT,
            isModified = { graph -> graph.inpaint.isNotEmpty() },
            reset = { graph -> graph.copy(inpaint = emptyList()) }
        )
    )
}
