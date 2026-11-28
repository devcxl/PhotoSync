package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.roundToInt

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.InpaintTileEta
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.ui.InpaintBrushMode
import cn.devcxl.photosync.editor.ui.InpaintBrushSize
import cn.devcxl.photosync.editor.ui.InpaintPanelHost

/**
 * Tier-1 panel of the AI > 消除 tool: paint/erase mode, brush diameter, quality mode, workload,
 * quick sizes, clear and confirm.
 *
 * The panel is a pure view. It reports user intent through [listener] and reflects controller state
 * through [InpaintPanelHost]. The brush is expressed in **source pixels** (`8..1200`, default `64`)
 * with three quick presets (小 24 / 中 64 / 大 160), and the workload line is a live estimate that
 * never blocks the confirmation button.
 */
class InpaintPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), InpaintPanelHost {

    /**
     * User intents raised by the panel.
     */
    interface Listener {
        /** The user switched between the brush and the eraser. */
        fun onInpaintModeChanged(mode: InpaintBrushMode)

        /** The user moved the brush dial or tapped a quick preset, in source pixels. */
        fun onInpaintBrushDiameterChanged(diameterPx: Int)

        /** The user switched between the fine and fast quality mode. */
        fun onInpaintQualityChanged(mode: InpaintQualityMode)

        /** The user asked to clear every committed stroke. */
        fun onInpaintClearRequested()

        /** The user confirmed the removal and expects an inference run. */
        fun onInpaintConfirmRequested()
    }

    /** User-intent callbacks. */
    var listener: Listener? = null

    /** Armed brush mode. */
    var brushMode: InpaintBrushMode = InpaintBrushMode.PAINT
        private set

    /** Brush diameter in source pixels. */
    var brushDiameterPx: Int = InpaintBrushSize.MEDIUM_PRESET_PX
        private set

    /** Armed fine/fast quality mode. */
    var qualityMode: InpaintQualityMode = InpaintQualityMode.FINE
        private set

    /** Last workload shown above the brush row. */
    var workload: InpaintTileEta? = null
        private set

