package cn.devcxl.photosync.editor.ui.panels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.ui.gesture.CropHandleMath
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.widgets.DialSeekBar

/**
 * Binds the three-tier bottom drawer to the edit graph: six category tabs, the sub-tool chips of the
 * active category, the tier-1 dial, the crop aspect-ratio lock chips and the value read-out.
 *
 * Every interaction funnels through [EditorUndoController], so a continuous drag coalesces into a
 * single undo step per [EditParameterKey] and the host only re-renders what changed.
 */
internal class EditorPanelController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val undoController: EditorUndoController,
    private val host: Host
) {

    /**
     * Host callbacks that keep the preview canvas in sync with the drawer.
     */
    interface Host {
        /** The active sub-tool changed; the canvas overlay must follow. */
        fun onEditorToolChanged(tool: EditorTool)

        /** The graph changed through a continuous drawer interaction. */
        fun onGraphEdited(graph: EditGraph, parameterKey: EditParameterKey)

        /** A discrete edit that seals its own undo step. */
        fun onGraphCommitted(graph: EditGraph, parameterKey: EditParameterKey)

        /** Width/height ratio of the frame the preview canvas is currently showing. */
        fun previewAspect(): Float
    }

    var currentGroup: EditorGroup = EditorGroup.TONE
        private set

    var currentTool: EditorTool = EditorTool.EXPOSURE
        private set

    /** Physical aspect the crop frame is locked to, or `null` when the crop is free. */
    var lockedAspect: Float? = null
        private set

    private val formatter = EditorReadoutFormatter(context)
    private val density = context.resources.displayMetrics.density
    private val tabViews: Map<EditorGroup, TextView> = mapOf(
        EditorGroup.PRESET to binding.tabPreset,
        EditorGroup.GEOMETRY to binding.tabGeometry,
        EditorGroup.TONE to binding.tabTone,
        EditorGroup.COLOR to binding.tabColor,
        EditorGroup.DETAIL to binding.tabDetail,
        EditorGroup.PORTRAIT to binding.tabPortrait
    )
    private val chipViews = mutableMapOf<EditorTool, TextView>()
    private val aspectChipViews = mutableMapOf<String, TextView>()
    private var dialTool: EditorTool? = null

    init {
        tabViews.forEach { (group, view) -> view.setOnClickListener { selectGroup(group) } }
        setupDial()
        setupAspectChips()
    }

    /**
     * Re-reads the graph and refreshes every chip state and read-out.
     */
    fun refresh() {
        val graph = undoController.currentGraph
        chipViews.forEach { (tool, view) -> bindChip(graph, tool, view) }
        dialTool?.let { tool ->
            EditorToolCatalog.specFor(tool)?.let { binding.dialSeekBar.setValue(it.readValue(graph)) }
        }
        if (currentTool != EditorTool.NONE) {
            binding.activeValueText.text = formatter.valueText(currentTool, graph, host.previewAspect())
        }
        binding.aspectChipScroll.visibility =
            if (currentTool == EditorTool.CROP) View.VISIBLE else View.GONE
        val activeKey = EditorReadoutFormatter.aspectKey(lockedAspect, host.previewAspect())
        aspectChipViews.forEach { (key, view) -> bindAspectChip(view, key == activeKey) }
    }

    /**
     * Selects a category tab and, when it has tools, its first sub-tool.
     */
    fun selectGroup(group: EditorGroup) {
        currentGroup = group
        tabViews.forEach { (candidate, view) -> bindTab(view, candidate == group) }
        val tools = EditorToolCatalog.toolsFor(group)
        if (tools.isEmpty()) {
            enterUnavailableGroup(group)
            return
        }
        buildChips(tools)
        selectTool(tools.first().tool, applyInstantAction = false)
    }

    /**
     * Selects a sub-tool, applying its one-tap action when [applyInstantAction] is true.
     */
    fun selectTool(tool: EditorTool, applyInstantAction: Boolean = true) {
        val spec = EditorToolCatalog.specFor(tool) ?: return
        currentTool = tool
        configureDial(spec)
        binding.activeToolLabel.setText(spec.labelRes)
        val action = spec.instantAction
        if (applyInstantAction && spec.controlStyle == EditorControlStyle.INSTANT && action != null) {
            commit(spec.parameterKey) { action(it) }
        }
        refresh()
        host.onEditorToolChanged(tool)
    }

    /**
     * Releases or installs the crop aspect lock and re-fits the crop rectangle.
     */
    fun applyAspectPreset(preset: CropAspectPreset) {
        val frameAspect = host.previewAspect().takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        val aspect = preset.aspectOf(frameAspect)
        lockedAspect = aspect
        val rect = if (aspect == null) {
            CropRect()
        } else {
            CropHandleMath.centredWithAspect(aspect, frameAspect)
        }
        val spec = EditorToolCatalog.specFor(EditorTool.CROP) ?: return
        commit(spec.parameterKey) { it.copy(geometry = it.geometry.copy(crop = rect)) }
        host.onEditorToolChanged(EditorTool.CROP)
        refresh()
    }

    private fun enterUnavailableGroup(group: EditorGroup) {
        currentTool = EditorTool.NONE
        dialTool = null
        buildChips(emptyList())
        binding.dialSeekBar.visibility = View.GONE
        binding.aspectChipScroll.visibility = View.GONE
        val tabLabel = tabViews[group]?.text
        binding.activeToolLabel.text = tabLabel
        binding.activeValueText.setText(R.string.editor_tool_coming_soon)
        Toast.makeText(context, R.string.editor_tool_coming_soon, Toast.LENGTH_SHORT).show()
        host.onEditorToolChanged(EditorTool.NONE)
    }

    private fun setupDial() {
        binding.dialSeekBar.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (!fromUser) return
                val spec = EditorToolCatalog.specFor(dialTool ?: return) ?: return
                val updated = spec.writeValue(undoController.currentGraph, value)
                val applied = undoController.onParameterDrag(updated, spec.parameterKey)
                binding.activeValueText.text = formatter.formatDialValue(spec, spec.readValue(applied))
                host.onGraphEdited(applied, spec.parameterKey)
            }

            override fun onStopTrackingTouch() {
                undoController.onParameterRelease()
                val spec = EditorToolCatalog.specFor(dialTool ?: return) ?: return
                host.onGraphCommitted(undoController.currentGraph, spec.parameterKey)
                refresh()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildChips(tools: List<EditorToolSpec>) {
        binding.subToolChipContainer.removeAllViews()
        chipViews.clear()
        tools.forEach { spec ->
            val chip = createChip(spec.labelRes, chipHeightPx(), 12.0f)
            val detector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        selectTool(spec.tool)
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        commit(spec.parameterKey) { spec.reset(it) }
                        selectTool(spec.tool, applyInstantAction = false)
                        return true
                    }
                }
            )
            chip.setOnTouchListener { view, event ->
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                true
            }
            chipViews[spec.tool] = chip
            binding.subToolChipContainer.addView(chip)
        }
    }

    private fun setupAspectChips() {
        binding.aspectChipContainer.removeAllViews()
        aspectChipViews.clear()
        EditorReadoutFormatter.presets.forEach { preset ->
            val chip = createChip(preset.labelRes, aspectChipHeightPx(), 11.0f)
            chip.setOnClickListener { applyAspectPreset(preset) }
            aspectChipViews[preset.key] = chip
            binding.aspectChipContainer.addView(chip)
        }
    }

    private fun createChip(labelRes: Int, heightPx: Int, textSizeSp: Float): TextView {
        val chip = TextView(context).apply {
            setText(labelRes)
            textSize = textSizeSp
            gravity = Gravity.CENTER
            setPadding(chipPaddingPx(), 0, chipPaddingPx(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                heightPx
            ).apply { marginEnd = chipPaddingPx() / 3 }
            setTextColor(ContextCompat.getColor(context, R.color.editor_text_primary))
            isClickable = true
            isFocusable = true
        }
        chip.setBackgroundColor(ContextCompat.getColor(context, R.color.editor_surface_elevated))
        return chip
    }

    private fun configureDial(spec: EditorToolSpec) {
        val dial = spec.dial
        dialTool = if (dial == null) null else spec.tool
        if (dial == null) {
            binding.dialSeekBar.visibility = View.GONE
            return
        }
        binding.dialSeekBar.visibility = View.VISIBLE
        binding.dialSeekBar.minValue = dial.min
        binding.dialSeekBar.maxValue = dial.max
        binding.dialSeekBar.stepSize = dial.step
        binding.dialSeekBar.zeroSnapThreshold = dial.zeroSnapThreshold
        binding.dialSeekBar.setValue(spec.readValue(undoController.currentGraph))
    }

    private fun commit(parameterKey: EditParameterKey, transform: (EditGraph) -> EditGraph) {
        val updated = undoController.onParameterDrag(transform(undoController.currentGraph), parameterKey)
        undoController.onParameterRelease()
        host.onGraphCommitted(updated, parameterKey)
    }

    private fun bindTab(view: TextView, selected: Boolean) {
        view.setBackgroundColor(
            if (selected) ContextCompat.getColor(context, R.color.editor_surface_elevated) else 0
        )
        view.setTextColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.editor_text_primary else R.color.editor_text_muted
            )
        )
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun bindChip(graph: EditGraph, tool: EditorTool, view: TextView) {
        val spec = EditorToolCatalog.specFor(tool) ?: return
        view.setText(if (spec.isModified(graph)) spec.modifiedLabelRes else spec.labelRes)
        val selected = tool == currentTool
        view.setBackgroundColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.editor_accent_blue else R.color.editor_surface_elevated
            )
        )
        view.alpha = if (selected) 1.0f else 0.75f
    }

    private fun bindAspectChip(view: TextView, selected: Boolean) {
        view.setBackgroundColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.editor_accent_blue else R.color.editor_surface_elevated
            )
        )
    }

    private fun chipHeightPx(): Int = (CHIP_HEIGHT_DP * density).toInt()

    private fun aspectChipHeightPx(): Int = (ASPECT_CHIP_HEIGHT_DP * density).toInt()

    private fun chipPaddingPx(): Int = (CHIP_PADDING_DP * density).toInt()

    private companion object {
        private const val CHIP_HEIGHT_DP = 30.0f
        private const val ASPECT_CHIP_HEIGHT_DP = 26.0f
        private const val CHIP_PADDING_DP = 13.0f
    }
}
