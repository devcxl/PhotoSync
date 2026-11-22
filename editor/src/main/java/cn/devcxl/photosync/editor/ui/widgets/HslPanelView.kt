package cn.devcxl.photosync.editor.ui.widgets

import java.util.Locale
import kotlin.math.roundToInt

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

    interface Listener {
        fun onHslChanged(hsl: HslOps, fromUser: Boolean)
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

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val bandViews = mutableMapOf<Band, TextView>()
    private val hueDial: DialSeekBar
    private val satDial: DialSeekBar
    private val lumaDial: DialSeekBar
    private val hueValue: TextView
    private val satValue: TextView
    private val lumaValue: TextView

    private var currentHsl: HslOps = HslOps()
    private var activeBand: Band = Band.RED

    init {
        orientation = VERTICAL
        addView(buildBandRow())
        val (hRow, hDial, hVal) = buildSliderRow(R.string.editor_hsl_hue) { shift, v ->
            shift.copy(hue = v)
        }
        val (sRow, sDial, sVal) = buildSliderRow(R.string.editor_hsl_saturation) { shift, v ->
            shift.copy(saturation = v)
        }
        val (lRow, lDial, lVal) = buildSliderRow(R.string.editor_hsl_luminance) { shift, v ->
            shift.copy(luminance = v)
        }
        hueDial = hDial
        satDial = sDial
        lumaDial = lDial
        hueValue = hVal
        satValue = sVal
        lumaValue = lVal
        addView(hRow)
        addView(sRow)
        addView(lRow)
        refreshUi()
    }

    /** Updates the panel with [hsl] from the active edit graph. */
    fun setHslOps(hsl: HslOps) {
        currentHsl = hsl
        refreshUi()
    }

    private fun buildBandRow(): HorizontalScrollView {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        Band.entries.forEach { band ->
            val chip = TextView(context).apply {
                setText(band.labelRes)
                textSize = 11.0f
                gravity = Gravity.CENTER
                val padH = (10.0f * density).toInt()
                setPadding(padH, 0, padH, 0)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (26.0f * density).toInt()).apply {
                    marginEnd = (6.0f * density).toInt()
                }
                setTextColor(ContextCompat.getColor(context, R.color.editor_text_primary))
                setOnClickListener {
                    activeBand = band
                    refreshUi()
                }
            }
            bandViews[band] = chip
            row.addView(chip)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(row)
        }
    }

    private fun buildSliderRow(
        labelRes: Int,
        updater: (HslBandShift, Int) -> HslBandShift
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
            layoutParams = LayoutParams((36.0f * density).toInt(), LayoutParams.WRAP_CONTENT)
        }
        val dial = DialSeekBar(context).apply {
            minValue = -100.0f
            maxValue = 100.0f
            stepSize = 1.0f
            zeroSnapThreshold = 2.0f
            layoutParams = LayoutParams(0, (36.0f * density).toInt(), 1.0f)
            listener = object : DialSeekBar.OnDialChangeListener {
                override fun onValueChanged(value: Float, fromUser: Boolean) {
                    if (!fromUser) return
                    val updated = updater(shiftFor(currentHsl, activeBand), value.roundToInt())
                    currentHsl = withShift(currentHsl, activeBand, updated)
                    valueText.text = formatSigned(value.roundToInt())
                    this@HslPanelView.listener?.onHslChanged(currentHsl, fromUser = true)
                }

                override fun onStopTrackingTouch() {
                    this@HslPanelView.listener?.onStopTrackingTouch()
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
        bandViews.forEach { (band, chip) ->
            val selected = band == activeBand
            val swatch = ContextCompat.getColor(context, band.colorRes)
            val bgColor = ContextCompat.getColor(
                context,
                if (selected) R.color.editor_surface_elevated else R.color.editor_bg
            )
            chip.background = GradientDrawable().apply {
                cornerRadius = 13.0f * density
                setColor(bgColor)
                setStroke(((if (selected) 2.0f else 1.0f) * density).toInt(), swatch)
            }
            chip.alpha = if (selected || !shiftFor(currentHsl, band).isIdentity()) 1.0f else 0.72f
        }
        val shift = shiftFor(currentHsl, activeBand)
        hueDial.setValue(shift.hue.toFloat())
        satDial.setValue(shift.saturation.toFloat())
        lumaDial.setValue(shift.luminance.toFloat())
        hueValue.text = formatSigned(shift.hue)
        satValue.text = formatSigned(shift.saturation)
        lumaValue.text = formatSigned(shift.luminance)
    }

    private fun formatSigned(value: Int): String =
        String.format(Locale.US, context.getString(R.string.editor_value_signed_fmt), value.toFloat())

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
}
