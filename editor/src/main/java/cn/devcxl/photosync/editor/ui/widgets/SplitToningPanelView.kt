package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.OklabColorMath
import cn.devcxl.photosync.editor.domain.SplitToningOps
import cn.devcxl.photosync.editor.ui.panels.PanelChipFactory

/**
 * Tier-1 controls for OKLab split toning: Highlights / Shadows mode toggle with live OKLCH hue
 * swatch plus Hue (`0..360°`), Saturation (`0..100`) and Balance (`-100..100`) [DialSeekBar] rows.
 */
class SplitToningPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callbacks raised when split-toning parameters change or a slider drag completes. */
    interface Listener {
        /** Raised when [split] changes from user slider input. */
        fun onSplitToningChanged(split: SplitToningOps)

        /** Raised when the user releases the active split-toning slider. */
        fun onStopTrackingTouch()
    }

    private enum class ToneZone { HIGHLIGHTS, SHADOWS }

    /** Listener notified on split-toning slider changes and release. */
    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val zoneChips = mutableMapOf<ToneZone, TextView>()
    private val swatchDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val swatchPreview = View(context).apply {
        val size = (SWATCH_SIZE_DP * density).toInt()
        layoutParams = LayoutParams(size, size).apply { marginStart = (8.0f * density).toInt() }
        background = swatchDrawable
    }

    private val hueRow = createRow(
        0.0f, 360.0f, 0.0f, R.string.editor_split_hue, R.string.editor_value_plain_degrees_fmt
    ) {
        if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.copy(hiHue = it) else currentSplit.copy(shHue = it)
    }
    private val satRow = createRow(
        0.0f, 100.0f, 0.0f, R.string.editor_split_saturation, R.string.editor_value_unsigned_fmt
    ) {
        if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.copy(hiSat = it) else currentSplit.copy(shSat = it)
    }
    private val balanceRow = createRow(
        -100.0f, 100.0f, 2.0f, R.string.editor_split_balance, R.string.editor_value_signed_fmt
    ) {
        currentSplit.copy(balance = it)
    }

    private var currentSplit: SplitToningOps = SplitToningOps()
    private var activeZone: ToneZone = ToneZone.HIGHLIGHTS

    init {
        orientation = VERTICAL
        addView(buildZoneRow())
        addView(hueRow.rowView)
        addView(satRow.rowView)
        addView(balanceRow.rowView)
        refreshUi()
    }

    /** Updates the panel controls to display [split]. */
    fun setSplitToningOps(split: SplitToningOps) {
        currentSplit = split
        refreshUi()
    }

    private fun createRow(
        min: Float,
        max: Float,
        zeroSnap: Float,
        labelRes: Int,
        formatRes: Int,
        updater: (Int) -> SplitToningOps
    ): LabeledDialRow = LabeledDialRow.create(
        context = context,
        labelRes = labelRes,
        min = min,
        max = max,
        zeroSnap = zeroSnap,
        formatRes = formatRes,
        onChanged = { value ->
            currentSplit = updater(value)
            updateHueSwatch()
            listener?.onSplitToningChanged(currentSplit)
        },
        onStopTracking = { listener?.onStopTrackingTouch() }
    )

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
                heightPx = (CHIP_HEIGHT_DP * density).toInt(),
                textSizeSp = 11.0f,
                paddingPx = (CHIP_PADDING_DP * density).toInt()
            )
            chip.setOnClickListener {
                activeZone = zone
                refreshUi()
            }
            zoneChips[zone] = chip
            row.addView(chip)
        }
        row.addView(swatchPreview)
        return row
    }

    private fun refreshUi() {
        zoneChips.forEach { (zone, chip) ->
            val selected = zone == activeZone
            PanelChipFactory.applySelection(context, chip, selected)
            chip.alpha = if (selected) 1.0f else 0.75f
        }
        val hue = if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.hiHue else currentSplit.shHue
        val sat = if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.hiSat else currentSplit.shSat
        hueRow.setValue(hue)
        satRow.setValue(sat)
        balanceRow.setValue(currentSplit.balance)
        updateHueSwatch()
    }

    private fun updateHueSwatch() {
        val hue = if (activeZone == ToneZone.HIGHLIGHTS) currentSplit.hiHue else currentSplit.shHue
        val rad = (hue * PI / 180.0).toFloat()
        val rgb = FloatArray(3)
        OklabColorMath.oklabToLinearSrgb(floatArrayOf(0.70f, 0.14f * cos(rad), 0.14f * sin(rad)), rgb)
        val r = (ExposureShaderMath.encodeSrgb(rgb[0].coerceIn(0.0f, 1.0f)) * 255.0f).roundToInt().coerceIn(0, 255)
        val g = (ExposureShaderMath.encodeSrgb(rgb[1].coerceIn(0.0f, 1.0f)) * 255.0f).roundToInt().coerceIn(0, 255)
        val b = (ExposureShaderMath.encodeSrgb(rgb[2].coerceIn(0.0f, 1.0f)) * 255.0f).roundToInt().coerceIn(0, 255)
        swatchDrawable.setColor(Color.rgb(r, g, b))
    }

    private companion object {
        private const val CHIP_HEIGHT_DP = 32.0f
        private const val CHIP_PADDING_DP = 12.0f
        private const val SWATCH_SIZE_DP = 18.0f
    }
}
