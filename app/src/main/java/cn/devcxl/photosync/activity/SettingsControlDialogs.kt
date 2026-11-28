package cn.devcxl.photosync.activity

import kotlin.math.roundToInt

import android.app.Activity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.R

/** Darkroom-styled pickers used by the unified settings page for enums and bounded integers. */
internal object SettingsControlDialogs {

    /** Shows a single-choice list; the dialog closes before [onSelect] writes the new value. */
    fun showChoice(
        activity: Activity,
        titleRes: Int,
        labels: List<CharSequence>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                dialog.dismiss()
                onSelect(which)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Shows a slider over [range], labelling the live value through [formatter]. */
    fun showRange(
        activity: Activity,
        titleRes: Int,
        range: IntRange,
        current: Int,
        formatter: (Int) -> CharSequence,
        onValue: (Int) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val horizontalPadding = (24 * density).roundToInt()
        val topPadding = (8 * density).roundToInt()
        var selected = current.coerceIn(range.first, range.last)
        val valueText = TextView(activity).apply {
            text = formatter(selected)
            textSize = 18f
            setTextColor(ContextCompat.getColor(activity, R.color.darkroom_text_primary))
        }
        val seekBar = SeekBar(activity).apply {
            max = range.last - range.first
            progress = selected - range.first
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selected = range.first + progress
                valueText.text = formatter(selected)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, topPadding, horizontalPadding, 0)
            addView(valueText)
            addView(seekBar)
        }
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.settings_dialog_confirm) { _, _ -> onValue(selected) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
