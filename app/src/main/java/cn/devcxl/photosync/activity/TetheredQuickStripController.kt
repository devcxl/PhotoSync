package cn.devcxl.photosync.activity

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.databinding.ActivityMainBinding
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.PresetGraphMerger

/**
 * The collapsible `快捷预设与联机操作栏` above the filmstrip: preset chips, the `无预设` reset, the
 * `⚡ 联机套用` toggle and the compact copy/paste/select actions — all reachable without opening the
 * editor.
 */
internal class TetheredQuickStripController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val owner: LifecycleOwner,
    private val host: Host
) {

    /** Callbacks the activity implements for quick-strip actions. */
    interface Host {
        fun onQuickPresetSelected(photoId: Long, graph: EditGraph)
        fun onLiveTetherToggled(presetId: String?)
        fun onQuickCopyRequested()
        fun onQuickCopyWithMaskRequested()
        fun onQuickPasteRequested()
        fun onMultiSelectToggled()
        fun currentGraph(): EditGraph
        fun currentPhotoId(): Long?
    }

    private val density = context.resources.displayMetrics.density
    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    private var presets: List<UserPresetRecord> = emptyList()
    private var livePresetId: String? = null
    private var collapsed: Boolean = true

    init {
        binding.quickStripToggleHandle.setOnClickListener { setCollapsed(!collapsed) }
        binding.quickLiveTetherToggle.setOnClickListener { onLiveTetherToggleClicked() }
        binding.quickCopyButton.setOnClickListener { host.onQuickCopyRequested() }
        binding.quickCopyButton.setOnLongClickListener {
            host.onQuickCopyWithMaskRequested()
            true
        }
        binding.quickPasteButton.setOnClickListener { host.onQuickPasteRequested() }
        binding.quickMultiSelectButton.setOnClickListener { host.onMultiSelectToggled() }
        setCollapsed(true)
        observePresets()
    }

    /** Collapses or expands the strip; the toggle handle stays visible either way. */
    fun setCollapsed(collapsed: Boolean) {
        this.collapsed = collapsed
        val visible = if (collapsed) View.GONE else View.VISIBLE
        binding.quickPresetChipScroll.visibility = visible
        binding.quickActionRow.visibility = visible
        binding.quickStripToggleHandle.setText(
            if (collapsed) R.string.quick_strip_expand else R.string.quick_strip_collapse
        )
    }

    /** Re-renders the preset chips against the current photo's graph. */
    fun refresh(currentGraph: EditGraph) {
        rebuildChips(currentGraph)
        renderLiveTetherToggle()
        onClipboardChanged()
    }

    /** Syncs the multi-select icon button with the adapter's selection mode. */
    fun renderSelectionMode(selectionMode: Boolean) {
        binding.quickMultiSelectButton.setImageResource(
            if (selectionMode) R.drawable.ic_check_24 else R.drawable.ic_select_multi_24
        )
        binding.quickMultiSelectButton.contentDescription = context.getString(
            if (selectionMode) R.string.quick_strip_select_done else R.string.quick_strip_select
        )
        binding.quickMultiSelectButton.setBackgroundResource(
            if (selectionMode) R.drawable.bg_toggle_pill_on else R.drawable.bg_toggle_pill
        )
    }

    /** Re-renders after a paste or copy so `粘贴参数` updates its enabled state. */
    fun onClipboardChanged() {
        binding.quickPasteButton.isEnabled = EditClipboard.hasContent
        binding.quickPasteButton.alpha = if (EditClipboard.hasContent) 1.0f else DISABLED_ALPHA
    }

    private fun onLiveTetherToggleClicked() {
        if (livePresetId != null) {
            host.onLiveTetherToggled(null)
            return
        }
        val matching = PresetGraphMerger.matchingPresetId(
            host.currentGraph(),
            presets.map { it.id to it.graphSubsetJson }
        )
        if (matching != null) {
            host.onLiveTetherToggled(matching)
            return
        }
        if (presets.isEmpty()) {
            host.onLiveTetherToggled(null)
            return
        }
        showLivePresetPicker()
    }

    private fun showLivePresetPicker() {
        val names = presets.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.quick_strip_live_picker_title)
            .setItems(names) { _, which ->
                presets.getOrNull(which)?.let { host.onLiveTetherToggled(it.id) }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun observePresets() {
        val currentStore = store
        if (currentStore == null) {
            Timber.w("Quick preset strip unavailable: no EditStore installed")
            return
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    currentStore.observePresets().collect { list ->
                        presets = list
                        rebuildChips(host.currentGraph())
                    }
                }
                launch {
                    currentStore.observeLiveTetherPresetId().collect { id ->
                        livePresetId = id
                        rebuildChips(host.currentGraph())
                        renderLiveTetherToggle()
                    }
                }
            }
        }
    }

    private fun renderLiveTetherToggle() {
        val active = livePresetId != null
        binding.quickLiveTetherToggle.setBackgroundResource(
            if (active) R.drawable.bg_toggle_pill_on else R.drawable.bg_toggle_pill
        )
        val tint = ContextCompat.getColor(
            context,
            if (active) R.color.toggle_on_text else R.color.darkroom_text_secondary
        )
        binding.quickLiveTetherToggle.setTextColor(tint)
    }

    private fun rebuildChips(currentGraph: EditGraph) {
        val container = binding.quickPresetChipContainer
        container.removeAllViews()
        addChip(container, context.getString(R.string.quick_strip_no_preset), null)
        presets.forEach { preset -> addChip(container, labelOf(preset), preset) }
        markSelectedChip(currentGraph)
    }

    private fun labelOf(preset: UserPresetRecord): CharSequence =
        if (preset.id == livePresetId) {
            context.getString(R.string.quick_strip_live_badge, preset.name)
        } else {
            preset.name
        }

    private fun addChip(
        container: LinearLayout,
        label: CharSequence,
        preset: UserPresetRecord?
    ) {
        val chip = QuickChipFactory.create(
            context = context,
            label = label,
            heightPx = (CHIP_HEIGHT_DP * density).toInt(),
            textSizeSp = 11.0f,
            paddingPx = (CHIP_PADDING_DP * density).toInt()
        )
        chip.tag = preset?.id
        chip.setOnClickListener { onChipTapped(preset) }
        if (preset != null) {
            chip.setOnLongClickListener { view ->
                showChipMenu(preset, view)
                true
            }
        }
        container.addView(chip)
    }

    private fun showChipMenu(preset: UserPresetRecord, anchor: View) {
        val menu = PopupMenu(context, anchor)
        val isLive = preset.id == livePresetId
        val labelRes = if (isLive) R.string.quick_strip_menu_clear_live else R.string.quick_strip_menu_set_live
        menu.menu.add(0, MENU_TOGGLE_LIVE, 0, labelRes)
        menu.setOnMenuItemClickListener {
            host.onLiveTetherToggled(if (isLive) null else preset.id)
            true
        }
        menu.show()
    }

    private fun onChipTapped(preset: UserPresetRecord?) {
        val photoId = host.currentPhotoId() ?: return
        val graph = if (preset == null) {
            PresetGraphMerger.resetPresetAxes(host.currentGraph())
        } else {
            resolvePresetGraph(preset)
        }
        if (graph != null) host.onQuickPresetSelected(photoId, graph)
    }

    private fun resolvePresetGraph(preset: UserPresetRecord): EditGraph? {
        val target = EditGraphJsonCodec.decode(preset.graphSubsetJson)
        if (target == null) Timber.w("Preset %s carries corrupt JSON", preset.id)
        return target?.let { PresetGraphMerger.applyPreset(it, host.currentGraph()) }
    }

    private fun markSelectedChip(currentGraph: EditGraph) {
        val container = binding.quickPresetChipContainer
        val matched = PresetGraphMerger.matchingPresetId(
            currentGraph,
            presets.map { it.id to it.graphSubsetJson }
        )
        val neutral = PresetGraphMerger.hasNeutralPresetAxes(currentGraph)
        for (index in 0 until container.childCount) {
            val chip = container.getChildAt(index) as? TextView ?: continue
            val presetId = chip.tag as? String
            val selected = if (presetId == null) neutral else presetId == matched
            QuickChipFactory.applySelection(context, chip, selected)
            chip.alpha = if (selected) 1.0f else 0.75f
        }
    }

    private companion object {
        const val CHIP_HEIGHT_DP = 28.0f
        const val CHIP_PADDING_DP = 11.0f
        const val DISABLED_ALPHA = 0.4f
        const val MENU_TOGGLE_LIVE = 1
    }
}
