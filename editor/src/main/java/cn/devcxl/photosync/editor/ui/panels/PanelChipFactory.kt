package cn.devcxl.photosync.editor.ui.panels

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R

/**
 * Builds the pill chips the tier-2 sub-tool rows and the liquify panel share, so every chip in the
 * drawer keeps the same height, padding and selected/unselected treatment.
 */
internal object PanelChipFactory {

    /** Creates an unselected chip labelled with [labelRes]. */
    fun create(
        context: Context,
        labelRes: Int,
        heightPx: Int,
        textSizeSp: Float,
        paddingPx: Int
    ): TextView = createWithText(
        context = context,
        label = context.getString(labelRes),
        heightPx = heightPx,
        textSizeSp = textSizeSp,
        paddingPx = paddingPx
    )

    /** Creates an unselected chip labelled with a dynamic [label], such as an imported LUT name. */
    fun createWithText(
        context: Context,
        label: CharSequence,
        heightPx: Int,
        textSizeSp: Float,
        paddingPx: Int
    ): TextView {
        val chip = TextView(context).apply {
            text = label
            textSize = textSizeSp
            gravity = Gravity.CENTER
            setPadding(paddingPx, 0, paddingPx, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                heightPx
            ).apply { marginEnd = paddingPx / 3 }
            setTextColor(ContextCompat.getColor(context, R.color.editor_text_primary))
            isClickable = true
            isFocusable = true
        }
        applySelection(context, chip, selected = false)
        return chip
    }

    /** Repaints [view] as selected (accent) or unselected (elevated surface). */
    fun applySelection(context: Context, view: TextView, selected: Boolean) {
        view.setBackgroundColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.editor_accent_blue else R.color.editor_surface_elevated
            )
        )
    }
}
