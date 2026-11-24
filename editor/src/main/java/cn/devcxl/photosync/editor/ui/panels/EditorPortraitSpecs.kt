package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.BodyOps
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.ReshapeOps
import cn.devcxl.photosync.editor.domain.SkinOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * The Portrait tab: Skin Smoothing, Face Reshaping and Body Reshaping.
 *
 * Each tool owns a dedicated undo bucket so a continuous skin drag never merges with a face or body
 * change, and each one resets only its own `portrait.*` branch.
 */
internal object EditorPortraitSpecs {

    private const val DEFAULT_SKIN_TEXTURE = 50

    /** Returns the Portrait tools in drawer chip order. */
    fun portraitSpecs(): List<EditorToolSpec> = listOf(
        skinSpec(),
        faceReshapeSpec(),
        bodyReshapeSpec()
    )

    private fun skinSpec(): EditorToolSpec = EditorToolSpec(
        tool = EditorTool.SKIN_SMOOTH,
        group = EditorGroup.PORTRAIT,
        labelRes = R.string.editor_tool_skin_smooth,
        modifiedLabelRes = R.string.editor_tool_skin_smooth_modified,
        parameterKey = EditParameterKey.PORTRAIT_SKIN,
        controlStyle = EditorControlStyle.SKIN,
        isModified = { graph -> isSkinModified(graph.portrait.skin) },
        reset = { graph -> graph.copy(portrait = graph.portrait.copy(skin = SkinOps())) }
    )

    private fun faceReshapeSpec(): EditorToolSpec = EditorToolSpec(
        tool = EditorTool.FACE_RESHAPE,
        group = EditorGroup.PORTRAIT,
        labelRes = R.string.editor_tool_face_reshape,
        modifiedLabelRes = R.string.editor_tool_face_reshape_modified,
        parameterKey = EditParameterKey.PORTRAIT_RESHAPE,
        controlStyle = EditorControlStyle.FACE_RESHAPE,
        isModified = { graph -> !graph.portrait.reshape.isIdentity() },
        reset = { graph ->
            graph.copy(portrait = graph.portrait.copy(reshape = ReshapeOps()))
        }
    )

    private fun bodyReshapeSpec(): EditorToolSpec = EditorToolSpec(
        tool = EditorTool.BODY_RESHAPE,
        group = EditorGroup.PORTRAIT,
        labelRes = R.string.editor_tool_body_reshape,
        modifiedLabelRes = R.string.editor_tool_body_reshape_modified,
        parameterKey = EditParameterKey.PORTRAIT_BODY,
        controlStyle = EditorControlStyle.BODY_RESHAPE,
        isModified = { graph ->
            !graph.portrait.body.isIdentity() ||
                graph.portrait.body.waistY != null ||
                graph.portrait.body.hipY != null
        },
        reset = { graph -> graph.copy(portrait = graph.portrait.copy(body = BodyOps())) }
    )

    private fun isSkinModified(skin: SkinOps): Boolean =
        !skin.isIdentity() || skin.texture != DEFAULT_SKIN_TEXTURE
}
