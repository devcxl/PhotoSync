package cn.devcxl.photosync.export

import android.content.Context

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the background batch `Bake` queue and exposes what the status card renders.
 *
 * WorkManager is the source of truth: the controller starts observing immediately so a running or
 * recently completed queue survives configuration changes and Activity recreates.
 */
class BatchBakeController(
    private val context: Context,
    private val owner: LifecycleOwner
) {

    private val _progress = MutableStateFlow<BatchBakeProgress?>(null)

    /** Null when no queue is active or awaiting dismissal. */
    val progress: StateFlow<BatchBakeProgress?> = _progress.asStateFlow()

    init {
        observeWork()
    }

    /**
     * Enqueues [photoIds] for sequential full-resolution export.
     *
     * Existing queued or running work is preserved (`APPEND_OR_REPLACE`), so selecting more photos
     * while a queue drains extends it instead of discarding the pending jobs.
     */
    fun enqueue(photoIds: Collection<Long>) {
        val ids = photoIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) {
            Timber.w("Batch bake enqueue skipped: no photo ids")
            return
        }
        val request = OneTimeWorkRequestBuilder<BatchBakeWorker>()
            .setInputData(BatchBakeWorker.inputFor(ids))
            .addTag(BatchBakeWorker.UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BatchBakeWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        if (_progress.value?.running != true) {
            _progress.value = BatchBakeProgress.from(done = 0, total = ids.size, running = true)
        }
    }

    /** Cancels the queue; the worker observes `isStopped` between photos. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(BatchBakeWorker.UNIQUE_WORK_NAME)
        _progress.value = BatchBakeProgress.from(done = 0, total = 0, running = false)
    }

    /** Clears a finished or cancelled progress card once the auto-dismiss timer fires. */
    fun dismissTerminal() {
        val current = _progress.value ?: return
        if (!current.running) {
            _progress.value = null
        }
    }

    private fun observeWork() {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(BatchBakeWorker.UNIQUE_WORK_NAME)
                    .collect { infos -> _progress.value = resolveProgress(infos) }
            }
        }
    }

    /**
     * Folds the queue's [WorkInfo]s into one progress row.
     *
     * Always prefers the currently `RUNNING` job over `ENQUEUED` jobs appended behind it so adding
     * another batch never makes the card regress to "preparing".
     */
    internal fun resolveProgress(infos: List<WorkInfo>): BatchBakeProgress? {
        if (infos.isEmpty()) return null
        val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
        if (active != null) {
            val total = active.progress.getInt(BatchBakeWorker.KEY_TOTAL, 0)
            val done = active.progress.getInt(BatchBakeWorker.KEY_DONE, 0)
            val failed = active.progress.getInt(BatchBakeWorker.KEY_FAILED, 0)
            return BatchBakeProgress.from(
                done = done,
                total = total,
                failed = failed,
                running = true,
                currentName = active.progress.getString(BatchBakeWorker.KEY_CURRENT_NAME)
            )
        }
        val finished = infos.lastOrNull { it.state.isFinished } ?: return null
        return when (finished.state) {
            WorkInfo.State.CANCELLED -> BatchBakeProgress.from(done = 0, total = 0, running = false)
            WorkInfo.State.FAILED -> null
            WorkInfo.State.SUCCEEDED -> BatchBakeProgress.from(
                done = finished.outputData.getInt(BatchBakeWorker.KEY_COMPLETED, 0),
                total = finished.outputData.getInt(BatchBakeWorker.KEY_TOTAL, 0),
                failed = finished.outputData.getInt(BatchBakeWorker.KEY_FAILED, 0),
                running = false
            )
            else -> null
        }
    }
}
