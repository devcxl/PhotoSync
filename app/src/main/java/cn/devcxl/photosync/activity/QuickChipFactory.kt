package cn.devcxl.photosync.activity

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.content.ContextCompat

import cn.devcxl.photosync.R

/**
 * Builds the pill chips the quick preset strip and its action row share, so every chip keeps the same
 * height, padding and selected/unselected treatment.
 *
 * `:editor` keeps its own factory internal, and duplicating ~20 lines is cheaper than widening the
 * editor module's API surface for a view helper.
 */
internal object QuickChipFactory {

    /** Creates an unselected chip labelled with [label]. */
    fun create(
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
            setTextColor(ContextCompat.getColor(context, R.color.darkroom_text_primary))
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
                if (selected) R.color.accent_blue else R.color.darkroom_surface_elevated
            )
        )
    }
}
