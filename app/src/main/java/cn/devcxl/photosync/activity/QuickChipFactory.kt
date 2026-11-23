package cn.devcxl.photosync.activity

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

import androidx.core.content.ContextCompat

import cn.devcxl.photosync.R

/**
 * One quick-preset pill chip with an inline circular loading spinner shown while a preset is
 * rendering in the background.
 */
internal data class QuickPresetChip(
    val root: LinearLayout,
    val spinner: ProgressBar,
    val label: TextView,
    val presetId: String?
)

/**
 * Builds the pill chips of the quick preset strip so every chip keeps consistent height, padding,
 * selected styling and inline loading animation.
 */
internal object QuickChipFactory {

    /** Creates an unselected chip labelled with [label] and an inline hidden spinner. */
    fun create(
        context: Context,
        label: CharSequence,
        presetId: String?,
        heightPx: Int,
        textSizeSp: Float,
        paddingPx: Int
    ): QuickPresetChip {
        val density = context.resources.displayMetrics.density
        val spinnerSize = (SPINNER_SIZE_DP * density).toInt()
        val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.darkroom_text_primary)
            )
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                marginEnd = (SPINNER_GAP_DP * density).toInt()
            }
        }
        val textView = TextView(context).apply {
            text = label
            textSize = textSizeSp
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.darkroom_text_primary))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(paddingPx, 0, paddingPx, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                heightPx
            ).apply { marginEnd = paddingPx / 2 }
            isClickable = true
            isFocusable = true
            addView(spinner)
            addView(textView)
        }
        val chip = QuickPresetChip(
            root = container,
            spinner = spinner,
            label = textView,
            presetId = presetId
        )
        applySelection(context, chip, selected = false, loading = false)
        return chip
    }

    /** Repaints [chip] with its selected and inline-loading states. */
    fun applySelection(
        context: Context,
        chip: QuickPresetChip,
        selected: Boolean,
        loading: Boolean
    ) {
        chip.spinner.visibility = if (loading) View.VISIBLE else View.GONE
        chip.root.setBackgroundResource(
            if (selected || loading) R.drawable.bg_toggle_pill_on else R.drawable.bg_toggle_pill
        )
        chip.label.setTextColor(
            ContextCompat.getColor(
                context,
                if (selected || loading) R.color.toggle_on_text else R.color.darkroom_text_primary
            )
        )
        chip.root.alpha = if (selected || loading) 1.0f else IDLE_ALPHA
    }

    private const val SPINNER_SIZE_DP = 13.0f
    private const val SPINNER_GAP_DP = 5.0f
    private const val IDLE_ALPHA = 0.82f
}