    private val paintChip: TextView by lazy { findViewById(R.id.inpaintModePaintChip) }
    private val eraseChip: TextView by lazy { findViewById(R.id.inpaintModeEraseChip) }
    private val fineChip: TextView by lazy { findViewById(R.id.inpaintQualityFineChip) }
    private val fastChip: TextView by lazy { findViewById(R.id.inpaintQualityFastChip) }
    private val workloadHint: TextView by lazy { findViewById(R.id.inpaintWorkloadHint) }
    private val workloadNote: TextView by lazy { findViewById(R.id.inpaintWorkloadNote) }
    private val clearButton: TextView by lazy { findViewById(R.id.inpaintClearButton) }
    private val confirmButton: TextView by lazy { findViewById(R.id.inpaintConfirmButton) }
    private val emptyHint: TextView by lazy { findViewById(R.id.inpaintEmptyHint) }
    private val brushDial: DialSeekBar by lazy { findViewById(R.id.inpaintBrushDial) }
    private val brushValue: TextView by lazy { findViewById(R.id.inpaintBrushValue) }
    private val smallChip: TextView by lazy { findViewById(R.id.inpaintBrushSmallChip) }
    private val mediumChip: TextView by lazy { findViewById(R.id.inpaintBrushMediumChip) }
    private val largeChip: TextView by lazy { findViewById(R.id.inpaintBrushLargeChip) }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.editor_inpaint_panel, this, true)
        paintChip.setOnClickListener { setBrushMode(InpaintBrushMode.PAINT) }
        eraseChip.setOnClickListener { setBrushMode(InpaintBrushMode.ERASE) }
        fineChip.setOnClickListener { selectQuality(InpaintQualityMode.FINE) }
        fastChip.setOnClickListener { selectQuality(InpaintQualityMode.FAST) }
        smallChip.setOnClickListener { selectBrushDiameter(InpaintBrushSize.SMALL_PRESET_PX) }
        mediumChip.setOnClickListener { selectBrushDiameter(InpaintBrushSize.MEDIUM_PRESET_PX) }
        largeChip.setOnClickListener { selectBrushDiameter(InpaintBrushSize.LARGE_PRESET_PX) }
        clearButton.setOnClickListener { listener?.onInpaintClearRequested() }
        confirmButton.setOnClickListener { listener?.onInpaintConfirmRequested() }
        setupBrushDial()
        refreshModeChips()
        refreshQualityChips()
        refreshBrushValue()
        refreshPresetChips()
        setWorkload(null)
    }

    override fun setBrushMode(mode: InpaintBrushMode) {
        if (brushMode == mode) return
        brushMode = mode
        refreshModeChips()
        listener?.onInpaintModeChanged(mode)
    }

    override fun setBrushDiameterPx(diameterPx: Int) {
        brushDiameterPx = diameterPx.coerceIn(InpaintBrushSize.MIN_DIAMETER_PX, InpaintBrushSize.MAX_DIAMETER_PX)
        brushDial.setValue(brushDiameterPx.toFloat())
        refreshBrushValue()
        refreshPresetChips()
    }

    override fun setQualityMode(mode: InpaintQualityMode) {
        qualityMode = mode
        refreshQualityChips()
    }

    override fun setWorkload(eta: InpaintTileEta?) {
        workload = eta
        if (eta == null || eta.tileCount <= 0) {
            workloadHint.visibility = View.GONE
            workloadNote.visibility = View.GONE
            return
        }
        val seconds = eta.estimatedSeconds
        workloadHint.text = if (seconds != null) {
            context.getString(R.string.editor_inpaint_workload_fmt, eta.tileCount, seconds)
        } else {
            context.getString(R.string.editor_inpaint_workload_tiles_fmt, eta.tileCount)
        }
        workloadHint.visibility = View.VISIBLE
        workloadNote.visibility = if (eta.isLongRunning) View.VISIBLE else View.GONE
    }

    override fun setBusy(busy: Boolean) {
        confirmButton.isEnabled = !busy
        clearButton.isEnabled = !busy
        confirmButton.alpha = if (busy) DISABLED_ALPHA else 1.0f
        clearButton.alpha = if (busy) DISABLED_ALPHA else 1.0f
    }

    /** Shows or hides the "paint something first" hint. */
    fun setEmptyHintVisible(visible: Boolean) {
        emptyHint.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setupBrushDial() {
        brushDial.minValue = InpaintBrushSize.MIN_DIAMETER_PX.toFloat()
        brushDial.maxValue = InpaintBrushSize.MAX_DIAMETER_PX.toFloat()
        brushDial.stepSize = DIAMETER_STEP_PX
        brushDial.zeroSnapThreshold = 0.0f
        brushDial.setValue(brushDiameterPx.toFloat())
        brushDial.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (!fromUser) return
                selectBrushDiameter(value.roundToInt())
            }

            override fun onStopTrackingTouch() = Unit
        }
    }

    private fun selectBrushDiameter(diameterPx: Int) {
        val clamped = diameterPx.coerceIn(InpaintBrushSize.MIN_DIAMETER_PX, InpaintBrushSize.MAX_DIAMETER_PX)
        brushDiameterPx = clamped
        brushDial.setValue(clamped.toFloat())
        refreshBrushValue()
        refreshPresetChips()
        listener?.onInpaintBrushDiameterChanged(clamped)
    }

    private fun selectQuality(mode: InpaintQualityMode) {
        if (qualityMode == mode) return
        qualityMode = mode
        refreshQualityChips()
        listener?.onInpaintQualityChanged(mode)
    }

    private fun refreshModeChips() {
        styleChip(paintChip, selected = brushMode == InpaintBrushMode.PAINT)
        styleChip(eraseChip, selected = brushMode == InpaintBrushMode.ERASE)
    }

    private fun refreshQualityChips() {
        styleChip(fineChip, selected = qualityMode == InpaintQualityMode.FINE)
        styleChip(fastChip, selected = qualityMode == InpaintQualityMode.FAST)
    }

    private fun refreshPresetChips() {
        styleChip(smallChip, selected = brushDiameterPx == InpaintBrushSize.SMALL_PRESET_PX)
        styleChip(mediumChip, selected = brushDiameterPx == InpaintBrushSize.MEDIUM_PRESET_PX)
        styleChip(largeChip, selected = brushDiameterPx == InpaintBrushSize.LARGE_PRESET_PX)
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundColor(
            ContextCompat.getColor(
                context,
                if (selected) R.color.editor_accent_blue else R.color.editor_surface_elevated
            )
        )
        chip.alpha = if (selected) 1.0f else 0.75f
    }

    private fun refreshBrushValue() {
        brushValue.text = context.getString(R.string.editor_inpaint_brush_value_fmt, brushDiameterPx)
    }

    companion object {

        /** Smallest brush the panel exposes, in source pixels. */
        const val MIN_DIAMETER_PX: Int = InpaintBrushSize.MIN_DIAMETER_PX

        /** Largest brush the panel exposes, in source pixels. */
        const val MAX_DIAMETER_PX: Int = InpaintBrushSize.MAX_DIAMETER_PX

        /** Default brush, in source pixels. */
        const val DEFAULT_DIAMETER_PX: Int = InpaintBrushSize.MEDIUM_PRESET_PX

        private const val DIAMETER_STEP_PX = 1.0f
        private const val DISABLED_ALPHA = 0.45f
    }
}
