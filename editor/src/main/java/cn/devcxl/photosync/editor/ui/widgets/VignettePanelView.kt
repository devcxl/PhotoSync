package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.VignetteOps

/**
 * Tier-1 controls for the post-crop vignette: Amount (`-100..100`), Midpoint (`0..100`),
 * Feather (`0..100`) and Roundness (`-100..100`) [DialSeekBar] rows.
 */
class VignettePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the vignette parameters change or a slider drag completes. */
    interface Listener {
        /** Raised when [vignette] changes from user slider input. */
        fun onVignetteChanged(vignette: VignetteOps)

        /** Raised when the user releases the active vignette slider. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on vignette slider changes and release. */
    var listener: Listener? = null

    private var currentVignette: VignetteOps = VignetteOps()

    private val amountRow = signedRow(R.string.editor_vignette_amount) {
        currentVignette.copy(amount = it)
    }
    private val midpointRow = createRow(
        labelRes = R.string.editor_vignette_midpoint,
        min = 0.0f,
        zeroSnap = 0.0f,
        formatRes = R.string.editor_value_unsigned_fmt
    ) { currentVignette.copy(midpoint = it) }
    private val featherRow = createRow(
        labelRes = R.string.editor_vignette_feather,
        min = 0.0f,
        zeroSnap = 0.0f,
        formatRes = R.string.editor_value_unsigned_fmt
    ) { currentVignette.copy(feather = it) }
    private val roundnessRow = signedRow(R.string.editor_vignette_roundness) {
        currentVignette.copy(roundness = it)
    }

    init {
        orientation = VERTICAL
        addView(amountRow.rowView)
        addView(midpointRow.rowView)
        addView(featherRow.rowView)
        addView(roundnessRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [vignette]. */
    fun setVignetteOps(vignette: VignetteOps) {
        currentVignette = vignette
        refreshUi()
    }

    private fun signedRow(labelRes: Int, updater: (Int) -> VignetteOps): LabeledDialRow =
        createRow(
            labelRes = labelRes,
            min = -MAX_VALUE,
            zeroSnap = 2.0f,
            formatRes = R.string.editor_value_signed_fmt,
            updater = updater
        )

    private fun createRow(
        labelRes: Int,
        min: Float,
        zeroSnap: Float,
        formatRes: Int,
        updater: (Int) -> VignetteOps
    ): LabeledDialRow = LabeledDialRow.create(
        context = context,
        labelRes = labelRes,
        min = min,
        max = MAX_VALUE,
        zeroSnap = zeroSnap,
        formatRes = formatRes,
        onChanged = { value ->
            currentVignette = updater(value)
            listener?.onVignetteChanged(currentVignette)
        },
        onStopTracking = { listener?.onStopTrackingTouch() }
    )

    private fun refreshUi() {
        amountRow.setValue(currentVignette.amount)
        midpointRow.setValue(currentVignette.midpoint)
        featherRow.setValue(currentVignette.feather)
        roundnessRow.setValue(currentVignette.roundness)
    }

    private companion object {
        private const val MAX_VALUE = 100.0f
    }
}
