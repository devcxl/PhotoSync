package cn.devcxl.photosync.adapter

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

import cn.devcxl.photosync.R

/**
 * Controls the glass pill stacked above the pager's viewers.
 *
 * The same pill explains a pending decode and a permanent "preview unavailable" state: the
 * spinner distinguishes "still working" from "nothing will arrive", and callers never show the
 * spinner for the latter so the page cannot look like it loads forever.
 */
internal class LoadingOverlay internal constructor(
    val view: LinearLayout,
    private val spinner: ProgressBar,
    private val label: TextView
) {
    /** Shows the pill with a spinner and [labelRes]. */
    fun showLoading(labelRes: Int) {
        label.setText(labelRes)
        spinner.visibility = View.VISIBLE
        view.visibility = View.VISIBLE
    }

    /** Shows the pill without a spinner, for a terminal message such as a missing preview. */
    fun showMessage(labelRes: Int) {
        label.setText(labelRes)
        spinner.visibility = View.GONE
        view.visibility = View.VISIBLE
    }

    /** Hides the pill entirely. */
    fun hide() {
        view.visibility = View.GONE
    }
}

/**
 * Builds the glass loading pill the pager stacks above both viewers.
 *
 * Kept out of [PhotoPagerAdapter] because it is pure view chrome: a spinner and a label, sized in
 * dp, hidden until a bind decides the viewer needs it.
 */
internal fun createLoadingOverlay(context: Context, labelView: TextView): LoadingOverlay {
    val density = context.resources.displayMetrics.density
    val dp = { value: Int -> (value * density).toInt() }
    val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            marginEnd = dp(8)
        }
    }
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_glass_pill)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        visibility = View.GONE
        addView(spinner)
        addView(labelView)
    }
    return LoadingOverlay(container, spinner, labelView)
}
