package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * The `AI -> 天空` sub-tool: a dedicated panel because the asset chips and the four sliders are not a
 * single dial. Its "modified" marker is any non-identity `SkyOps`, and a long-press reset clears the
 * whole group in one undo step.
 */
internal object AiSkySpecs {

    /** Tool specs contributed to the shared [EditorToolCatalog] for the AI group. */
    fun skySpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.AI_SKY,
            group = EditorGroup.AI,
            labelRes = R.string.editor_tool_ai_sky,
            modifiedLabelRes = R.string.editor_tool_ai_sky_modified,
            parameterKey = EditParameterKey.AI_SKY,
            controlStyle = EditorControlStyle.SKY,
            isModified = { graph -> isSkyModified(graph) },
            reset = { graph -> graph.copy(sky = SkyOps()) }
        )
    )

    /** True when the sky group carries a replacement or a spatial adjustment worth a marker. */
    fun isSkyModified(graph: EditGraph): Boolean =
        !graph.sky.isIdentity() || graph.sky.maskRef.isNotBlank()
}
