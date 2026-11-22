package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.SkinOps

/**
 * Tier-1 controls for portrait skin smoothing: Smooth, Texture and Even Tone rows.
 *
 * The rows only publish a new [SkinOps]; the undo bucket and the render request belong to the
 * panels binder, so the view stays a dumb control block.
 */
class SkinPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the skin bundle changes or a row drag completes. */
    interface Listener {
        /** Raised when [skin] changes from user input. */
        fun onSkinChanged(skin: SkinOps)

        /** Raised when the user releases the active row. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on skin changes and release. */
    var listener: Listener? = null

    private var current: SkinOps = SkinOps()

    private val smoothRow = createRow(R.string.editor_skin_smooth) { current.copy(smooth = it) }

    private val textureRow = createRow(R.string.editor_skin_texture) { current.copy(texture = it) }

    private val evenToneRow = createRow(R.string.editor_skin_even_tone) {
        current.copy(evenTone = it)
    }

    init {
        orientation = VERTICAL
        addView(smoothRow.rowView)
        addView(textureRow.rowView)
        addView(evenToneRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [skin]. */
    fun setSkinOps(skin: SkinOps) {
        current = skin
        refreshUi()
    }

    private fun createRow(labelRes: Int, updater: (Int) -> SkinOps): LabeledDialRow =
        LabeledDialRow.create(
            context = context,
            labelRes = labelRes,
            min = 0.0f,
            max = 100.0f,
            zeroSnap = 0.0f,
            formatRes = R.string.editor_value_unsigned_fmt,
            onChanged = { value ->
                current = updater(value)
                listener?.onSkinChanged(current)
            },
            onStopTracking = { listener?.onStopTrackingTouch() }
        )

    private fun refreshUi() {
        smoothRow.setValue(current.smooth)
        textureRow.setValue(current.texture)
        evenToneRow.setValue(current.evenTone)
    }
}
