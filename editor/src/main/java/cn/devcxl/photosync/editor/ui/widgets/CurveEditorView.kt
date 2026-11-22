package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.CurvePoint
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * 4-channel Fritsch-Carlson curve editor panel (`RGB / R / G / B` selector + [CurveCanvasView] +
 * per-channel reset action).
 */
class CurveEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when the curve knots change or a drag completes. */
    interface Listener {
        /** Raised when any channel of [curve] changes interactively. */
        fun onCurveChanged(curve: CurveOps)

        /** Raised when the user releases the active knot or resets a channel. */
        fun onStopTrackingTouch()
    }

    /** Listener notified on interactive curve edits and touch release. */
    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val channelChips = mutableMapOf<CurveChannel, TextView>()
    private val curveCanvas: CurveCanvasView
    private var currentOps: CurveOps = CurveOps()
    private var activeChannel: CurveChannel = CurveChannel.RGB

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        CurveChannel.entries.forEach { channel ->
            val chip = PanelChipFactory.create(
                context = context,
                labelRes = channelLabel(channel),
                heightPx = (CHIP_HEIGHT_DP * density).toInt(),
                textSizeSp = 11.0f,
                paddingPx = (CHIP_PADDING_DP * density).toInt()
            )
            chip.setOnClickListener { selectChannel(channel) }
            channelChips[channel] = chip
            header.addView(chip)
        }
        header.addView(Space(context).apply { layoutParams = LayoutParams(0, 0, 1.0f) })
        val resetBtn = TextView(context).apply {
            setText(R.string.editor_curve_reset)
            textSize = 11.0f
            gravity = Gravity.CENTER
            minHeight = (CHIP_HEIGHT_DP * density).toInt()
            setTextColor(ContextCompat.getColor(context, R.color.editor_text_secondary))
            setPadding((10.0f * density).toInt(), 0, (6.0f * density).toInt(), 0)
            setOnClickListener { resetActiveChannel() }
        }
        header.addView(resetBtn)
        addView(header)

        curveCanvas = CurveCanvasView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (CANVAS_HEIGHT_DP * density).toInt()).apply {
                topMargin = (6.0f * density).toInt()
            }
            listener = object : CurveCanvasView.Listener {
                override fun onChannelPointsChanged(channel: CurveChannel, points: List<CurvePoint>) {
                    currentOps = updateChannel(currentOps, channel, points)
                    this@CurveEditorView.listener?.onCurveChanged(currentOps)
                }

                override fun onStopTrackingTouch() {
                    this@CurveEditorView.listener?.onStopTrackingTouch()
                }
            }
        }
        addView(curveCanvas)
        refreshChannelChips()
    }

    /** Updates the displayed curves and knots to reflect [curve]. */
    fun setCurveOps(curve: CurveOps) {
        currentOps = curve
        curveCanvas.bindState(currentOps, activeChannel)
        refreshChannelChips()
    }

    private fun selectChannel(channel: CurveChannel) {
        activeChannel = channel
        curveCanvas.bindState(currentOps, activeChannel)
        refreshChannelChips()
    }

    private fun resetActiveChannel() {
        currentOps = updateChannel(currentOps, activeChannel, CurveOps.DEFAULT_LINEAR_CURVE)
        curveCanvas.bindState(currentOps, activeChannel)
        refreshChannelChips()
        listener?.onCurveChanged(currentOps)
        listener?.onStopTrackingTouch()
    }

    private fun refreshChannelChips() {
        channelChips.forEach { (ch, view) ->
            val selected = ch == activeChannel
            PanelChipFactory.applySelection(context, view, selected)
            view.alpha = if (selected) 1.0f else 0.75f
        }
    }

    private fun updateChannel(ops: CurveOps, channel: CurveChannel, points: List<CurvePoint>): CurveOps =
        when (channel) {
            CurveChannel.RGB -> ops.copy(rgb = points)
            CurveChannel.R -> ops.copy(r = points)
            CurveChannel.G -> ops.copy(g = points)
            CurveChannel.B -> ops.copy(b = points)
        }

    private fun channelLabel(channel: CurveChannel): Int = when (channel) {
        CurveChannel.RGB -> R.string.editor_curve_channel_rgb
        CurveChannel.R -> R.string.editor_curve_channel_r
        CurveChannel.G -> R.string.editor_curve_channel_g
        CurveChannel.B -> R.string.editor_curve_channel_b
    }

    private companion object {
        private const val CHIP_HEIGHT_DP = 32.0f
        private const val CHIP_PADDING_DP = 10.0f
        private const val CANVAS_HEIGHT_DP = 148.0f
    }
}
