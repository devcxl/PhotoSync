package cn.devcxl.photosync.editor.render

import java.io.File

import timber.log.Timber

/**
 * Ownership of a preview file across a timed wait.
 *
 * A render can finish in the exact instant a `withTimeoutOrNull` deadline expires: the completion
 * callback wrote a file and resumed the waiter, yet the caller receives `null` because the timeout
 * discarded the value. The holder closes that hole — the callback parks the file, and [settle] deletes
 * anything that did not actually reach the caller. A file that was delivered is never touched.
 *
 * All decisions are taken under one lock, so a late completion after the caller already returned is
 * deleted at [park] time instead of leaking.
 */
internal class PreviewResultHolder(private val delete: (File) -> Unit = { it.delete() }) {

    private val lock = Any()
    private var parked: File? = null
    private var settled: Boolean = false
    private var discarded: Boolean = false

    /**
     * Parks the produced [file] for [settle].
     *
     * A file that arrives after the caller already settled — or after it was discarded — is deleted
     * immediately and never stored.
     */
    fun park(file: File?) {
        if (file == null) return
        val deleteNow = synchronized(lock) {
            if (settled || discarded) {
                true
            } else {
                parked = file
                false
            }
        }
        if (deleteNow) deleteQuietly(file)
    }

    /**
     * Deletes the parked file unless the caller took it; idempotent.
     *
     * Used by the hand-over when the continuation can no longer receive the value.
     */
    fun discardParked() {
        val toDelete = synchronized(lock) {
            if (discarded) return
            discarded = true
            parked.also { parked = null }
        }
        toDelete?.let(::deleteQuietly)
    }

    /**
     * Settles the wait.
     *
     * @param delivered The value the caller actually received, or `null` when it timed out.
     * @return [delivered]; a parked file that never reached the caller is deleted first.
     */
    fun settle(delivered: File?): File? {
        var toDelete: File? = null
        synchronized(lock) {
            if (settled) return delivered
            settled = true
            if (delivered != null) {
                parked = null
            } else if (!discarded) {
                discarded = true
                toDelete = parked
                parked = null
            }
        }
        toDelete?.let(::deleteQuietly)
        return delivered
    }

    private fun deleteQuietly(file: File) {
        try {
            delete(file)
        } catch (error: RuntimeException) {
            Timber.w(error, "Could not discard an abandoned preview cache file")
        }
    }
}
