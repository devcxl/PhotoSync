package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.ui.widgets.DialSeekBar

/**
 * Binds the exposure dial to the zero-snap, `-5.0..+5.0 EV` exposure range used by the editor.
 */
internal object ExposureSliderBinder {

    private const val MIN_EXPOSURE_EV = -5.0f
    private const val MAX_EXPOSURE_EV = 5.0f
    private const val EXPOSURE_STEP_EV = 0.05f
    private const val ZERO_SNAP_THRESHOLD_EV = 0.10f

    /**
     * @param onExposureChanged Invoked for every user-driven tick.
     * @param onExposureReleased Invoked when the finger leaves the dial.
     */
    fun bind(
        seekBar: DialSeekBar,
        onExposureChanged: (Float) -> Unit,
        onExposureReleased: () -> Unit
    ) {
        seekBar.minValue = MIN_EXPOSURE_EV
        seekBar.maxValue = MAX_EXPOSURE_EV
        seekBar.stepSize = EXPOSURE_STEP_EV
        seekBar.zeroSnapThreshold = ZERO_SNAP_THRESHOLD_EV
        seekBar.listener = object : DialSeekBar.OnDialChangeListener {
            override fun onValueChanged(value: Float, fromUser: Boolean) {
                if (fromUser) {
                    onExposureChanged(value)
                }
            }

            override fun onStopTrackingTouch() {
                onExposureReleased()
            }
        }
    }
}
