package cn.devcxl.photosync.editor.render

/**
 * Circuit breaker for the offscreen preview renderer.
 *
 * A device that cannot create a headless context, cannot compile the shared preview shaders, or
 * whose driver rejects the offscreen attachments would otherwise pay the full GL setup cost on
 * every preview and still end up on the CPU path. After [failureLimit] consecutive failures the GPU path
 * stays disabled for the rest of the process, so every later preview goes straight to the CPU
 * writer; one success resets the count.
 *
 * Thread-safe: several coroutines can finish a preview at the same time.
 */
internal class OffscreenPreviewBreaker(private val failureLimit: Int = DEFAULT_FAILURE_LIMIT) {

    private val lock = Any()

    private var consecutiveFailures: Int = 0

    /** True while the GPU path may still be attempted. */
    val isEnabled: Boolean
        get() = synchronized(lock) { consecutiveFailures < failureLimit }

    /** Serialized failure count, for diagnostics and tests. */
    val failureCount: Int
        get() = synchronized(lock) { consecutiveFailures }

    /** Records a successful render and re-arms the GPU path. */
    fun onSuccess() {
        synchronized(lock) { consecutiveFailures = 0 }
    }

    /** Records a failed render; the GPU path is disabled once the limit is reached. */
    fun onFailure() {
        synchronized(lock) {
            if (consecutiveFailures < failureLimit) {
                consecutiveFailures++
            }
        }
    }

    /** Re-arms the breaker unconditionally. */
    fun reset() {
        synchronized(lock) { consecutiveFailures = 0 }
    }

    companion object {
        /**
         * Failures tolerated before the GPU path is abandoned.
         *
         * Three keeps a single transient failure (OOM, a driver hiccup, a cancelled render) from
         * disabling acceleration for the session while a genuinely unusable device gives up quickly.
         */
        const val DEFAULT_FAILURE_LIMIT = 3
    }
}
