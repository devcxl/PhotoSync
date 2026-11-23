package cn.devcxl.photosync.activity

import android.annotation.SuppressLint
import android.content.Context
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu

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
 * The collapsible `快捷预设` strip above the filmstrip:
 * - Preset chips apply immediately to the current photo with an inline circular spinner while
 *   rendering.
 * - `⚡ 联机套用` picks or disables the `LiveTetherPreset` applied to newly synced photos.
 * - The hold-to-compare icon button appears only on edited photos and previews the unedited
 *   original strictly while held down.
 */
@SuppressLint("ClickableViewAccessibility")
internal class TetheredQuickStripController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val owner: LifecycleOwner,
    private val host: Host
) {

    /** Callbacks the activity implements for quick-strip actions. */
    interface Host {
        fun onQuickPresetSelected(photoId: Long, graph: EditGraph, presetId: String?, presetName: String?)
        fun onLiveTetherToggled(presetId: String?, presetName: String?)
        fun onCompareChanged(comparing: Boolean)
        fun currentGraph(): EditGraph
        fun currentPhotoId(): Long?
        fun canCompareCurrent(): Boolean
    }

    private val density = context.resources.displayMetrics.density
    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    private var presets: List<UserPresetRecord> = emptyList()
    private var chips: List<QuickPresetChip> = emptyList()
    private var livePresetId: String? = null
    private var collapsed: Boolean = true
    private var applyingChipActive: Boolean = false
    private var applyingPresetId: String? = null

    init {
        binding.quickStripToggleHandle.setOnClickListener { setCollapsed(!collapsed, animate = true) }
        binding.quickLiveTetherToggle.setOnClickListener { onLiveTetherToggleClicked() }
        binding.quickCompareButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    host.onCompareChanged(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    host.onCompareChanged(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> false
            }
        }
        setCollapsed(true, animate = false)
        observePresets()
    }

    /** Collapses or expands the preset chip row with a smooth transition when requested. */
    fun setCollapsed(collapsed: Boolean, animate: Boolean = true) {
        this.collapsed = collapsed
        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.quickPresetStrip,
                AutoTransition().setDuration(EXPAND_ANIM_MS)
            )
        }
        binding.quickPresetChipScroll.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.quickStripToggleHandle.setText(
            if (collapsed) R.string.quick_strip_expand else R.string.quick_strip_collapse
        )
    }

    /** Re-renders chips, the `⚡ 联机套用` pill and the hold-to-compare button. */
    fun refresh(currentGraph: EditGraph) {
        rebuildChips(currentGraph)
        renderLiveTetherToggle()
        renderCompareButton()
    }

    /** Shows or clears an inline circular spinner on the preset chip currently rendering. */
    fun setApplyingPreset(active: Boolean, presetId: String?) {
        applyingChipActive = active
        applyingPresetId = if (active) presetId else null
        markSelectedChip(host.currentGraph())
    }

    private fun renderCompareButton() {
        val visible = host.canCompareCurrent()
        binding.quickCompareButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun onLiveTetherToggleClicked() {
        if (presets.isEmpty()) {
            host.onLiveTetherToggled(null, null)
            return
        }
        showLivePresetPicker()
    }

    private fun showLivePresetPicker() {
        val isActive = livePresetId != null
        val labels = mutableListOf<String>()
        if (isActive) {
            labels += context.getString(R.string.quick_strip_live_picker_turn_off)
        }
        presets.forEach { preset ->
            labels += if (preset.id == livePresetId) {
                context.getString(R.string.quick_strip_live_badge, preset.name)
            } else {
                preset.name
            }
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.quick_strip_live_picker_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (isActive && which == 0) {
                    host.onLiveTetherToggled(null, null)
                } else {
                    val presetIndex = if (isActive) which - 1 else which
                    presets.getOrNull(presetIndex)?.let { host.onLiveTetherToggled(it.id, it.name) }
                }
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
                        renderLiveTetherToggle()
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
        val activePreset = livePresetId?.let { id -> presets.firstOrNull { it.id == id } }
        val active = livePresetId != null
        binding.quickLiveTetherToggle.text = if (activePreset != null) {
            context.getString(R.string.quick_strip_live_tether_active, activePreset.name)
        } else {
            context.getString(R.string.quick_strip_live_tether)
        }
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
        val built = mutableListOf<QuickPresetChip>()
        built += addChip(container, context.getString(R.string.quick_strip_no_preset), null)
        presets.forEach { preset -> built += addChip(container, labelOf(preset), preset) }
        chips = built
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
    ): QuickPresetChip {
        val chip = QuickChipFactory.create(
            context = context,
            label = label,
            presetId = preset?.id,
            heightPx = (CHIP_HEIGHT_DP * density).toInt(),
            textSizeSp = 11.0f,
            paddingPx = (CHIP_PADDING_DP * density).toInt()
        )
        chip.root.setOnClickListener { onChipTapped(preset) }
        if (preset != null) {
            chip.root.setOnLongClickListener { view ->
                showChipMenu(preset, view)
                true
            }
        }
        container.addView(chip.root)
        return chip
    }

    private fun showChipMenu(preset: UserPresetRecord, anchor: View) {
        val menu = PopupMenu(context, anchor)
        val isLive = preset.id == livePresetId
        val labelRes = if (isLive) R.string.quick_strip_menu_clear_live else R.string.quick_strip_menu_set_live
        menu.menu.add(0, MENU_TOGGLE_LIVE, 0, labelRes)
        menu.setOnMenuItemClickListener {
            host.onLiveTetherToggled(
                if (isLive) null else preset.id,
                if (isLive) null else preset.name
            )
            true
        }
        menu.show()
    }

    private fun onChipTapped(preset: UserPresetRecord?) {
        if (applyingChipActive) return
        val photoId = host.currentPhotoId() ?: return
        val graph = if (preset == null) {
            PresetGraphMerger.resetPresetAxes(host.currentGraph())
        } else {
            resolvePresetGraph(preset)
        }
        if (graph != null) {
            host.onQuickPresetSelected(photoId, graph, preset?.id, preset?.name)
        }
    }

    private fun resolvePresetGraph(preset: UserPresetRecord): EditGraph? {
        val target = EditGraphJsonCodec.decode(preset.graphSubsetJson)
        if (target == null) Timber.w("Preset %s carries corrupt JSON", preset.id)
        return target?.let { PresetGraphMerger.applyPreset(it, host.currentGraph()) }
    }

    private fun markSelectedChip(currentGraph: EditGraph) {
        val matched = PresetGraphMerger.matchingPresetId(
            currentGraph,
            presets.map { it.id to it.graphSubsetJson }
        )
        val neutral = PresetGraphMerger.hasNeutralPresetAxes(currentGraph)
        for (chip in chips) {
            val selected = if (chip.presetId == null) neutral else chip.presetId == matched
            val loading = applyingChipActive && chip.presetId == applyingPresetId
            QuickChipFactory.applySelection(context, chip, selected = selected, loading = loading)
            chip.root.isEnabled = !applyingChipActive
        }
    }

    private companion object {
        const val CHIP_HEIGHT_DP = 28.0f
        const val CHIP_PADDING_DP = 11.0f
        const val EXPAND_ANIM_MS = 160L
        const val MENU_TOGGLE_LIVE = 1
    }
}
