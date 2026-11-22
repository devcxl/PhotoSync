package cn.devcxl.photosync.activity

import android.content.Context
import android.view.View
import android.widget.TextView

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
        /** A preset (or `无预设`) was tapped for the photo at [photoId]. */
        fun onQuickPresetSelected(photoId: Long, graph: EditGraph)

        /** The `⚡ 联机套用` toggle changed state; [presetId] is null when turned off. */
        fun onLiveTetherToggled(presetId: String?)

        /** A short copy was requested for the current photo. */
        fun onQuickCopyRequested()

        /** A long copy (mask checklist) was requested for the current photo. */
        fun onQuickCopyWithMaskRequested()

        /** A paste was requested for the current photo. */
        fun onQuickPasteRequested()

        /** The user wants to enter/leave multi-select mode. */
        fun onMultiSelectToggled()

        /** Graph of the pager's current page, or a neutral graph when it was never edited. */
        fun currentGraph(): EditGraph

        /** Id of the pager's current page, or `null` when the library is empty. */
        fun currentPhotoId(): Long?
    }

    private val density = context.resources.displayMetrics.density
    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    private var presets: List<UserPresetRecord> = emptyList()
    private var livePresetId: String? = null
    private var collapsed: Boolean = true
    private var selectionMode: Boolean = false

    init {
        binding.quickStripToggleHandle.setOnClickListener { setCollapsed(!collapsed) }
        binding.quickLiveTetherToggle.setOnClickListener {
            host.onLiveTetherToggled(if (livePresetId == null) activePresetId() else null)
        }
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
        binding.quickPasteButton.isEnabled = EditClipboard.hasContent
        binding.quickPasteButton.alpha = if (EditClipboard.hasContent) 1.0f else DISABLED_ALPHA
    }

    /** Syncs the `多选` button with the adapter's selection mode. */
    fun renderSelectionMode(selectionMode: Boolean) {
        this.selectionMode = selectionMode
        binding.quickMultiSelectButton.setText(
            if (selectionMode) R.string.quick_strip_select_done else R.string.quick_strip_select
        )
        binding.quickMultiSelectButton.setBackgroundResource(
            if (selectionMode) R.drawable.bg_toggle_pill_on else R.drawable.bg_toggle_pill
        )
    }

    /** Returns the preset id currently marked as the `LiveTetherPreset`. */
    fun activePresetId(): String? = livePresetId

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
        if (preset.id == livePresetId) "⚡ ${preset.name}" else preset.name

    private fun addChip(
        container: android.widget.LinearLayout,
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
        container.addView(chip)
    }

    /**
     * Resolves the graph a chip applies and hands it to the host.
     *
     * A corrupt preset row is reported and skipped: applying half a look would be worse than not
     * applying one, and the gallery has no undo stack to recover from it.
     */
    private fun onChipTapped(preset: UserPresetRecord?) {
        val photoId = host.currentPhotoId()
        if (photoId == null) return
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

    /**
     * Marks the chip matching the graph.
     *
     * There is no "active preset" column in the database — a look is just parameters — so the nearest
     * match wins by comparing the encoded preset axes against the current graph.
     */
    private fun markSelectedChip(currentGraph: EditGraph) {
        val container = binding.quickPresetChipContainer
        val neutral = PresetGraphMerger.hasNeutralPresetAxes(currentGraph)
        val encoded = EditGraphJsonCodec.encode(PresetGraphMerger.resetPresetAxes(currentGraph))
        for (index in 0 until container.childCount) {
            val chip = container.getChildAt(index) as? TextView ?: continue
            val presetId = chip.tag as? String
            val preset = presets.firstOrNull { it.id == presetId }
            val selected = if (preset == null) {
                neutral
            } else {
                EditGraphJsonCodec.encode(
                    PresetGraphMerger.applyPreset(
                        EditGraphJsonCodec.decode(preset.graphSubsetJson) ?: EditGraph(),
                        EditGraph()
                    )
                ) == encoded
            }
            QuickChipFactory.applySelection(context, chip, selected)
            chip.alpha = if (selected) 1.0f else 0.75f
        }
    }

    /** Re-renders after a paste or a selection change so the `粘贴参数` button dims correctly. */
    fun onClipboardChanged() {
        binding.quickPasteButton.isEnabled = EditClipboard.hasContent
        binding.quickPasteButton.alpha = if (EditClipboard.hasContent) 1.0f else DISABLED_ALPHA
    }

    private companion object {
        const val CHIP_HEIGHT_DP = 28.0f
        const val CHIP_PADDING_DP = 11.0f
        const val DISABLED_ALPHA = 0.4f
    }
}
