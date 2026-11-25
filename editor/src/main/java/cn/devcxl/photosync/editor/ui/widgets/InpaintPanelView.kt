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
import cn.devcxl.photosync.editor.ui.InpaintBrushMode
import cn.devcxl.photosync.editor.ui.InpaintPanelHost

/**
 * Tier-1 panel of the AI > 消除 tool: paint/erase mode, brush size, clear and confirm.
 *
 * The panel is a pure view. It reports user intent through [listener] and reflects controller state
 * through [InpaintPanelHost]; the amber >256 px advice is non-blocking and sits above the brush row
 * so it never covers the photo.
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

        /** The user moved the brush-size dial. */
        fun onInpaintBrushSizeChanged(radiusNorm: Float)

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

    /** Brush radius as a fraction of the source width. */
    var brushRadiusNorm: Float = DEFAULT_RADIUS
        private set

    private val paintChip: TextView by lazy { findViewById(R.id.inpaintModePaintChip) }
    private val eraseChip: TextView by lazy { findViewById(R.id.inpaintModeEraseChip) }
    private val clearButton: TextView by lazy { findViewById(R.id.inpaintClearButton) }
    private val confirmButton: TextView by lazy { findViewById(R.id.inpaintConfirmButton) }
    private val warningView: TextView by lazy { findViewById(R.id.inpaintOversizeWarning) }
    private val emptyHint: TextView by lazy { findViewById(R.id.inpaintEmptyHint) }
    private val brushDial: DialSeekBar by lazy { findViewById(R.id.inpaintBrushDial) }
    private val brushValue: TextView by lazy { findViewById(R.id.inpaintBrushValue) }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.editor_inpaint_panel, this, true)
        paintChip.setOnClickListener { setBrushMode(InpaintBrushMode.PAINT) }
        eraseChip.setOnClickListener { setBrushMode(InpaintBrushMode.ERASE) }
        clearButton.setOnClickListener { listener?.onInpaintClearRequested() }
        confirmButton.setOnClickListener { listener?.onInpaintConfirmRequested() }
        setupBrushDial()
        refreshModeChips()
        refreshBrushValue()
    }

    override fun setBrushMode(mode: InpaintBrushMode) {
        if (brushMode == mode) return
        brushMode = mode
        refreshModeChips()
        listener?.onInpaintModeChanged(mode)
    }

    override fun setBrushRadiusNorm(radiusNorm: Float) {
        brushRadiusNorm = radiusNorm.coerceIn(MIN_RADIUS, MAX_RADIUS)
        brushDial.setValue(brushRadiusNorm)
        refreshBrushValue()
    }

    override fun showOversizeWarning(widthPx: Int, heightPx: Int, suggestedSplits: Int) {
        warningView.text = context.getString(
            R.string.editor_inpaint_oversize_warning,
            widthPx,
            heightPx,
            suggestedSplits.coerceAtLeast(2)
        )
        warningView.visibility = View.VISIBLE
    }

    override fun hideOversizeWarning() {
        warningView.visibility = View.GONE
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
        brushDial.minValue = MIN_RADIUS
        brushDial.maxValue = MAX_RADIUS
        brushDial.stepSize = RADIUS_STEP
        brushDial.zeroSnapThreshold = 0.0f
        brushDial.setValue(brushRadiusNorm)
        brushDial.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (!fromUser) return
                brushRadiusNorm = value.coerceIn(MIN_RADIUS, MAX_RADIUS)
                refreshBrushValue()
                listener?.onInpaintBrushSizeChanged(brushRadiusNorm)
            }

            override fun onStopTrackingTouch() = Unit
        }
    }

    private fun refreshModeChips() {
        styleChip(paintChip, selected = brushMode == InpaintBrushMode.PAINT)
        styleChip(eraseChip, selected = brushMode == InpaintBrushMode.ERASE)
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
        val diameterPercent = (brushRadiusNorm * 200.0f).roundToInt().coerceAtLeast(1)
        brushValue.text = context.getString(
            R.string.editor_inpaint_brush_value_fmt,
            diameterPercent
        )
    }

    companion object {
        /** Default radius: a 4% brush diameter, the MI-GAN sweet spot near 256 px at 24 MP. */
        const val DEFAULT_RADIUS = 0.02f

        /** Smallest radius exposed by the panel. */
        const val MIN_RADIUS = 0.004f

        /** Largest radius exposed by the panel. */
        const val MAX_RADIUS = 0.08f

        private const val RADIUS_STEP = 0.002f
        private const val DISABLED_ALPHA = 0.45f
    }
}
