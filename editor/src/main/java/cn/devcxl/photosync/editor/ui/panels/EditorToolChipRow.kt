package cn.devcxl.photosync.editor.ui.panels

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Builds and refreshes the Tier-2 sub-tool chip row.
 *
 * A single tap selects the tool, a double tap resets it, and the chip label carries the `•` modified
 * marker whenever the tool's own edit-graph field is off its neutral value. Extracted from
 * [EditorPanelController] so the drawer controller only routes group/tool selection.
 */
internal class EditorToolChipRow(
    private val context: Context,
    private val container: ViewGroup,
    private val onSelect: (EditorToolSpec, Boolean) -> Unit,
    private val onReset: (EditorToolSpec) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val views = mutableMapOf<EditorTool, TextView>()

    /** Recreates one chip per [tools] entry. */
    @SuppressLint("ClickableViewAccessibility")
    fun build(tools: List<EditorToolSpec>) {
        container.removeAllViews()
        views.clear()
        tools.forEach { spec ->
            val chip = PanelChipFactory.create(
                context = context,
                labelRes = spec.labelRes,
                heightPx = (CHIP_HEIGHT_DP * density).toInt(),
                textSizeSp = CHIP_TEXT_SIZE_SP,
                paddingPx = (CHIP_PADDING_DP * density).toInt()
            )
            val detector = GestureDetector(context, GestureListener(spec))
            chip.setOnTouchListener { view, event ->
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                true
            }
            views[spec.tool] = chip
            container.addView(chip)
        }
    }

    /** Syncs every chip label and selection state with [graph] and [selectedTool]. */
    fun refresh(graph: EditGraph, selectedTool: EditorTool) {
        views.forEach { (tool, view) ->
            val spec = EditorToolCatalog.specFor(tool) ?: return@forEach
            view.setText(if (spec.isModified(graph)) spec.modifiedLabelRes else spec.labelRes)
            val selected = tool == selectedTool
            PanelChipFactory.applySelection(context, view, selected)
            view.alpha = if (selected) 1.0f else 0.75f
        }
    }

    private inner class GestureListener(private val spec: EditorToolSpec) :
        GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSelect(spec, true)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onReset(spec)
            onSelect(spec, false)
            return true
        }
    }

    private companion object {
        private const val CHIP_HEIGHT_DP = 30.0f
        private const val CHIP_PADDING_DP = 13.0f
        private const val CHIP_TEXT_SIZE_SP = 12.0f
    }
}
