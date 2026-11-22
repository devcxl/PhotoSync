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
 * WorkManager is the source of truth: the controller only translates
 * [BatchBakeWorker.UNIQUE_WORK_NAME]'s [WorkInfo] into [BatchBakeProgress], so the card survives a
 * configuration change or a process restart and can be cancelled from the UI.
 */
class BatchBakeController(
    private val context: Context,
    private val owner: LifecycleOwner
) {

    private val _progress = MutableStateFlow<BatchBakeProgress?>(null)

    /** Null when no queue was ever started in this process. */
    val progress: StateFlow<BatchBakeProgress?> = _progress.asStateFlow()

    private var observing = false

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
        _progress.value = BatchBakeProgress.from(done = 0, total = ids.size, running = true)
        observeWork()
    }

    /** Cancels the queue; the worker observes `isStopped` between photos. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(BatchBakeWorker.UNIQUE_WORK_NAME)
        _progress.value = null
    }

    private fun observeWork() {
        if (observing) return
        observing = true
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
     * APPEND_OR_REPLACE chains several requests for one unique name, so the row a user must see is the
     * active one (running or enqueued); once none is active the last finished job reports the totals.
     */
    internal fun resolveProgress(infos: List<WorkInfo>): BatchBakeProgress? {
        if (infos.isEmpty()) return null
        val active = infos.lastOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (active != null) {
            val total = active.progress.getInt(BatchBakeWorker.KEY_TOTAL, 0)
            val done = active.progress.getInt(BatchBakeWorker.KEY_DONE, 0)
            return BatchBakeProgress.from(
                done = done,
                total = total,
                running = true,
                currentName = active.progress.getString(BatchBakeWorker.KEY_CURRENT_NAME)
            )
        }
        val finished = infos.lastOrNull { it.state.isFinished } ?: return null
        if (finished.state == WorkInfo.State.CANCELLED || finished.state == WorkInfo.State.FAILED) {
            return null
        }
        return BatchBakeProgress.from(
            done = finished.outputData.getInt(BatchBakeWorker.KEY_COMPLETED, 0),
            total = finished.outputData.getInt(BatchBakeWorker.KEY_TOTAL, 0),
            running = false
        )
    }
}
