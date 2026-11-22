package cn.devcxl.photosync.export

import java.io.IOException

import android.content.Context

import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import timber.log.Timber

import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.export.BakeExporter
import cn.devcxl.photosync.editor.export.BakeRequest
import cn.devcxl.photosync.editor.export.BakeResult
import cn.devcxl.photosync.utils.ExtensionUtils

/**
 * Background batch `Bake` queue.
 *
 * One worker processes the whole selection sequentially: RAW concurrency is fixed at 1 by
 * [cn.devcxl.photosync.editor.export.BakeConcurrencyGate], and keeping the loop inside a single
 * worker means the progress data always describes one uniform job instead of N competing ones.
 */
class BatchBakeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val photoIds = inputData.getLongArray(KEY_PHOTO_IDS)?.toList().orEmpty()
        if (photoIds.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "no_photos"))
        val database = AppDatabase.getInstance(applicationContext)
        val sink = EditorHostRegistry.gallerySink
        if (sink == null) {
            Timber.e("Batch bake aborted: no gallery sink installed")
            return Result.failure(workDataOf(KEY_ERROR to "no_sink"))
        }
        val entities = database.photoDao().getByIds(photoIds)
        if (entities.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "photos_missing"))

        var completed = 0
        var failed = 0
        for (entity in entities) {
            if (isStopped) {
                Timber.i("Batch bake cancelled after %d of %d", completed, entities.size)
                return Result.failure(workDataOf(KEY_ERROR to "cancelled"))
            }
            publishProgress(completed, entities.size, failed, entity.name)
            val ok = bakeEntity(database, entity)
            if (ok) completed++ else failed++
        }
        publishProgress(completed, entities.size, failed, currentName = null)
        Timber.i("Batch bake finished: %d exported, %d failed", completed, failed)
        return Result.success(
            workDataOf(
                KEY_COMPLETED to completed,
                KEY_TOTAL to entities.size,
                KEY_FAILED to failed
            )
        )
    }

    private suspend fun bakeEntity(database: AppDatabase, entity: PhotoEntity): Boolean {
        val sink = EditorHostRegistry.gallerySink ?: run {
            Timber.e("GallerySink unavailable during batch bake for photoId=%d", entity.id)
            return false
        }
        val graph = resolveGraph(database, entity.id)
        val result = BakeExporter(applicationContext, sink, applicationContext.filesDir).bake(
            BakeRequest(
                sourcePath = entity.path,
                displayName = displayName(entity),
                isRaw = ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(entity.path)),
                graph = graph
            )
        )
        return when (result) {
            is BakeResult.Success -> {
                result.uri?.let { uri ->
                    try {
                        database.photoDao().updateUriString(entity.id, uri.toString())
                    } catch (io: IOException) {
                        Timber.w(io, "Failed to persist the baked uri for photoId=%d", entity.id)
                    } catch (error: RuntimeException) {
                        Timber.w(error, "Database failure persisting baked uri for photoId=%d", entity.id)
                    }
                }
                true
            }
            is BakeResult.Failure -> {
                Timber.w(result.cause, "Batch bake failed for photoId=%d: %s", entity.id, result.reason)
                false
            }
        }
    }

    private suspend fun resolveGraph(database: AppDatabase, photoId: Long): EditGraph {
        val json = try {
            database.photoEditDao().getByPhotoId(photoId)?.graphJson
        } catch (error: RuntimeException) {
            Timber.w(error, "Failed to read edit graph for batch bake photoId=%d", photoId)
            null
        } ?: return EditGraph()
        return EditGraphJsonCodec.decode(json) ?: run {
            Timber.w("Corrupt edit graph JSON for batch bake photoId=%d; baking neutral", photoId)
            EditGraph()
        }
    }

    private fun displayName(entity: PhotoEntity): String {
        val base = entity.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        return "${base ?: "IMG_${entity.id}"}_edited.jpg"
    }

    private suspend fun publishProgress(
        done: Int,
        total: Int,
        failed: Int,
        currentName: String?
    ) {
        setProgress(
            workDataOf(
                KEY_DONE to done,
                KEY_TOTAL to total,
                KEY_FAILED to failed,
                KEY_CURRENT_NAME to currentName
            )
        )
    }

    companion object {
        /** Unique work name shared by the controller and the queue. */
        const val UNIQUE_WORK_NAME = "photosync_batch_bake"

        /** Input key holding the selected photo ids. */
        const val KEY_PHOTO_IDS = "photo_ids"

        /** Progress keys read by [BatchBakeController]. */
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_CURRENT_NAME = "current_name"
        const val KEY_ERROR = "error"

        /** Output key holding how many photos were published. */
        const val KEY_COMPLETED = "completed"

        /** Builds the worker input for [photoIds]. */
        fun inputFor(photoIds: List<Long>): Data = workDataOf(KEY_PHOTO_IDS to photoIds.toLongArray())
    }
}
