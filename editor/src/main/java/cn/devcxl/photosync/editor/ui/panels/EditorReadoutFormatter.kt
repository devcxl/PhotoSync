package cn.devcxl.photosync.editor.ui.panels

import java.util.Locale
import kotlin.math.abs

import android.content.Context

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.ui.gesture.CropHandleMath
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * A physical aspect ratio the crop frame can be locked to.
 */
internal data class CropAspectPreset(
    val key: String,
    val labelRes: Int,
    val aspectOf: (Float) -> Float?
)

/**
 * Formats the tier-1 read-out for every sub-tool and owns the crop aspect-ratio presets.
 *
 * Pure apart from string lookups, so the drawer never needs to branch on tool identity itself.
 */
internal class EditorReadoutFormatter(private val context: Context) {

    /**
     * Returns the text shown next to the active tool label.
     */
    fun valueText(tool: EditorTool, graph: EditGraph, previewAspect: Float): String {
        val spec = EditorToolCatalog.specFor(tool)
        if (spec?.dial != null) return formatDialValue(spec, spec.readValue(graph))
        return when (tool) {
            EditorTool.CROP, EditorTool.PERSPECTIVE -> String.format(
                Locale.US,
                context.getString(R.string.editor_value_aspect_fmt),
                CropHandleMath.physicalAspect(graph.geometry.crop, previewAspect)
            )
            EditorTool.ROTATE90 -> String.format(
                Locale.US,
                context.getString(R.string.editor_value_plain_degrees_fmt),
                (graph.geometry.rotate90 * QUARTER_TURN_DEGREES).toFloat()
            )
            EditorTool.FLIP_HORIZONTAL -> onOff(graph.geometry.flipH)
            EditorTool.FLIP_VERTICAL -> onOff(graph.geometry.flipV)
            EditorTool.LIQUIFY -> context.getString(
                if (graph.geometry.liquify != null) {
                    R.string.editor_value_liquify_modified
                } else {
                    R.string.editor_value_liquify_none
                }
            )
            else -> ""
        }
    }

    /**
     * Formats a dial value with the tool's own unit and precision.
     */
    fun formatDialValue(spec: EditorToolSpec, value: Float): String {
        val formatRes = spec.dial?.valueFormatRes ?: return ""
        return String.format(Locale.US, context.getString(formatRes), value)
    }

    private fun onOff(enabled: Boolean): String = context.getString(
        if (enabled) R.string.editor_value_on else R.string.editor_value_off
    )

    companion object {
        private const val QUARTER_TURN_DEGREES = 90
        private const val ASPECT_TOLERANCE = 1e-3f

        /** Style applied to a chip that is currently selected. */
        const val KEY_FREE = "free"

        /**
         * Returns the key of the preset matching [lockedAspect] in a frame of [frameAspect].
         *
         * A `null` lock always resolves to the free preset, and an unrecognised ratio also falls
         * back to free so the chip row never highlights two options at once.
         */
        fun aspectKey(lockedAspect: Float?, frameAspect: Float): String =
            presets.firstOrNull { preset ->
                val aspect = preset.aspectOf(frameAspect)
                if (aspect == null || lockedAspect == null) {
                    aspect == null && lockedAspect == null
                } else {
                    abs(aspect - lockedAspect) < ASPECT_TOLERANCE
                }
            }?.key ?: presets.first().key

        /**
         * Crop aspect-ratio chips. `自由` releases the lock, `原比例` re-locks to the frame's own
         * aspect, and the fixed ratios fit the largest centred rectangle of that shape.
         */
        val presets: List<CropAspectPreset> = listOf(
            CropAspectPreset(KEY_FREE, R.string.editor_aspect_free) { null },
            CropAspectPreset("original", R.string.editor_aspect_original) { frame -> frame },
            CropAspectPreset("square", R.string.editor_aspect_square) { 1.0f },
            CropAspectPreset("four_three", R.string.editor_aspect_four_three) { 4.0f / 3.0f },
            CropAspectPreset("sixteen_nine", R.string.editor_aspect_sixteen_nine) { 16.0f / 9.0f }
        )
    }
}
