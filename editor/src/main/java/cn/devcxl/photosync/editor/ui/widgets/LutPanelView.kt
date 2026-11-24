package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.LutRef
import cn.devcxl.photosync.editor.lut.CubeLutInfo
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * Tier-1 controls for external `.cube` 3D LUTs: a SAF import button, one selectable chip per
 * installed LUT (plus a “none” chip) and a `0..100` blend strength [DialSeekBar].
 */
class LutPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised by the LUT panel. */
    interface Listener {
        /** The user asked to pick a `.cube` document through the system file chooser. */
        fun onLutImportRequested()

        /** A LUT chip was tapped, or the “none” chip when [id] is `null`. */
        fun onLutSelected(id: String?)

        /** The blend strength changed from user slider input. */
        fun onStrengthChanged(strength: Int)

        /** The user released the strength slider. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on LUT selection and strength changes. */
    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val chipContainer = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val chipViews = mutableMapOf<String?, TextView>()
    private var installedLuts: List<CubeLutInfo> = emptyList()
    private var selectedId: String? = null
    private var strength: Int = DEFAULT_STRENGTH

    private val strengthRow = LabeledDialRow.create(
        context = context,
        labelRes = R.string.editor_lut_strength,
        min = 0.0f,
        max = 100.0f,
        zeroSnap = 0.0f,
        formatRes = R.string.editor_value_unsigned_fmt,
        onChanged = { value ->
            strength = value
            listener?.onStrengthChanged(value)
        },
        onStopTracking = { listener?.onStopTrackingTouch() }
    )

    init {
        orientation = VERTICAL
        addView(buildImportRow())
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                addView(chipContainer)
            }
        )
        addView(strengthRow.rowView)
        rebuildChips()
    }

    /** Rebuilds the chip row when the installed LUT library changed. */
    internal fun setInstalledLuts(luts: List<CubeLutInfo>) {
        if (luts == installedLuts) return
        installedLuts = luts
        rebuildChips()
    }

    /** Highlights the active [ref] and syncs the strength slider, disabling it when unbounded. */
    fun setLut(ref: LutRef?) {
        selectedId = ref?.id
        strength = ref?.strength ?: DEFAULT_STRENGTH
        strengthRow.setValue(strength)
        refreshSelection()
    }

    private fun buildImportRow(): View {
        val importChip = PanelChipFactory.createWithText(
            context = context,
            label = context.getString(R.string.editor_lut_import),
            heightPx = (CHIP_HEIGHT_DP * density).toInt(),
            textSizeSp = 11.0f,
            paddingPx = (CHIP_PADDING_DP * density).toInt()
        )
        importChip.setOnClickListener { listener?.onLutImportRequested() }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(importChip)
        }
    }

    private fun rebuildChips() {
        chipContainer.removeAllViews()
        chipViews.clear()
        addChip(null, context.getString(R.string.editor_lut_none))
        installedLuts.forEach { lut -> addChip(lut.id, lut.displayName) }
        refreshSelection()
    }

    private fun addChip(key: String?, label: CharSequence) {
        val chip = PanelChipFactory.createWithText(
            context = context,
            label = label,
            heightPx = (CHIP_HEIGHT_DP * density).toInt(),
            textSizeSp = 11.0f,
            paddingPx = (CHIP_PADDING_DP * density).toInt()
        )
        chip.setOnClickListener { listener?.onLutSelected(key) }
        chipViews[key] = chip
        chipContainer.addView(chip)
    }

    private fun refreshSelection() {
        chipViews.forEach { (key, chip) ->
            val selected = key == selectedId
            PanelChipFactory.applySelection(context, chip, selected)
            chip.alpha = if (selected) 1.0f else 0.75f
        }
        strengthRow.setEnabled(selectedId != null)
    }

    private companion object {
        private const val CHIP_HEIGHT_DP = 30.0f
        private const val CHIP_PADDING_DP = 13.0f
        private const val DEFAULT_STRENGTH = 100
    }
}
