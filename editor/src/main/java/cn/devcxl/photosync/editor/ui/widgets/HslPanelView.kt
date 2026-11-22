package cn.devcxl.photosync.editor.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.HslBandShift
import cn.devcxl.photosync.editor.domain.HslOps

/**
 * 8-band OKLCH HSL mixer panel: colored band chips (`Red .. Magenta`) plus Hue / Saturation /
 * Luminance [DialSeekBar] controls with live value read-outs.
 */
class HslPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when any HSL band shift changes or a slider drag completes. */
    interface Listener {
        /** Raised when [hsl] changes from user slider input. */
        fun onHslChanged(hsl: HslOps)

        /** Raised when the user releases the active HSL slider. */
        fun onStopTrackingTouch()
    }

    private enum class Band(val labelRes: Int, val colorRes: Int) {
        RED(R.string.editor_hsl_band_red, R.color.editor_hsl_red),
        ORANGE(R.string.editor_hsl_band_orange, R.color.editor_hsl_orange),
        YELLOW(R.string.editor_hsl_band_yellow, R.color.editor_hsl_yellow),
        GREEN(R.string.editor_hsl_band_green, R.color.editor_hsl_green),
        AQUA(R.string.editor_hsl_band_aqua, R.color.editor_hsl_aqua),
        BLUE(R.string.editor_hsl_band_blue, R.color.editor_hsl_blue),
        PURPLE(R.string.editor_hsl_band_purple, R.color.editor_hsl_purple),
        MAGENTA(R.string.editor_hsl_band_magenta, R.color.editor_hsl_magenta)
    }

    /** Listener notified on HSL slider changes and release. */
    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val bandViews = mutableMapOf<Band, TextView>()
    private val bandDrawables = mutableMapOf<Band, GradientDrawable>()
    private val selectedBgColor = ContextCompat.getColor(context, R.color.editor_surface_elevated)
    private val idleBgColor = ContextCompat.getColor(context, R.color.editor_bg)

    private val hueRow = createBandRow(R.string.editor_hsl_hue) { shift, v -> shift.copy(hue = v) }
    private val satRow = createBandRow(R.string.editor_hsl_saturation) { shift, v -> shift.copy(saturation = v) }
    private val lumaRow = createBandRow(R.string.editor_hsl_luminance) { shift, v -> shift.copy(luminance = v) }

    private var currentHsl: HslOps = HslOps()
    private var activeBand: Band = Band.RED

    init {
        orientation = VERTICAL
        addView(buildBandRow())
        addView(hueRow.rowView)
        addView(satRow.rowView)
        addView(lumaRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [hsl]. */
    fun setHslOps(hsl: HslOps) {
        currentHsl = hsl
        refreshUi()
    }

    private fun createBandRow(
        labelRes: Int,
        updater: (HslBandShift, Int) -> HslBandShift
    ): LabeledDialRow = LabeledDialRow.create(
        context = context,
        labelRes = labelRes,
        min = SLIDER_MIN,
        max = SLIDER_MAX,
        zeroSnap = SLIDER_ZERO_SNAP,
        formatRes = R.string.editor_value_signed_fmt,
        onChanged = { value ->
            val updated = updater(shiftFor(currentHsl, activeBand), value)
            currentHsl = withShift(currentHsl, activeBand, updated)
            listener?.onHslChanged(currentHsl)
        },
        onStopTracking = { listener?.onStopTrackingTouch() }
    )

    private fun buildBandRow(): HorizontalScrollView {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        Band.entries.forEach { band ->
            val drawable = GradientDrawable().apply { cornerRadius = CHIP_CORNER_DP * density }
            val chip = TextView(context).apply {
                setText(band.labelRes)
                textSize = 11.0f
                gravity = Gravity.CENTER
                val padH = (CHIP_PAD_H_DP * density).toInt()
                setPadding(padH, 0, padH, 0)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (CHIP_HEIGHT_DP * density).toInt()).apply {
                    marginEnd = (CHIP_MARGIN_END_DP * density).toInt()
                }
                setTextColor(ContextCompat.getColor(context, R.color.editor_text_primary))
                background = drawable
                setOnClickListener {
                    activeBand = band
                    refreshUi()
                }
            }
            bandViews[band] = chip
            bandDrawables[band] = drawable
            row.addView(chip)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(row)
        }
    }

    private fun refreshUi() {
        bandViews.forEach { (band, chip) ->
            val selected = band == activeBand
            val swatch = ContextCompat.getColor(context, band.colorRes)
            val drawable = bandDrawables.getValue(band)
            drawable.setColor(if (selected) selectedBgColor else idleBgColor)
            drawable.setStroke(((if (selected) 2.0f else 1.0f) * density).toInt(), swatch)
            chip.alpha = if (selected || !shiftFor(currentHsl, band).isIdentity()) 1.0f else 0.72f
        }
        val shift = shiftFor(currentHsl, activeBand)
        hueRow.setValue(shift.hue)
        satRow.setValue(shift.saturation)
        lumaRow.setValue(shift.luminance)
    }

    private fun shiftFor(hsl: HslOps, band: Band): HslBandShift = when (band) {
        Band.RED -> hsl.red
        Band.ORANGE -> hsl.orange
        Band.YELLOW -> hsl.yellow
        Band.GREEN -> hsl.green
        Band.AQUA -> hsl.aqua
        Band.BLUE -> hsl.blue
        Band.PURPLE -> hsl.purple
        Band.MAGENTA -> hsl.magenta
    }

    private fun withShift(hsl: HslOps, band: Band, shift: HslBandShift): HslOps = when (band) {
        Band.RED -> hsl.copy(red = shift)
        Band.ORANGE -> hsl.copy(orange = shift)
        Band.YELLOW -> hsl.copy(yellow = shift)
        Band.GREEN -> hsl.copy(green = shift)
        Band.AQUA -> hsl.copy(aqua = shift)
        Band.BLUE -> hsl.copy(blue = shift)
        Band.PURPLE -> hsl.copy(purple = shift)
        Band.MAGENTA -> hsl.copy(magenta = shift)
    }

    private companion object {
        private const val SLIDER_MIN = -100.0f
        private const val SLIDER_MAX = 100.0f
        private const val SLIDER_ZERO_SNAP = 2.0f
        private const val CHIP_HEIGHT_DP = 32.0f
        private const val CHIP_PAD_H_DP = 11.0f
        private const val CHIP_MARGIN_END_DP = 6.0f
        private const val CHIP_CORNER_DP = 16.0f
    }
}
