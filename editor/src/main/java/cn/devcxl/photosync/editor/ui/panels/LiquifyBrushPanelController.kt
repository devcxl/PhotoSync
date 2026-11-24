package cn.devcxl.photosync.editor.ui.panels

import android.content.Context
import android.view.View
import android.widget.TextView

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.LiquifyBrushMode
import cn.devcxl.photosync.editor.domain.LiquifyBrushSettings
import cn.devcxl.photosync.editor.ui.widgets.DialSeekBar

/**
 * Tier-1 controls of the liquify tool: the push/restore mode toggle, the brush size and strength
 * sliders, and the one-tap reset.
 *
 * Kept separate from [EditorPanelController] so the drawer stays a thin coordinator and the liquify
 * controls can be laid out independently in portrait (bottom drawer) and landscape (side panel).
 */
internal class LiquifyBrushPanelController(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val host: Host
) {

    /**
     * Callbacks the liquify controls raise.
     */
    interface Host {
        /** The brush mode, size or strength changed. */
        fun onLiquifySettingsChanged(settings: LiquifyBrushSettings)

        /** The user asked to clear every liquify displacement. */
        fun onLiquifyResetRequested()
    }

    /** Current brush mode, size and strength. */
    var settings: LiquifyBrushSettings = LiquifyBrushSettings()
        private set

    private val density = context.resources.displayMetrics.density
    private val modeChips = mutableMapOf<LiquifyBrushMode, TextView>()

    init {
        setupModeChips()
        setupDial(
            dial = binding.liquifySizeSeekBar,
            min = LiquifyBrushSettings.MIN_SIZE_NORM,
            max = LiquifyBrushSettings.MAX_SIZE_NORM,
            step = SIZE_STEP,
            value = settings.sizeNorm
        ) { size -> update { it.withSizeNorm(size) } }
        setupStrengthDial()
        binding.liquifyResetButton.setOnClickListener { host.onLiquifyResetRequested() }
    }

    /** Shows or hides the whole liquify control block. */
    fun setVisible(visible: Boolean) {
        binding.liquifyPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refresh()
    }

    /** Re-reads [settings] into the chips and both sliders. */
    fun refresh() {
        modeChips.forEach { (mode, chip) ->
            PanelChipFactory.applySelection(context, chip, mode == settings.mode)
            chip.alpha = if (mode == settings.mode) 1.0f else 0.75f
        }
        binding.liquifySizeSeekBar.setValue(settings.sizeNorm)
        binding.liquifyStrengthSeekBar.setValue(settings.strength * STRENGTH_SLIDER_SCALE)
    }

    private fun setupModeChips() {
        binding.liquifyModeChipContainer.removeAllViews()
        modeChips.clear()
        LiquifyBrushMode.entries.forEach { mode ->
            val chip = PanelChipFactory.create(
                context = context,
                labelRes = mode.labelRes(),
                heightPx = (MODE_CHIP_HEIGHT_DP * density).toInt(),
                textSizeSp = 11.0f,
                paddingPx = (CHIP_PADDING_DP * density).toInt()
            )
            chip.setOnClickListener { update { it.withMode(mode) } }
            modeChips[mode] = chip
            binding.liquifyModeChipContainer.addView(chip)
        }
    }

    private fun setupStrengthDial() {
        val dial = binding.liquifyStrengthSeekBar
        dial.minValue = 0.0f
        dial.maxValue = STRENGTH_SLIDER_SCALE
        dial.stepSize = STRENGTH_STEP
        dial.zeroSnapThreshold = 0.0f
        dial.setValue(settings.strength * STRENGTH_SLIDER_SCALE)
        dial.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (!fromUser) return
                update { it.withStrength(value / STRENGTH_SLIDER_SCALE) }
            }

            override fun onStopTrackingTouch() = Unit
        }
    }

    private fun setupDial(
        dial: DialSeekBar,
        min: Float,
        max: Float,
        step: Float,
        value: Float,
        onChanged: (Float) -> Unit
    ) {
        dial.minValue = min
        dial.maxValue = max
        dial.stepSize = step
        dial.zeroSnapThreshold = 0.0f
        dial.setValue(value)
        dial.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (fromUser) onChanged(value)
            }

            override fun onStopTrackingTouch() = Unit
        }
    }

    private fun update(transform: (LiquifyBrushSettings) -> LiquifyBrushSettings) {
        settings = transform(settings)
        refresh()
        host.onLiquifySettingsChanged(settings)
    }

    private fun LiquifyBrushMode.labelRes(): Int = when (this) {
        LiquifyBrushMode.PUSH -> R.string.editor_liquify_mode_push
        LiquifyBrushMode.RESTORE -> R.string.editor_liquify_mode_restore
    }

    companion object {
        private const val SIZE_STEP = 0.005f
        private const val STRENGTH_SLIDER_SCALE = 100.0f
        private const val STRENGTH_STEP = 1.0f
        private const val MODE_CHIP_HEIGHT_DP = 28.0f
        private const val CHIP_PADDING_DP = 12.0f
    }
}
