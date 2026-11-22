package cn.devcxl.photosync.editor.ui.widgets

import java.util.Locale
import kotlin.math.roundToInt

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.SplitToningOps
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * Tier-1 controls for OKLab split toning: Highlights / Shadows mode toggle plus Hue (`0..360°`),
 * Saturation (`0..100`) and Balance (`-100..100`) [DialSeekBar] rows.
 */
class SplitToningPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSplitToningChanged(split: SplitToningOps, fromUser: Boolean)
        fun onStopTrackingTouch()
    }

    private enum class ToneZone { HIGHLIGHTS, SHADOWS }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val zoneChips = mutableMapOf<ToneZone, TextView>()
    private val hueDial: DialSeekBar
    private val satDial: DialSeekBar
    private val balanceDial: DialSeekBar
    private val hueValue: TextView
    private val satValue: TextView
    private val balanceValue: TextView

    private var currentSplit: SplitToningOps = SplitToningOps()
    private var activeZone: ToneZone = ToneZone.HIGHLIGHTS

    init {
        orientation = VERTICAL
        addView(buildZoneRow())
        val (hRow, hDial, hVal) = buildRow(
            R.string.editor_split_hue,
            0.0f,
            360.0f,
            0.0f
        ) { split, v ->
            if (activeZone == ToneZone.HIGHLIGHTS) split.copy(hiHue = v) else split.copy(shHue = v)
        }
        val (sRow, sDial, sVal) = buildRow(
            R.string.editor_split_saturation,
            0.0f,
            100.0f,
            0.0f
        ) { split, v ->
            if (activeZone == ToneZone.HIGHLIGHTS) split.copy(hiSat = v) else split.copy(shSat = v)
        }
        val (bRow, bDial, bVal) = buildRow(
            R.string.editor_split_balance,
            -100.0f,
            100.0f,
            2.0f
        ) { split, v ->
            split.copy(balance = v)
        }
        hueDial = hDial
        satDial = sDial
        balanceDial = bDial
        hueValue = hVal
        satValue = sVal
        balanceValue = bVal
        addView(hRow)
        addView(sRow)
        addView(bRow)
        refreshUi()
    }

    /** Updates the displayed [split] parameters. */
    fun setSplitToningOps(split: SplitToningOps) {
        currentSplit = split
        refreshUi()
    }

    private fun buildZoneRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        ToneZone.entries.forEach { zone ->
            val chip = PanelChipFactory.create(
                context = context,
                labelRes = if (zone == ToneZone.HIGHLIGHTS) {
                    R.string.editor_split_highlights
                } else {
                    R.string.editor_split_shadows
                },
                heightPx = (26.0f * density).toInt(),
                textSizeSp = 11.0f,
                paddingPx = (12.0f * density).toInt()
            )
            chip.setOnClickListener {
                activeZone = zone
                refreshUi()
            }
            zoneChips[zone] = chip
            row.addView(chip)
        }
        return row
    }

    private fun buildRow(
        labelRes: Int,
        min: Float,
        max: Float,
        zeroSnap: Float,
        updater: (SplitToningOps, Int) -> SplitToningOps
    ): Triple<LinearLayout, DialSeekBar, TextView> {
        val label = TextView(context).apply {
            setText(labelRes)
            textSize = 11.0f
            setTextColor(ContextCompat.getColor(context, R.color.editor_text_secondary))
            layoutParams = LayoutParams((40.0f * density).toInt(), LayoutParams.WRAP_CONTENT)
        }
        val valueText = TextView(context).apply {
            textSize = 11.0f
            gravity = Gravity.END
            setTextColor(ContextCompat.getColor(context, R.color.editor_accent_blue))
            layoutParams = LayoutParams((40.0f * density).toInt(), LayoutParams.WRAP_CONTENT)
        }
        val dial = DialSeekBar(context).apply {
            minValue = min
            maxValue = max
            stepSize = 1.0f
            zeroSnapThreshold = zeroSnap
            layoutParams = LayoutParams(0, (36.0f * density).toInt(), 1.0f)
            listener = object : DialSeekBar.OnDialChangeListener {
                override fun onValueChanged(value: Float, fromUser: Boolean) {
                    if (!fromUser) return
                    currentSplit = updater(currentSplit, value.roundToInt())
                    refreshValuesOnly()
                    this@SplitToningPanelView.listener?.onSplitToningChanged(currentSplit, fromUser = true)
                }

                override fun onStopTrackingTouch() {
                    this@SplitToningPanelView.listener?.onStopTrackingTouch()
                }
            }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2.0f * density).toInt()
            }
            addView(label)
            addView(dial)
            addView(valueText)
        }
        return Triple(row, dial, valueText)
    }

    private fun refreshUi() {
        zoneChips.forEach { (zone, chip) ->
            val selected = zone == activeZone
            PanelChipFactory.applySelection(context, chip, selected)
            chip.alpha = if (selected) 1.0f else 0.75f
        }
        val hue = if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.hiHue else currentSplit.shHue
        val sat = if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.hiSat else currentSplit.shSat
        hueDial.setValue(hue.toFloat())
        satDial.setValue(sat.toFloat())
        balanceDial.setValue(currentSplit.balance.toFloat())
        refreshValuesOnly()
    }

    private fun refreshValuesOnly() {
        val hue = if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.hiHue else currentSplit.shHue
        val sat = if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.hiSat else currentSplit.shSat
        hueValue.text = String.format(Locale.US, context.getString(R.string.editor_value_plain_degrees_fmt), hue.toFloat())
        satValue.text = String.format(Locale.US, context.getString(R.string.editor_value_unsigned_fmt), sat.toFloat())
        balanceValue.text = String.format(
            Locale.US,
            context.getString(R.string.editor_value_signed_fmt),
            currentSplit.balance.toFloat()
        )
    }
}
