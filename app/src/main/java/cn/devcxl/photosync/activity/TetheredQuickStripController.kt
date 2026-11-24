package cn.devcxl.photosync.activity

import android.annotation.SuppressLint
import android.content.Context
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

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
 * Unified collapsible preset strip above the filmstrip:
 * - Picking a preset applies it to the current photo (with an inline circular spinner while
 *   rendering) AND activates it for incoming tethered photos automatically.
 * - Picking `无预设` clears the current photo's preset axes AND disables tethered auto-apply so
 *   new shots display straight as originals.
 * - The hold-to-compare icon button appears only on edited photos and shows the unedited original
 *   strictly while held down.
 */
@SuppressLint("ClickableViewAccessibility")
internal class TetheredQuickStripController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val owner: LifecycleOwner,
    private val host: Host
) {

    /** Callbacks the activity implements for unified preset & compare actions. */
    interface Host {
        fun onPresetSelected(photoId: Long?, graph: EditGraph?, presetId: String?, presetName: String?)
        fun onCompareChanged(comparing: Boolean)
        fun currentGraph(): EditGraph
        fun currentPhotoId(): Long?
        fun canCompareCurrent(): Boolean

        /** Warms the portrait analysis of the photo on screen so a portrait preset applies faster. */
        fun prewarmPortrait()
    }

    private val density = context.resources.displayMetrics.density
    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    private var presets: List<UserPresetRecord> = emptyList()
    private var chips: List<QuickPresetChip> = emptyList()
    private var activePresetId: String? = null
    private var collapsed: Boolean = true
    private var applyingChipActive: Boolean = false
    private var applyingPresetId: String? = null

    init {
        binding.quickStripToggleHandle.setOnClickListener { setCollapsed(!collapsed, animate = true) }
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
        if (!collapsed) host.prewarmPortrait()
        renderToggleHandle()
    }

    /** Re-renders chips, the preset toggle pill and the hold-to-compare button. */
    fun refresh(currentGraph: EditGraph) {
        rebuildChips(currentGraph)
        renderToggleHandle()
        renderCompareButton()
    }

    /** Shows or clears an inline circular spinner on the preset chip currently rendering. */
    fun setApplyingPreset(active: Boolean, presetId: String?) {
        applyingChipActive = active
        applyingPresetId = if (active) presetId else null
        markSelectedChip(host.currentGraph())
    }

    private fun renderCompareButton() {
        binding.quickCompareButton.visibility =
            if (host.canCompareCurrent()) View.VISIBLE else View.GONE
    }

    private fun renderToggleHandle() {
        val activePreset = activePresetId?.let { id -> presets.firstOrNull { it.id == id } }
        val handle = binding.quickStripToggleHandle
        if (!collapsed) {
            handle.setText(R.string.quick_strip_collapse)
            handle.setBackgroundResource(R.drawable.bg_filmstrip_handle)
            handle.setTextColor(ContextCompat.getColor(context, R.color.darkroom_text_primary))
            return
        }
        if (activePreset != null) {
            handle.text = context.getString(R.string.quick_strip_expand_active, activePreset.name)
            handle.setBackgroundResource(R.drawable.bg_toggle_pill_on)
            handle.setTextColor(ContextCompat.getColor(context, R.color.toggle_on_text))
        } else {
            handle.setText(R.string.quick_strip_expand)
            handle.setBackgroundResource(R.drawable.bg_filmstrip_handle)
            handle.setTextColor(ContextCompat.getColor(context, R.color.darkroom_text_primary))
        }
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
                        renderToggleHandle()
                    }
                }
                launch {
                    currentStore.observeLiveTetherPresetId().collect { id ->
                        activePresetId = id
                        markSelectedChip(host.currentGraph())
                        renderToggleHandle()
                    }
                }
            }
        }
    }

    private fun rebuildChips(currentGraph: EditGraph) {
        val container = binding.quickPresetChipContainer
        container.removeAllViews()
        val built = mutableListOf<QuickPresetChip>()
        built += addChip(container, context.getString(R.string.quick_strip_no_preset), null)
        presets.forEach { preset -> built += addChip(container, preset.name, preset) }
        chips = built
        markSelectedChip(currentGraph)
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
        container.addView(chip.root)
        return chip
    }

    private fun onChipTapped(preset: UserPresetRecord?) {
        if (applyingChipActive) return
        val photoId = host.currentPhotoId()
        val graph = if (photoId == null) {
            null
        } else if (preset == null) {
            PresetGraphMerger.resetPresetAxes(host.currentGraph())
        } else {
            resolvePresetGraph(preset) ?: return
        }
        host.onPresetSelected(photoId, graph, preset?.id, preset?.name)
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
        ) ?: activePresetId
        val neutral = matched == null && PresetGraphMerger.hasNeutralPresetAxes(currentGraph)
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
    }
}
