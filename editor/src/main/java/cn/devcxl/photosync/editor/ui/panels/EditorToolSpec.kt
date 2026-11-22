package cn.devcxl.photosync.editor.ui.panels

import kotlin.math.abs

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * How the tier-1 control slot drives a tool.
 */
internal enum class EditorControlStyle {
    /** Zero-snap dial slider with a numeric read-out. */
    DIAL,

    /** One-tap action such as a quarter turn or a mirror. */
    INSTANT,

    /** The preview canvas owns the gesture; no dial is shown. */
    CANVAS,

    /** The canvas owns the gesture and the tool needs its own brush control block. */
    LIQUIFY,

    /** 4-channel monotone cubic tone curve editor. */
    CURVE,

    /** 8-band OKLCH HSL mixer panel. */
    HSL,

    /** Cartesian OKLab split-toning panel. */
    SPLIT_TONING,

    /** `.cube` 3D LUT importer with LUT chips and a strength slider. */
    LUT,

    /** Film grain panel (amount, size, roughness). */
    GRAIN,

    /** Post-crop vignette panel (amount, midpoint, feather, roundness). */
    VIGNETTE
}

/**
 * Range and formatting of a tier-1 dial slider.
 */
internal data class DialSpec(
    val min: Float,
    val max: Float,
    val step: Float,
    val zeroSnapThreshold: Float,
    val valueFormatRes: Int
)

/**
 * Declarative description of one editor sub-tool: label, undo coalescing key, how the tier-1
 * control drives it, and how its value maps onto the [EditGraph].
 */
internal data class EditorToolSpec(
    val tool: EditorTool,
    val group: EditorGroup,
    val labelRes: Int,
    val modifiedLabelRes: Int,
    val parameterKey: EditParameterKey,
    val controlStyle: EditorControlStyle,
    val dial: DialSpec? = null,
    val readValue: (EditGraph) -> Float = { 0.0f },
    val writeValue: (EditGraph, Float) -> EditGraph = { graph, _ -> graph },
    val isModified: (EditGraph) -> Boolean = { false },
    val instantAction: ((EditGraph) -> EditGraph)? = null,
    val reset: (EditGraph) -> EditGraph = { it }
)

/**
 * Registry of the tools delivered so far: geometry (including liquify), tone, white balance,
 * display color, the Pass D effect panels (3D LUT, film grain, post-crop vignette) and lens
 * dispersion / defringe.
 */
internal object EditorToolCatalog {

    private const val QUARTER_TURNS = 4

    private val specs: Map<EditorTool, EditorToolSpec> =
        (
            cropAndStraightenSpecs() +
                flipAndPerspectiveSpecs() +
                EditorToneDetailSpecs.toneSpecs() +
                EditorColorSpecs.colourSpecs() +
                EditorToneDetailSpecs.detailSpecs()
            )
            .associateBy { it.tool }

    /**
     * Returns the sub-tools of [group] in chip order.
     */
    fun toolsFor(group: EditorGroup): List<EditorToolSpec> = specs.values.filter { it.group == group }

    /**
     * Returns the spec of [tool].
     */
    fun specFor(tool: EditorTool): EditorToolSpec? = specs[tool]

    /**
     * Returns the first tool of [group], or [EditorTool.NONE] when the group is not available yet.
     */
    fun defaultToolFor(group: EditorGroup): EditorTool =
        toolsFor(group).firstOrNull()?.tool ?: EditorTool.NONE

