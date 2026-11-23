package cn.devcxl.photosync.editor.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Shared "Done" action: freezes the session, persists the edit off the main thread, and only then
 * hands the photo identity back to the host.
 *
 * Every exit path is decided here rather than in `EditorActivity`, so the UI can never be left in a
 * half-saved state:
 * - the snapshot is captured synchronously on the calling (main) thread, before any work leaves the
 *   UI, so the saved state matches the frame the user is looking at;
 * - the source pixels the snapshot borrowed are returned on **every** path, including the ones where
 *   the save never runs (a scope that is already cancelled, a failing progress callback) and the
 *   ones where it runs twice over the same snapshot (the save's own `finally` and the job's
 *   completion) — [EditorSaveSnapshot.close] is idempotent;
 * - a failed save re-enables Done and keeps the editor open with its graph untouched;
 * - a cancellation is rethrown instead of being mistaken for a failure, so an activity teardown
 *   never reports "save failed" or finishes with a result.
 */
internal object EditorFinishAction {

    /**
     * Persists the editor's current state and reports the outcome.
     *
     * [snapshot] and [onSavingStarted] run synchronously on the calling thread. [save], [onSaved]
     * and [onSaveFailed] run on [scope], which must be bound to the main dispatcher for the UI
     * callbacks to be safe.
     *
     * @param snapshot Main-thread read of the mutable session; it owns the source lease until it is
     *   closed, which this action guarantees for the paths where [save] does not run.
     * @param save Persists the snapshot; `false` is a recoverable failure that keeps the editor open.
     *   Cancellation propagates out of it.
     * @param onSavingStarted Shows the progress state and stops further Done taps. Must not throw;
     *   if it does the save is abandoned, the lease returned and the failure reported.
     * @param onSaved Invoked once the record is durable; the only place allowed to finish the
     *   activity with a result.
     * @param onSaveFailed Invoked on failure and on an unexpected capture error; restores the UI.
     * @return The job that carries the save, or `null` when nothing was launched.
     */
    fun saveAndFinish(
        scope: CoroutineScope,
        snapshot: () -> EditorSaveSnapshot,
        save: suspend (EditorSaveSnapshot) -> Boolean,
        onSavingStarted: () -> Unit,
        onSaved: () -> Unit,
        onSaveFailed: () -> Unit
    ): Job? {
        val frozen = try {
            snapshot()
        } catch (error: RuntimeException) {
            return abandon(error, onSaveFailed)
        } catch (oom: OutOfMemoryError) {
            return abandon(oom, onSaveFailed)
        }
        try {
            onSavingStarted()
        } catch (error: RuntimeException) {
            return abandon(error, onSaveFailed, frozen)
        } catch (oom: OutOfMemoryError) {
            return abandon(oom, onSaveFailed, frozen)
        }
        val job = scope.launch {
            val persisted = try {
                save(frozen)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (oom: OutOfMemoryError) {
                Timber.e(oom, "Editor save ran out of memory")
                false
            } catch (error: Exception) {
                Timber.e(error, "Editor save failed unexpectedly")
                false
            }
            if (persisted) onSaved() else onSaveFailed()
        }
        // Covers the one path `save` cannot: a scope that is already cancelled never runs the body,
        // so `persist`'s `finally` never returns the lease. Closing is idempotent, so this is a
        // no-op on every path where the save did run.
        job.invokeOnCompletion { frozen.close() }
        return job
    }

    /**
     * Reports that nothing was saved and returns the lease that a capture failure may have taken.
     *
     * @param frozen The snapshot to close, or `null` when the capture itself failed.
     */
    private fun abandon(
        error: Throwable,
        onSaveFailed: () -> Unit,
        frozen: EditorSaveSnapshot? = null
    ): Job? {
        Timber.e(error, "Editor save could not start; nothing was saved")
        frozen?.close()
        try {
            onSaveFailed()
        } catch (callbackError: RuntimeException) {
            Timber.e(callbackError, "Editor save-failure callback threw")
        }
        return null
    }
}
