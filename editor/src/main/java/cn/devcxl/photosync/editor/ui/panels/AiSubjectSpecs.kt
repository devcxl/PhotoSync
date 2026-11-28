package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * The `AI -> 主体` sub-tool: a dedicated panel instead of a dial, because the three background
 * modes are mutually exclusive choices with an extra swatch row for the solid colour.
 */
internal object AiSubjectSpecs {

    /** Tool specs contributed to the shared [EditorToolCatalog] for the AI group. */
    fun subjectSpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.AI_SUBJECT,
            group = EditorGroup.AI,
            labelRes = R.string.editor_tool_ai_subject,
            modifiedLabelRes = R.string.editor_tool_ai_subject_modified,
            parameterKey = EditParameterKey.AI_SUBJECT,
            controlStyle = EditorControlStyle.SUBJECT,
            isModified = { graph -> isSubjectModified(graph) },
            reset = { graph -> graph.copy(subject = SubjectOps()) }
        )
    )

    /** True when the subject group carries a mode, an effect or a cached mask worth a marker. */
    fun isSubjectModified(graph: EditGraph): Boolean =
        !graph.subject.isIdentity() || graph.subject.maskRef.isNotBlank()
}