    private fun cropAndStraightenSpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.CROP,
            group = EditorGroup.GEOMETRY,
            labelRes = R.string.editor_tool_crop,
            modifiedLabelRes = R.string.editor_tool_crop_modified,
            parameterKey = EditParameterKey.GEOMETRY_CROP,
            controlStyle = EditorControlStyle.CANVAS,
            isModified = { !it.geometry.crop.isIdentity() },
            reset = { it.copy(geometry = it.geometry.copy(crop = CropRect())) }
        ),
        EditorToolSpec(
            tool = EditorTool.STRAIGHTEN,
            group = EditorGroup.GEOMETRY,
            labelRes = R.string.editor_tool_straighten,
            modifiedLabelRes = R.string.editor_tool_straighten_modified,
            parameterKey = EditParameterKey.GEOMETRY_STRAIGHTEN,
            controlStyle = EditorControlStyle.DIAL,
            dial = DialSpec(
                min = -45.0f,
                max = 45.0f,
                step = 0.1f,
                zeroSnapThreshold = 0.25f,
                valueFormatRes = R.string.editor_value_degrees_fmt
            ),
            readValue = { it.geometry.straighten },
            writeValue = { graph, value -> graph.copy(geometry = graph.geometry.copy(straighten = value)) },
            isModified = { abs(it.geometry.straighten) >= 1e-4f },
            reset = { it.copy(geometry = it.geometry.copy(straighten = 0.0f)) }
        )
    )

    private fun flipAndPerspectiveSpecs(): List<EditorToolSpec> = listOf(
        EditorToolSpec(
            tool = EditorTool.ROTATE90,
            group = EditorGroup.GEOMETRY,
            labelRes = R.string.editor_tool_rotate90,
            modifiedLabelRes = R.string.editor_tool_rotate90_modified,
            parameterKey = EditParameterKey.GEOMETRY_ROTATE_FLIP,
            controlStyle = EditorControlStyle.INSTANT,
            isModified = { it.geometry.rotate90 != 0 },
            instantAction = {
                it.copy(geometry = it.geometry.copy(rotate90 = (it.geometry.rotate90 + 1) % QUARTER_TURNS))
            },
            reset = { it.copy(geometry = it.geometry.copy(rotate90 = 0)) }
        ),
        EditorToolSpec(
            tool = EditorTool.FLIP_HORIZONTAL,
            group = EditorGroup.GEOMETRY,
            labelRes = R.string.editor_tool_flip_horizontal,
            modifiedLabelRes = R.string.editor_tool_flip_horizontal_modified,
            parameterKey = EditParameterKey.GEOMETRY_ROTATE_FLIP,
            controlStyle = EditorControlStyle.INSTANT,
            isModified = { it.geometry.flipH },
            instantAction = { it.copy(geometry = it.geometry.copy(flipH = !it.geometry.flipH)) },
            reset = { it.copy(geometry = it.geometry.copy(flipH = false)) }
        ),
        EditorToolSpec(
            tool = EditorTool.FLIP_VERTICAL,
            group = EditorGroup.GEOMETRY,
            labelRes = R.string.editor_tool_flip_vertical,
            modifiedLabelRes = R.string.editor_tool_flip_vertical_modified,
            parameterKey = EditParameterKey.GEOMETRY_ROTATE_FLIP,
            controlStyle = EditorControlStyle.INSTANT,
            isModified = { it.geometry.flipV },
            instantAction = { it.copy(geometry = it.geometry.copy(flipV = !it.geometry.flipV)) },
            reset = { it.copy(geometry = it.geometry.copy(flipV = false)) }
        ),
        EditorToolSpec(
            tool = EditorTool.PERSPECTIVE,
            group = EditorGroup.GEOMETRY,
            labelRes = R.string.editor_tool_perspective,
            modifiedLabelRes = R.string.editor_tool_perspective_modified,
            parameterKey = EditParameterKey.GEOMETRY_PERSPECTIVE,
            controlStyle = EditorControlStyle.CANVAS,
            isModified = { it.geometry.perspective != GeometryOps.DEFAULT_PERSPECTIVE_QUAD },
            reset = {
                it.copy(
                    geometry = it.geometry.copy(perspective = GeometryOps.DEFAULT_PERSPECTIVE_QUAD)
                )
            }
        ),
        EditorToolSpec(
            tool = EditorTool.LIQUIFY,
            group = EditorGroup.GEOMETRY,
            labelRes = R.string.editor_tool_liquify,
            modifiedLabelRes = R.string.editor_tool_liquify_modified,
            parameterKey = EditParameterKey.GEOMETRY_LIQUIFY,
            controlStyle = EditorControlStyle.LIQUIFY,
            isModified = { it.geometry.liquify != null },
            reset = { it.copy(geometry = it.geometry.copy(liquify = null)) }
        )
    )
}
