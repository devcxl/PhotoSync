package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.ReshapeOps

/**
 * Tier-1 controls for the six face-reshaping axes: Slim Face, Enlarge Eyes, Slim Nose, Chin,
 * Forehead and Smile, each a signed `-100..100` [LabeledDialRow].
 *
 * The values are normalized strengths only; the 478-point MLS solve happens off the UI thread when
 * the graph changes.
 */
class FaceReshapePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the reshape bundle changes or a row drag completes. */
    interface Listener {
        /** Raised when [reshape] changes from user input. */
        fun onReshapeChanged(reshape: ReshapeOps)

        /** Raised when the user releases the active row. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on reshape changes and release. */
    var listener: Listener? = null

    private var current: ReshapeOps = ReshapeOps()

    private val faceRow = createRow(R.string.editor_reshape_face) { current.copy(face = it) }

    private val eyesRow = createRow(R.string.editor_reshape_eyes) { current.copy(eyes = it) }

    private val noseRow = createRow(R.string.editor_reshape_nose) { current.copy(nose = it) }

    private val chinRow = createRow(R.string.editor_reshape_chin) { current.copy(chin = it) }

    private val foreheadRow = createRow(R.string.editor_reshape_forehead) {
        current.copy(forehead = it)
    }

    private val smileRow = createRow(R.string.editor_reshape_smile) { current.copy(smile = it) }

    init {
        orientation = VERTICAL
        addView(faceRow.rowView)
        addView(eyesRow.rowView)
        addView(noseRow.rowView)
        addView(chinRow.rowView)
        addView(foreheadRow.rowView)
        addView(smileRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [reshape]. */
    fun setReshapeOps(reshape: ReshapeOps) {
        current = reshape
        refreshUi()
    }

    private fun createRow(labelRes: Int, updater: (Int) -> ReshapeOps): LabeledDialRow =
        LabeledDialRow.create(
            context = context,
            labelRes = labelRes,
            min = -100.0f,
            max = 100.0f,
            zeroSnap = 1.0f,
            formatRes = R.string.editor_value_signed_fmt,
            onChanged = { value ->
                current = updater(value)
                listener?.onReshapeChanged(current)
            },
            onStopTracking = { listener?.onStopTrackingTouch() }
        )

    private fun refreshUi() {
        faceRow.setValue(current.face)
        eyesRow.setValue(current.eyes)
        noseRow.setValue(current.nose)
        chinRow.setValue(current.chin)
        foreheadRow.setValue(current.forehead)
        smileRow.setValue(current.smile)
    }
}
