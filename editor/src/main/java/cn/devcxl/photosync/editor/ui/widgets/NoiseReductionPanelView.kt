package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.NoiseReductionOps

/**
 * Tier-1 controls for noise reduction: Luma (`0..100`), Chroma (`0..100`) and Detail preservation
 * (`0..100`) [DialSeekBar] rows.
 */
class NoiseReductionPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the noise-reduction bundle changes or a row drag completes. */
    interface Listener {
        /** Raised when [noiseReduction] changes from user input. */
        fun onNoiseReductionChanged(noiseReduction: NoiseReductionOps)

        /** Raised when the user releases the active row. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on noise-reduction changes and release. */
    var listener: Listener? = null

    private var current: NoiseReductionOps = NoiseReductionOps()

    private val lumaRow = createRow(R.string.editor_nr_luma) { current.copy(luma = it) }
    private val chromaRow = createRow(R.string.editor_nr_chroma) { current.copy(chroma = it) }
    private val detailRow = createRow(R.string.editor_nr_detail) { current.copy(detail = it) }

    init {
        orientation = VERTICAL
        addView(lumaRow.rowView)
        addView(chromaRow.rowView)
        addView(detailRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [noiseReduction]. */
    fun setNoiseReductionOps(noiseReduction: NoiseReductionOps) {
        current = noiseReduction
        refreshUi()
    }

    private fun createRow(
        labelRes: Int,
        updater: (Int) -> NoiseReductionOps
    ): LabeledDialRow = LabeledDialRow.create(
        context = context,
        labelRes = labelRes,
        min = 0.0f,
        max = 100.0f,
        zeroSnap = 0.0f,
        formatRes = R.string.editor_value_unsigned_fmt,
        onChanged = { value ->
            current = updater(value)
            listener?.onNoiseReductionChanged(current)
        },
        onStopTracking = { listener?.onStopTrackingTouch() }
    )

    private fun refreshUi() {
        lumaRow.setValue(current.luma)
        chromaRow.setValue(current.chroma)
        detailRow.setValue(current.detail)
    }
}
