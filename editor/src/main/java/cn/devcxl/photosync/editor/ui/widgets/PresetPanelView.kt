package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.view.View
import android.widget.TextView

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.databinding.EditorPresetPanelBinding
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * Renders the preset chip row inside `editor_preset_panel.xml`: the `＋ 保存当前为预设` action, the
 * `无预设` reset chip and one chip per library entry with a `⚡` badge on the active
 * `LiveTetherPreset`.
 *
 * Pure view code: it knows nothing about `EditStore`, undo history or dialogs — [PresetPanelBinder]
 * owns those. Chip text is the only state it keeps, so re-emitting the same library is a no-op.
 */
internal class PresetPanelView(
    private val context: Context,
    private val binding: EditorPresetPanelBinding,
    private val onSaveCurrent: () -> Unit,
    private val onNoPreset: () -> Unit,
    private val onPresetSelected: (UserPresetRecord) -> Unit,
    private val onPresetLongPressed: (UserPresetRecord, View) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private var lastPresets: List<UserPresetRecord> = emptyList()
    private var lastLiveId: String? = null

    init {
        binding.presetSaveCurrentChip.setOnClickListener { onSaveCurrent() }
    }

    /** Shows or hides the whole panel; the group is unreachable while hidden. */
    fun setVisible(visible: Boolean) {
        binding.presetPanelRoot.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Rebuilds the chip row for [presets], badging the entry whose id is [livePresetId].
     *
     * Rebuilding is skipped when neither argument changed, so a slider drag that refreshes the drawer
     * every frame does not re-inflate a dozen TextViews.
     */
    fun render(presets: List<UserPresetRecord>, livePresetId: String?) {
        if (presets == lastPresets && livePresetId == lastLiveId) return
        lastPresets = presets
        lastLiveId = livePresetId
        binding.presetChipContainer.removeAllViews()
        addChip(NO_PRESET_KEY, context.getString(R.string.editor_preset_none), null)
        presets.forEach { preset -> addChip(preset.id, label(preset, livePresetId), preset) }
        binding.presetEmptyHint.visibility = if (presets.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Highlights [presetId], or the `无预设` chip when it is `null`. */
    fun select(presetId: String?) {
        for (index in 0 until binding.presetChipContainer.childCount) {
            val chip = binding.presetChipContainer.getChildAt(index) as? TextView ?: continue
            val key = chip.tag as? String
            val selected = key == (presetId ?: NO_PRESET_KEY)
            PanelChipFactory.applySelection(context, chip, selected)
            chip.alpha = if (selected) 1.0f else 0.75f
        }
    }

    private fun label(preset: UserPresetRecord, livePresetId: String?): CharSequence {
        val badge = context.getString(R.string.editor_preset_live_badge)
        return if (preset.id == livePresetId) "$badge ${preset.name}" else preset.name
    }

    private fun addChip(key: String, label: CharSequence, preset: UserPresetRecord?) {
        val chip = PanelChipFactory.createWithText(
            context = context,
            label = label,
            heightPx = (CHIP_HEIGHT_DP * density).toInt(),
            textSizeSp = 11.0f,
            paddingPx = (CHIP_PADDING_DP * density).toInt()
        )
        chip.tag = key
        PanelChipFactory.applySelection(context, chip, selected = false)
        chip.setOnClickListener {
            if (preset == null) onNoPreset() else onPresetSelected(preset)
        }
        if (preset != null) {
            chip.setOnLongClickListener { view ->
                onPresetLongPressed(preset, view)
                true
            }
        }
        binding.presetChipContainer.addView(chip)
    }

    private companion object {
        /** Chip tag of the `无预设` reset chip. */
        const val NO_PRESET_KEY = "__none__"
        const val CHIP_HEIGHT_DP = 30.0f
        const val CHIP_PADDING_DP = 13.0f
    }
}
