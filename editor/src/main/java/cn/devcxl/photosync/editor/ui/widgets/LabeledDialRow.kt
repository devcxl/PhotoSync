package cn.devcxl.photosync.editor.ui.widgets

import java.util.Locale
import kotlin.math.roundToInt

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R

/**
 * Shared `[Label] [DialSeekBar] [FormattedValue]` row used by [HslPanelView] and
 * [SplitToningPanelView] so sub-panel sliders share uniform alignment, step size and typography.
 */
internal class LabeledDialRow private constructor(
    private val context: Context,
    val rowView: LinearLayout,
    private val dial: DialSeekBar,
    private val valueView: TextView,
    private val formatRes: Int
) {

    /** Updates the dial position and numeric text without raising change callbacks. */
    fun setValue(value: Int) {
        dial.setValue(value.toFloat())
        updateValueText(value)
    }

    /** Enables or dims the whole row so unavailable controls read as disabled. */
    fun setEnabled(enabled: Boolean) {
        dial.isEnabled = enabled
        dial.alpha = if (enabled) 1.0f else DISABLED_ALPHA
        rowView.alpha = if (enabled) 1.0f else DISABLED_ALPHA
    }

    private fun updateValueText(value: Int) {
        valueView.text = String.format(Locale.US, context.getString(formatRes), value.toFloat())
    }

    companion object {
        private const val LABEL_WIDTH_DP = 40.0f
        private const val VALUE_WIDTH_DP = 40.0f
        private const val DIAL_HEIGHT_DP = 36.0f
        private const val ROW_TOP_MARGIN_DP = 2.0f
        private const val DISABLED_ALPHA = 0.4f

        fun create(
            context: Context,
            labelRes: Int,
            min: Float,
            max: Float,
            zeroSnap: Float,
            formatRes: Int,
            onChanged: (Int) -> Unit,
            onStopTracking: () -> Unit
        ): LabeledDialRow {
            val density = context.resources.displayMetrics.density
            val label = TextView(context).apply {
                setText(labelRes)
                textSize = 11.0f
                setTextColor(ContextCompat.getColor(context, R.color.editor_text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    (LABEL_WIDTH_DP * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val valueText = TextView(context).apply {
                textSize = 11.0f
                gravity = Gravity.END
                setTextColor(ContextCompat.getColor(context, R.color.editor_accent_blue))
                layoutParams = LinearLayout.LayoutParams(
                    (VALUE_WIDTH_DP * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            lateinit var holder: LabeledDialRow
            val dial = DialSeekBar(context).apply {
                minValue = min
                maxValue = max
                stepSize = 1.0f
                zeroSnapThreshold = zeroSnap
                layoutParams = LinearLayout.LayoutParams(0, (DIAL_HEIGHT_DP * density).toInt(), 1.0f)
                listener = object : DialSeekBar.OnDialChangeListener {
                    override fun onValueChanged(value: Float, fromUser: Boolean) {
                        if (!fromUser) return
                        val rounded = value.roundToInt()
                        holder.updateValueText(rounded)
                        onChanged(rounded)
                    }

                    override fun onStopTrackingTouch() = onStopTracking()
                }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (ROW_TOP_MARGIN_DP * density).toInt() }
                addView(label)
                addView(dial)
                addView(valueText)
            }
            holder = LabeledDialRow(context, row, dial, valueText, formatRes)
            return holder
        }
    }
}
