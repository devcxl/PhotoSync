package cn.devcxl.photosync.editor.ui.panels

import android.content.Context
import android.view.View
import android.widget.TextView

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding

/**
 * The tier-2b row of crop aspect-ratio lock chips (`自由 / 原比例 / 1:1 / 4:3 / 16:9`).
 *
 * Owns only the chip views and their highlight state; the crop rectangle itself is written by
 * [EditorPanelController] so all undo work keeps flowing through one place.
 */
internal class CropAspectChipRow(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val onPresetSelected: (CropAspectPreset) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val chipViews = mutableMapOf<String, TextView>()

    init {
        binding.aspectChipContainer.removeAllViews()
        EditorReadoutFormatter.presets.forEach { preset ->
            val chip = PanelChipFactory.create(
                context = context,
                labelRes = preset.labelRes,
                heightPx = (CHIP_HEIGHT_DP * density).toInt(),
                textSizeSp = 11.0f,
                paddingPx = (CHIP_PADDING_DP * density).toInt()
            )
            chip.setOnClickListener { onPresetSelected(preset) }
            chipViews[preset.key] = chip
            binding.aspectChipContainer.addView(chip)
        }
    }

    /** Shows or hides the whole chip row. */
    fun setVisible(visible: Boolean) {
        binding.aspectChipScroll.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Highlights the chip matching the current lock. */
    fun refresh(lockedAspect: Float?, frameAspect: Float) {
        val activeKey = EditorReadoutFormatter.aspectKey(lockedAspect, frameAspect)
        chipViews.forEach { (key, view) ->
            PanelChipFactory.applySelection(context, view, key == activeKey)
        }
    }

    private companion object {
        private const val CHIP_HEIGHT_DP = 26.0f
        private const val CHIP_PADDING_DP = 13.0f
    }
}
