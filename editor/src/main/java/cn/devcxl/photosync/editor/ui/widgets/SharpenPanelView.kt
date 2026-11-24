package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.SharpenOps

/**
 * Tier-1 controls for Y-channel sharpening: Amount (`0..150`), Radius (`0.5..3.0` full-resolution
 * pixels), Threshold (`0..100`) and Masking (`0..100`) [DialSeekBar] rows.
 */
class SharpenPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the sharpening bundle changes or a row drag completes. */
    interface Listener {
        /** Raised when [sharpen] changes from user input. */
        fun onSharpenChanged(sharpen: SharpenOps)

        /** Raised when the user releases the active row. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on sharpening changes and release. */
    var listener: Listener? = null

    private var current: SharpenOps = SharpenOps()

    private val amountRow = createRow(
        min = 0.0f,
        max = 150.0f,
        labelRes = R.string.editor_sharpen_amount,
        formatRes = R.string.editor_value_unsigned_fmt
    ) { current.copy(amount = it) }

    private val radiusRow = LabeledDialRow.createDecimal(
        context = context,
        labelRes = R.string.editor_sharpen_radius,
        min = MIN_RADIUS_PX,
        max = MAX_RADIUS_PX,
        step = RADIUS_STEP_PX,
        zeroSnap = 0.0f,
        formatRes = R.string.editor_value_radius_fmt,
        onChanged = { value ->
            current = current.copy(radius = value)
            listener?.onSharpenChanged(current)
        },
        onStopTracking = { listener?.onStopTrackingTouch() }
    )

    private val thresholdRow = createRow(
        min = 0.0f,
        max = 100.0f,
        labelRes = R.string.editor_sharpen_threshold,
        formatRes = R.string.editor_value_unsigned_fmt
    ) { current.copy(threshold = it) }

    private val maskingRow = createRow(
        min = 0.0f,
        max = 100.0f,
        labelRes = R.string.editor_sharpen_masking,
        formatRes = R.string.editor_value_unsigned_fmt
    ) { current.copy(masking = it) }

    init {
        orientation = VERTICAL
        addView(amountRow.rowView)
        addView(radiusRow.rowView)
        addView(thresholdRow.rowView)
        addView(maskingRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [sharpen]. */
    fun setSharpenOps(sharpen: SharpenOps) {
        current = sharpen
        refreshUi()
    }

    private fun createRow(
        min: Float,
        max: Float,
        labelRes: Int,
        formatRes: Int,
        updater: (Int) -> SharpenOps
    ): LabeledDialRow = LabeledDialRow.create(
        context = context,
        labelRes = labelRes,
        min = min,
        max = max,
        zeroSnap = 0.0f,
        formatRes = formatRes,
        onChanged = { value ->
            current = updater(value)
            listener?.onSharpenChanged(current)
        },
        onStopTracking = { listener?.onStopTrackingTouch() }
    )

    private fun refreshUi() {
        amountRow.setValue(current.amount)
        radiusRow.setFloatValue(current.radius)
        thresholdRow.setValue(current.threshold)
        maskingRow.setValue(current.masking)
    }

    private companion object {
        private const val MIN_RADIUS_PX = 0.5f
        private const val MAX_RADIUS_PX = 3.0f
        private const val RADIUS_STEP_PX = 0.1f
    }
}
