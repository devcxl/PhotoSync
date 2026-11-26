package cn.devcxl.photosync.editor.ui.panels

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifyBrushSettings
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.ui.gesture.CropHandleMath
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool
import cn.devcxl.photosync.editor.ui.widgets.DialSeekBar

/**
 * Binds the three-tier bottom drawer to the edit graph: seven category tabs, the sub-tool chips of
 * the active category, the tier-1 control slot (`DialSeekBar`, color, spatial-detail, effect,
 * liquify, subject or inpaint panels), the crop aspect chips and the value read-out.
 *
 * The seventh `AI` tab hosts the subject cut-out and the MI-GAN erase tool; the sky tool lands in a
 * later wave inside the same chip container.
 */
internal class EditorPanelController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val undoController: EditorUndoController,
    private val host: Host,
    lutStore: CubeLutStore,
    owner: LifecycleOwner
) {

    /** Host callbacks that keep the preview canvas in sync with the drawer. */
    interface Host {
        /** The active sub-tool changed; the canvas overlay must follow. */
        fun onEditorToolChanged(tool: EditorTool)
        /** The graph changed through a continuous drawer interaction. */
        fun onGraphEdited(graph: EditGraph, parameterKey: EditParameterKey)
        /** A discrete edit that seals its own undo step. */
        fun onGraphCommitted(graph: EditGraph, parameterKey: EditParameterKey)
        /** Clears every adjustment of the photo; returns false when it is already untouched. */
        fun onResetAllRequested(): Boolean
        /** Width/height ratio of the frame the preview canvas is currently showing. */
        fun previewAspect(): Float
        /** The liquify brush mode, size or strength changed. */
        fun onLiquifySettingsChanged(settings: LiquifyBrushSettings)
        /** The user asked to clear every liquify displacement. */
        fun onLiquifyResetRequested()
        /** The user asked to import a `.cube` LUT through the system file chooser. */
        fun onLutImportRequested()

        /** The user selected a subject background mode; may start model download/inference. */
        fun onSubjectModeRequested(mode: SubjectBackgroundMode)

        /** The user picked a solid subject background colour. */
        fun onSubjectColorRequested(argb: Int)

        /** True when the P4 cloud switch is on, so the AI group may offer the cloud sub-tool. */
        fun isCloudToolAvailable(): Boolean
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
    private val rotateFlipPanel = RotateFlipPanelBinder(binding, ::commit)
    private val colorPanels = ColorPanelsBinder(
        binding = binding,
        undoController = undoController,
        onEdited = ::handleSubPanelEdited,
        onCommitted = ::handleSubPanelCommitted
    )
    private val detailPanels = DetailPanelsBinder(
        binding = binding,
        undoController = undoController,
        onEdited = ::handleSubPanelEdited,
        onCommitted = ::handleSubPanelCommitted
    )
    private val portraitPanels = PortraitPanelsBinder(
        binding = binding,
        undoController = undoController,
        onEdited = ::handleSubPanelEdited,
        onCommitted = ::handleSubPanelCommitted
    )
    private val effectsPanels = EffectsPanelsBinder(
        binding = binding,
        undoController = undoController,
        lutStore = lutStore,
        onImportRequested = host::onLutImportRequested,
        onEdited = ::handleSubPanelEdited,
        onCommitted = ::handleSubPanelCommitted
    )
    private val subjectPanels = SubjectPanelBinder(
        binding = binding,
        host = object : SubjectPanelBinder.Host {
            override fun onSubjectModeRequested(mode: SubjectBackgroundMode) =
                host.onSubjectModeRequested(mode)

            override fun onSubjectColorRequested(argb: Int) = host.onSubjectColorRequested(argb)
        }
    )
    private val presetPanels = PresetPanelBinder(
        context = context,
        binding = binding,
        undoController = undoController,
        owner = owner,
        host = object : PresetPanelBinder.Host {
            override fun onPresetApplied(graph: EditGraph) {
                host.onGraphCommitted(graph, EditParameterKey.PRESET)
                refresh()
            }

            override fun onPresetCleared(): Boolean {
                val cleared = host.onResetAllRequested()
                if (cleared) refresh()
                return cleared
            }

            override fun onLiveTetherPresetChanged(presetId: String?) = Unit
        }
    )
    private val aspectRow = CropAspectChipRow(context, binding, ::applyAspectPreset)
    private val tabViews: Map<EditorGroup, TextView> = mapOf(
        EditorGroup.PRESET to binding.tabPreset,
        EditorGroup.GEOMETRY to binding.tabGeometry,
        EditorGroup.TONE to binding.tabTone,
        EditorGroup.COLOR to binding.tabColor,
        EditorGroup.DETAIL to binding.tabDetail,
        EditorGroup.PORTRAIT to binding.tabPortrait,
        EditorGroup.AI to binding.tabAi
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
    private var cloudPanelVisible = false

    init {
        tabViews.forEach { (group, view) -> view.setOnClickListener { selectGroup(group) } }
        setupDial()
        liquifyPanel.setVisible(false)
        colorPanels.showFor(null)
        detailPanels.showFor(null)
        effectsPanels.showFor(null)
        portraitPanels.showFor(null)
        subjectPanels.setVisible(false)
        binding.inpaintPanelView.visibility = View.GONE
        cloudPanelVisible = false
        applyCloudPanelVisibility()
        presetPanels.setVisible(false)
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
        detailPanels.refresh(graph)
        effectsPanels.refresh(graph)
        portraitPanels.refresh(graph)
        subjectPanels.refresh(graph)
        presetPanels.refresh(graph)
        rotateFlipPanel.refresh(graph)
        refreshHeaderReadout(graph)
        aspectRow.setVisible(currentTool == EditorTool.CROP)
        aspectRow.refresh(lockedAspect, host.previewAspect())
        applyCloudPanelVisibility()
    }

    /** Selects a category tab and, when it has tools, its first sub-tool. */
    fun selectGroup(group: EditorGroup) {
        currentGroup = group
        tabViews.forEach { (candidate, view) -> bindTab(view, candidate == group) }
        val tools = availableTools(group)
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

    /**
     * Returns the sub-tools of [group] the host may currently offer.
     *
     * The cloud chip is filtered out while the P4 switch is off, so a disabled cloud feature never
     * leaks into the AI group's chip row.
     */
    private fun availableTools(group: EditorGroup): List<EditorToolSpec> =
        EditorToolCatalog.toolsFor(group).filter { spec ->
            spec.tool != EditorTool.AI_CLOUD || host.isCloudToolAvailable()
        }

    /**
     * Shows or hides the tier-1 cloud panel.
     *
     * Called by [cn.devcxl.photosync.editor.ui.EditorCloudController] on every refresh, where `false`
     * means the P4 switch is off; the panel only becomes visible when the cloud tool is also the
     * active sub-tool.
     */
    fun setCloudPanelVisible(visible: Boolean) {
        cloudPanelVisible = visible
        applyCloudPanelVisibility()
    }

    /**
     * Re-reads the P4 switch, for example after the user returns from the settings screen.
     *
     * When cloud was switched off while its tool was active, the AI group falls back to the first
     * tool the host still offers so the drawer never shows an empty chip row.
     */
    fun refreshCloudAvailability() {
        if (currentTool == EditorTool.AI_CLOUD && !host.isCloudToolAvailable()) {
            selectGroup(EditorGroup.AI)
        } else if (currentGroup == EditorGroup.AI) {
            chipRow.build(availableTools(EditorGroup.AI))
        }
        cloudPanelVisible = currentTool == EditorTool.AI_CLOUD && host.isCloudToolAvailable()
        refresh()
    }

    private fun applyCloudPanelVisibility() {
        val visible = cloudPanelVisible &&
            currentTool == EditorTool.AI_CLOUD &&
            host.isCloudToolAvailable()
        binding.cloudPanelView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun handleSubPanelEdited(graph: EditGraph, key: EditParameterKey) {
        refreshHeaderReadout(graph)
        host.onGraphEdited(graph, key)
    }

    private fun handleSubPanelCommitted(graph: EditGraph, key: EditParameterKey) {
        host.onGraphCommitted(graph, key)
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
        detailPanels.showFor(null)
        effectsPanels.showFor(null)
        portraitPanels.showFor(null)
        subjectPanels.setVisible(false)
        binding.inpaintPanelView.visibility = View.GONE
        cloudPanelVisible = false
        applyCloudPanelVisibility()
        presetPanels.setVisible(false)
        rotateFlipPanel.setVisible(false)
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
        if (spec.tool == EditorTool.PRESET) {
            // "无预设" clears the out-of-graph liquify grid too, so it must go through the shared
            // history coordinator instead of a plain parameter commit.
            host.onResetAllRequested()
            return
        }
        commit(spec.parameterKey) { spec.reset(it) }
    }

    private fun configureTierOneControl(spec: EditorToolSpec) {
        cloudPanelVisible = spec.controlStyle == EditorControlStyle.CLOUD
        applyCloudPanelVisibility()
        liquifyPanel.setVisible(spec.controlStyle == EditorControlStyle.LIQUIFY)
        colorPanels.showFor(spec.controlStyle)
        detailPanels.showFor(spec.controlStyle)
        effectsPanels.showFor(spec.controlStyle)
        portraitPanels.showFor(spec.controlStyle)
        subjectPanels.setVisible(spec.controlStyle == EditorControlStyle.SUBJECT)
        binding.inpaintPanelView.visibility =
            if (spec.controlStyle == EditorControlStyle.INPAINT) View.VISIBLE else View.GONE
        presetPanels.setVisible(spec.controlStyle == EditorControlStyle.PRESET)
        rotateFlipPanel.setVisible(spec.controlStyle == EditorControlStyle.ROTATE_FLIP)
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
