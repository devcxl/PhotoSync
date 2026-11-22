package cn.devcxl.photosync.editor.ui.panels

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyBrushSettings
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.ui.gesture.CropHandleMath
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.widgets.DialSeekBar

/**
 * Binds the three-tier bottom drawer to the edit graph: six category tabs, the sub-tool chips of the
 * active category, the tier-1 control slot (`DialSeekBar`, `CurveEditorView`, `HslPanelView`,
 * `SplitToningPanelView`, `LutPanelView`, `GrainPanelView`, `VignettePanelView` or
 * `LiquifyBrushPanel`), the crop aspect chips and the value read-out.
 */
internal class EditorPanelController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val undoController: EditorUndoController,
    private val host: Host,
    lutStore: CubeLutStore
) {

    /** Host callbacks that keep the preview canvas in sync with the drawer. */
    interface Host {
        /** The active sub-tool changed; the canvas overlay must follow. */
        fun onEditorToolChanged(tool: EditorTool)
        /** The graph changed through a continuous drawer interaction. */
        fun onGraphEdited(graph: EditGraph, parameterKey: EditParameterKey)
        /** A discrete edit that seals its own undo step. */
        fun onGraphCommitted(graph: EditGraph, parameterKey: EditParameterKey)
        /** Width/height ratio of the frame the preview canvas is currently showing. */
        fun previewAspect(): Float
        /** The liquify brush mode, size or strength changed. */
        fun onLiquifySettingsChanged(settings: LiquifyBrushSettings)
        /** The user asked to clear every liquify displacement. */
        fun onLiquifyResetRequested()
        /** The user asked to import a `.cube` LUT through the system file chooser. */
        fun onLutImportRequested()
    }

    var currentGroup: EditorGroup = EditorGroup.TONE
        private set

    var currentTool: EditorTool = EditorTool.EXPOSURE
        private set

    /** Physical aspect the crop frame is locked to, or `null` when the crop is free. */
    var lockedAspect: Float? = null
        private set

    private val formatter = EditorReadoutFormatter(context)
    private val liquifyPanel = LiquifyBrushPanelController(
        context = context,
        binding = binding,
        host = object : LiquifyBrushPanelController.Host {
            override fun onLiquifySettingsChanged(settings: LiquifyBrushSettings) =
                host.onLiquifySettingsChanged(settings)

            override fun onLiquifyResetRequested() = host.onLiquifyResetRequested()
        }
    )
    private val colorPanels = ColorPanelsBinder(
        binding = binding,
        undoController = undoController,
        onEdited = { graph, key ->
            refreshHeaderReadout(graph)
            host.onGraphEdited(graph, key)
        },
        onCommitted = { graph, key ->
            host.onGraphCommitted(graph, key)
            refresh()
        }
    )
    private val effectsPanels = EffectsPanelsBinder(
        binding = binding,
        undoController = undoController,
        lutStore = lutStore,
        onImportRequested = host::onLutImportRequested,
        onEdited = { graph, key ->
            refreshHeaderReadout(graph)
            host.onGraphEdited(graph, key)
        },
        onCommitted = { graph, key ->
            host.onGraphCommitted(graph, key)
            refresh()
        }
    )
    private val aspectRow = CropAspectChipRow(context, binding, ::applyAspectPreset)
    private val tabViews: Map<EditorGroup, TextView> = mapOf(
        EditorGroup.PRESET to binding.tabPreset,
        EditorGroup.GEOMETRY to binding.tabGeometry,
        EditorGroup.TONE to binding.tabTone,
        EditorGroup.COLOR to binding.tabColor,
        EditorGroup.DETAIL to binding.tabDetail,
        EditorGroup.PORTRAIT to binding.tabPortrait
    )
    private val chipRow = SubToolChipRow(
        context = context,
        binding = binding,
        onSelected = { spec -> selectTool(spec.tool) },
        onResetRequested = { spec ->
            resetTool(spec)
            selectTool(spec.tool, applyInstantAction = false)
        }
    )
    private var dialTool: EditorTool? = null

    init {
        tabViews.forEach { (group, view) -> view.setOnClickListener { selectGroup(group) } }
        setupDial()
        liquifyPanel.setVisible(false)
        colorPanels.showFor(null)
        effectsPanels.showFor(null)
        aspectRow.setVisible(false)
    }

    /** Re-reads the graph and refreshes every chip state, panel and read-out. */
    fun refresh() {
        val graph = undoController.currentGraph
        chipRow.refresh(graph, currentTool)
        dialTool?.let { tool ->
            EditorToolCatalog.specFor(tool)?.let { binding.dialSeekBar.setValue(it.readValue(graph)) }
        }
        colorPanels.refresh(graph)
        effectsPanels.refresh(graph)
        refreshHeaderReadout(graph)
        aspectRow.setVisible(currentTool == EditorTool.CROP)
        aspectRow.refresh(lockedAspect, host.previewAspect())
    }

    /** Selects a category tab and, when it has tools, its first sub-tool. */
    fun selectGroup(group: EditorGroup) {
        currentGroup = group
        tabViews.forEach { (candidate, view) -> bindTab(view, candidate == group) }
        val tools = EditorToolCatalog.toolsFor(group)
        if (tools.isEmpty()) {
            enterUnavailableGroup(group)
            return
        }
        chipRow.build(tools)
        selectTool(tools.first().tool, applyInstantAction = false)
    }

    /** Selects a sub-tool, applying its one-tap action when [applyInstantAction] is true. */
    fun selectTool(tool: EditorTool, applyInstantAction: Boolean = true) {
        val spec = EditorToolCatalog.specFor(tool) ?: return
        currentTool = tool
        configureTierOneControl(spec)
        binding.activeToolLabel.setText(spec.labelRes)
        val action = spec.instantAction
        if (applyInstantAction && spec.controlStyle == EditorControlStyle.INSTANT && action != null) {
            commit(spec.parameterKey) { action(it) }
        }
        refresh()
        host.onEditorToolChanged(tool)
    }

    /** Releases or installs the crop aspect lock and re-fits the crop rectangle. */
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

    private fun refreshHeaderReadout(graph: EditGraph) {
        if (currentTool != EditorTool.NONE) {
            binding.activeValueText.text = formatter.valueText(currentTool, graph, host.previewAspect())
        }
    }

    private fun enterUnavailableGroup(group: EditorGroup) {
        currentTool = EditorTool.NONE
        dialTool = null
        chipRow.build(emptyList())
        binding.dialSeekBar.visibility = View.GONE
        liquifyPanel.setVisible(false)
        colorPanels.showFor(null)
        effectsPanels.showFor(null)
        aspectRow.setVisible(false)
        binding.activeToolLabel.text = tabViews[group]?.text
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

    private fun resetTool(spec: EditorToolSpec) {
        if (spec.controlStyle == EditorControlStyle.LIQUIFY) {
            host.onLiquifyResetRequested()
            return
        }
        commit(spec.parameterKey) { spec.reset(it) }
    }

    private fun configureTierOneControl(spec: EditorToolSpec) {
        liquifyPanel.setVisible(spec.controlStyle == EditorControlStyle.LIQUIFY)
        colorPanels.showFor(spec.controlStyle)
        effectsPanels.showFor(spec.controlStyle)
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
}
