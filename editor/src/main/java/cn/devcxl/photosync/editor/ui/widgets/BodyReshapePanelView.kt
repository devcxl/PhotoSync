package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.BodyOps

/**
 * Tier-1 controls for Body Reshaping: Slim Waist and Lengthen Legs.
 *
 * The waist and hip guide lines are dragged directly on the canvas ([BodyGuideOverlayView]); this
 * panel only exposes the two strengths, so the persisted `portrait.body.waistY/hipY` values survive
 * slider changes untouched.
 */
class BodyReshapePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the body bundle changes or a row drag completes. */
    interface Listener {
        /** Raised when [body] changes from user input. */
        fun onBodyChanged(body: BodyOps)

        /** Raised when the user releases the active row. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on body changes and release. */
    var listener: Listener? = null

    private var current: BodyOps = BodyOps()

    private val waistRow = createRow(R.string.editor_body_waist) { current.copy(waist = it) }

    private val legsRow = createRow(R.string.editor_body_legs) { current.copy(legs = it) }

    init {
        orientation = VERTICAL
        addView(waistRow.rowView)
        addView(legsRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [body] without touching the persisted guide lines. */
    fun setBodyOps(body: BodyOps) {
        current = body
        refreshUi()
    }

    private fun createRow(labelRes: Int, updater: (Int) -> BodyOps): LabeledDialRow =
        LabeledDialRow.create(
            context = context,
            labelRes = labelRes,
            min = -100.0f,
            max = 100.0f,
            zeroSnap = 1.0f,
            formatRes = R.string.editor_value_signed_fmt,
            onChanged = { value ->
                current = updater(value)
                listener?.onBodyChanged(current)
            },
            onStopTracking = { listener?.onStopTrackingTouch() }
        )

    private fun refreshUi() {
        waistRow.setValue(current.waist)
        legsRow.setValue(current.legs)
    }
}
