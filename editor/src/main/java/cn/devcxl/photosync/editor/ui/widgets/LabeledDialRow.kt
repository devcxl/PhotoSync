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
 * Shared `[Label] [DialSeekBar] [FormattedValue]` row used by [HslPanelView], [SplitToningPanelView],
 * [SharpenPanelView] and [NoiseReductionPanelView] so sub-panel sliders share uniform alignment,
 * step size and typography.
 */
internal class LabeledDialRow private constructor(
    private val context: Context,
    val rowView: LinearLayout,
    private val dial: DialSeekBar,
    private val valueView: TextView,
    private val formatRes: Int
) {

    /** Updates the dial position and numeric text without raising change callbacks. */
    fun setValue(value: Int) = setFloatValue(value.toFloat())

    /** Updates the dial position and decimal text without raising change callbacks. */
    fun setFloatValue(value: Float) {
        dial.setValue(value)
        valueView.text = String.format(Locale.US, context.getString(formatRes), value)
    }

    /** Enables or dims the whole row so unavailable controls read as disabled. */
    fun setEnabled(enabled: Boolean) {
        dial.isEnabled = enabled
        dial.alpha = if (enabled) 1.0f else DISABLED_ALPHA
        rowView.alpha = if (enabled) 1.0f else DISABLED_ALPHA
    }

    private fun updateValueText(value: Float) {
        valueView.text = String.format(Locale.US, context.getString(formatRes), value)
    }

    private fun attachListener(holder: LabeledDialRow, onChanged: (Float) -> Unit, onStop: () -> Unit) {
        dial.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (!fromUser) return
                holder.updateValueText(value)
                onChanged(value)
            }

            override fun onStopTrackingTouch() = onStop()
        }
    }

    companion object {
        private const val LABEL_WIDTH_DP = 40.0f
        private const val VALUE_WIDTH_DP = 40.0f
        private const val DIAL_HEIGHT_DP = 36.0f
        private const val ROW_TOP_MARGIN_DP = 2.0f
        private const val DISABLED_ALPHA = 0.4f

        /** Whole-step row whose value is delivered as a rounded [Int]. */
        fun create(
            context: Context,
            labelRes: Int,
            min: Float,
            max: Float,
            zeroSnap: Float,
            formatRes: Int,
            onChanged: (Int) -> Unit,
            onStopTracking: () -> Unit
        ): LabeledDialRow = build(
            context = context,
            labelRes = labelRes,
            min = min,
            max = max,
            step = 1.0f,
            zeroSnap = zeroSnap,
            formatRes = formatRes,
            onChanged = { onChanged(it.roundToInt()) },
            onStopTracking = onStopTracking
        )

        /** Fractional row such as the sharpening radius, whose value is delivered as a [Float]. */
        fun createDecimal(
            context: Context,
            labelRes: Int,
            min: Float,
            max: Float,
            step: Float,
            zeroSnap: Float,
            formatRes: Int,
            onChanged: (Float) -> Unit,
            onStopTracking: () -> Unit
        ): LabeledDialRow = build(
            context = context,
            labelRes = labelRes,
            min = min,
            max = max,
            step = step,
            zeroSnap = zeroSnap,
            formatRes = formatRes,
            onChanged = onChanged,
            onStopTracking = onStopTracking
        )

        private fun build(
            context: Context,
            labelRes: Int,
            min: Float,
            max: Float,
            step: Float,
            zeroSnap: Float,
            formatRes: Int,
            onChanged: (Float) -> Unit,
            onStopTracking: () -> Unit
        ): LabeledDialRow {
            val density = context.resources.displayMetrics.density
            val label = createLabel(context, labelRes, density)
            val valueText = createValueView(context, density)
            val dial = DialSeekBar(context).apply {
                minValue = min
                maxValue = max
                stepSize = step
                zeroSnapThreshold = zeroSnap
                layoutParams = LinearLayout.LayoutParams(0, (DIAL_HEIGHT_DP * density).toInt(), 1.0f)
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
            val holder = LabeledDialRow(context, row, dial, valueText, formatRes)
            holder.attachListener(holder, onChanged, onStopTracking)
            return holder
        }

        private fun createLabel(context: Context, labelRes: Int, density: Float): TextView =
            TextView(context).apply {
                setText(labelRes)
                textSize = 11.0f
                setTextColor(ContextCompat.getColor(context, R.color.editor_text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    (LABEL_WIDTH_DP * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

        private fun createValueView(context: Context, density: Float): TextView =
            TextView(context).apply {
                textSize = 11.0f
                gravity = Gravity.END
                setTextColor(ContextCompat.getColor(context, R.color.editor_accent_blue))
                layoutParams = LinearLayout.LayoutParams(
                    (VALUE_WIDTH_DP * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
    }
}
