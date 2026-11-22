package cn.devcxl.photosync.export

/**
 * Phase a batch bake queue is in, so the status card can pick a localized caption.
 */
enum class BatchBakeState {
    /** The queue is starting and does not know its photo count yet. */
    PREPARING,

    /** Encoding runs, at least one photo pending. */
    RUNNING,

    /** Every selected photo was processed. */
    FINISHED,

    /** The queue stopped before finishing. */
    CANCELLED
}

/**
 * Progress of the background batch `Bake` queue.
 *
 * Deliberately free of user-facing text: the caption comes from `strings.xml` via
 * [BatchBakeState], and only the numbers live here so they can be unit-tested.
 */
data class BatchBakeProgress(
    val done: Int,
    val total: Int,
    val running: Boolean = true,
    val currentName: String? = null
) {

    /** Completion ratio in `0f..1f`, safe for a zero-length queue. */
    val ratio: Float
        get() = if (total <= 0) 0.0f else (done.toFloat() / total.toFloat()).coerceIn(0.0f, 1.0f)

    /** True once every selected photo was processed. */
    val isFinished: Boolean
        get() = !running && total > 0 && done >= total

    /** Phase the status card renders. */
    val state: BatchBakeState
        get() = when {
            running && total > 0 -> BatchBakeState.RUNNING
            running -> BatchBakeState.PREPARING
            isFinished -> BatchBakeState.FINISHED
            else -> BatchBakeState.CANCELLED
        }

    companion object {

        /**
         * Builds the progress for a WorkManager `Progress`/`Output` payload.
         *
         * @param done Processed photo count.
         * @param total Selected photo count.
         * @param running True while the worker is still queued or running.
         * @param currentName Name of the photo being encoded, when known.
         */
        fun from(done: Int, total: Int, running: Boolean, currentName: String? = null) =
            BatchBakeProgress(
                done = done.coerceAtLeast(0),
                total = total.coerceAtLeast(0),
                running = running,
                currentName = currentName
            )
    }
}
