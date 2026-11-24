package cn.devcxl.photosync.editor.ui.panels

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Builds and repaints the tier-2 sub-tool chip row: one chip per [EditorToolSpec], a single tap
 * selects the tool and a double tap resets only that tool's parameters.
 */
internal class SubToolChipRow(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val onSelected: (EditorToolSpec) -> Unit,
    private val onResetRequested: (EditorToolSpec) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val chipViews = mutableMapOf<EditorTool, TextView>()

    /** Rebuilds the row for [tools]. */
    fun build(tools: List<EditorToolSpec>) {
        binding.subToolChipContainer.removeAllViews()
        chipViews.clear()
        tools.forEach { spec ->
            val chip = PanelChipFactory.create(
                context = context,
                labelRes = spec.labelRes,
                heightPx = (CHIP_HEIGHT_DP * density).toInt(),
                textSizeSp = 12.0f,
                paddingPx = (CHIP_PADDING_DP * density).toInt()
            )
            chip.setOnTouchListener(createTouchListener(spec))
            chipViews[spec.tool] = chip
            binding.subToolChipContainer.addView(chip)
        }
    }

    /** Repaints every chip for [graph], highlighting [currentTool]. */
    fun refresh(graph: EditGraph, currentTool: EditorTool) {
        chipViews.forEach { (tool, view) ->
            val spec = EditorToolCatalog.specFor(tool) ?: return@forEach
            view.setText(if (spec.isModified(graph)) spec.modifiedLabelRes else spec.labelRes)
            val selected = tool == currentTool
            PanelChipFactory.applySelection(context, view, selected)
            view.alpha = if (selected) 1.0f else 0.75f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTouchListener(spec: EditorToolSpec): View.OnTouchListener {
        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onSelected(spec)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onResetRequested(spec)
                    return true
                }
            }
        )
        return View.OnTouchListener { view, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
    }

    private companion object {
        private const val CHIP_HEIGHT_DP = 30.0f
        private const val CHIP_PADDING_DP = 13.0f
    }
}
