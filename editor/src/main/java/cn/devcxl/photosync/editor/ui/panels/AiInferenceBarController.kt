package cn.devcxl.photosync.editor.ui.panels

/**
 * Drives the non-blocking AI inference bar docked at the top of the editor bottom drawer.
 *
 * The controller owns the visible/progress/cancelled state and talks to the view through [Host], so
 * the progress and cancel contract is unit-testable without inflating the editor layout. Cancelling
 * hides the bar first and then invokes [onCancelRequested] once; the host coroutine sits behind that
 * callback and is cancelled there.
 */
internal class AiInferenceBarController(
    private val host: Host,
    private val onCancelRequested: () -> Unit
) {

    /** The view operations the bar needs. */
    interface Host {
        /** Shows or hides the whole inference bar. */
        fun setVisible(visible: Boolean)

        /** Replaces the model/progress label. */
        fun setLabel(label: CharSequence)

        /** Moves the determinate progress bar, in `0..100`. */
        fun setProgress(percent: Int)
    }

    /** True while the bar is shown. */
    var isVisible: Boolean = false
        private set

    /** Last progress written to the host, in `0..100`. */
    var progress: Int = 0
        private set

    /** True after the user cancelled the current run and before the next [show]. */
    var isCancelRequested: Boolean = false
        private set

    /** Shows the bar for a new inference run and resets the cancel state. */
    fun show(label: CharSequence, initialProgress: Int = 0) {
        isCancelRequested = false
        progress = clamp(initialProgress)
        host.setLabel(label)
        host.setProgress(progress)
        host.setVisible(true)
        isVisible = true
    }

    /** Moves the progress bar; updates while hidden are ignored so a stale run cannot revive it. */
    fun updateProgress(percent: Int) {
        if (!isVisible) return
        progress = clamp(percent)
        host.setProgress(progress)
    }

    /** Replaces the label of the running inference; ignored while hidden. */
    fun updateLabel(label: CharSequence) {
        if (!isVisible) return
        host.setLabel(label)
    }

    /** Hides the bar without cancelling, for a run that finished or failed. */
    fun dismiss() {
        if (!isVisible) return
        isVisible = false
        host.setVisible(false)
    }

    /** Cancels the running inference exactly once and hides the bar immediately. */
    fun cancel() {
        if (!isVisible || isCancelRequested) return
        isCancelRequested = true
        dismiss()
        onCancelRequested()
    }

    private fun clamp(percent: Int): Int = percent.coerceIn(0, 100)
}
