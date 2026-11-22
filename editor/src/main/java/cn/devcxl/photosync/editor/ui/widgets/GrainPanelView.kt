package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.GrainOps

/**
 * Tier-1 controls for the Pass D film grain: Amount (`0..100`), Size (`0..100`) and
 * Roughness (`0..100`) [DialSeekBar] rows.
 */
class GrainPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the grain parameters change or a slider drag completes. */
    interface Listener {
        /** Raised when [grain] changes from user slider input. */
        fun onGrainChanged(grain: GrainOps)

        /** Raised when the user releases the active grain slider. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on grain slider changes and release. */
    var listener: Listener? = null

    private var currentGrain: GrainOps = GrainOps()

    private val amountRow = createRow(R.string.editor_grain_amount) { currentGrain.copy(amount = it) }
    private val sizeRow = createRow(R.string.editor_grain_size) { currentGrain.copy(size = it) }
    private val roughnessRow = createRow(R.string.editor_grain_roughness) {
        currentGrain.copy(roughness = it)
    }

    init {
        orientation = VERTICAL
        addView(amountRow.rowView)
        addView(sizeRow.rowView)
        addView(roughnessRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [grain]. */
    fun setGrainOps(grain: GrainOps) {
        currentGrain = grain
        refreshUi()
    }

    private fun createRow(labelRes: Int, updater: (Int) -> GrainOps): LabeledDialRow =
        LabeledDialRow.create(
            context = context,
            labelRes = labelRes,
            min = MIN_VALUE,
            max = MAX_VALUE,
            zeroSnap = 0.0f,
            formatRes = R.string.editor_value_unsigned_fmt,
            onChanged = { value ->
                currentGrain = updater(value)
                listener?.onGrainChanged(currentGrain)
            },
            onStopTracking = { listener?.onStopTrackingTouch() }
        )

    private fun refreshUi() {
        amountRow.setValue(currentGrain.amount)
        sizeRow.setValue(currentGrain.size)
        roughnessRow.setValue(currentGrain.roughness)
    }

    private companion object {
        private const val MIN_VALUE = 0.0f
        private const val MAX_VALUE = 100.0f
    }
}
