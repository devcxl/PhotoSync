package cn.devcxl.photosync.data

import java.io.File
import java.io.IOException
import java.util.UUID

import timber.log.Timber

import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec

/**
 * Atomically commits a freshly synced photo together with its `LiveTetherPreset` edit row.
 *
 * The seam exists because the two inserts and the preview-file move must share one transaction: if
 * `MainActivity`'s `AutoFollow` observes the new [PhotoEntity] before the `PhotoEditEntity` lands, the
 * very first emission shows the ungraded original.
 */
interface TetheredPhotoCommitter {

    /**
     * Inserts [photo] and, when [stagedPreview] and [graphJson] are present, moves the staged JPEG
     * under the new photo id's edit directory and inserts the edit row — all in one transaction.
     *
     * @return The new photo id, or a non-positive value when the row already existed.
     */
    suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long
}

/**
 * Outcome of one downloaded file.
 */
sealed interface TetheredIngestResult {

    /** The file is not a photo this darkroom handles. */
    data object Ignored : TetheredIngestResult

    /** A photo row was committed, with or without a preset edit. */
    data class Ingested(val photoId: Long, val presetId: String?, val hasEdit: Boolean) :
        TetheredIngestResult

    /** The row already existed (duplicate `Sync`), so nothing was emitted. */
    data object Duplicate : TetheredIngestResult
}

/**
 * Turns a file the camera just finished downloading into a photo row that is already `LiveTetherPreset`-
 * graded before anybody can look at it.
 *
 * Order matters and is the whole point of this class:
 * 1. Read the active preset id (synchronous, `SharedPreferences`-backed).
 * 2. Decode its graph; a missing or identity preset means "plain import".
 * 3. Render the preview JPEG into an isolated per-ingest staging directory *before* the row exists.
 * 4. Commit the row and the edit together in one transaction (dropping the edit row too if staging
 *    failed, so a half-bound `PhotoEditEntity` without preview is never written).
 * 5. Clean up the staging directory and announce the photo id.
 */
class TetheredPhotoIngestor(
    private val editStore: EditStore,
    private val committer: TetheredPhotoCommitter,
    private val stagingDir: File,
    private val renderPreview: suspend (sourcePath: String, isRaw: Boolean, graph: EditGraph, outDir: File) -> File?,
    private val onIngested: (TetheredIngestResult) -> Unit = {}
) {

    /**
     * Handles one completed download.
     *
     * @param file The file the camera wrote into the download directory.
     * @param objectHandle PTP handle of the object on the camera.
     * @param isRaw True when the file is a RAW this pipeline decodes through `:libraw`.
     * @param isJpeg True when the file is a JPEG.
     * @return What happened, for logging and diagnostics.
     */
    suspend fun onFileDownloaded(
        file: File,
        objectHandle: Int?,
        isRaw: Boolean,
        isJpeg: Boolean
    ): TetheredIngestResult {
        if (!isRaw && !isJpeg) return TetheredIngestResult.Ignored
        val preset = resolveActivePreset()
        val jobDir = File(stagingDir, "$STAGING_DIR_NAME/${UUID.randomUUID()}")
        val staged = stagePreview(file, isRaw, preset, jobDir)
        val effectiveGraphJson = if (staged != null) preset?.graphJson else null
        val photo = PhotoEntity(
            path = file.absolutePath,
            name = file.name,
            uriString = null,
            objectHandle = objectHandle
        )
        return try {
            val photoId = commitSafely(photo, staged, effectiveGraphJson)
            if (photoId == null) {
                staged?.delete()
                return TetheredIngestResult.Ignored
            }
            if (photoId <= 0L) {
                staged?.delete()
                TetheredIngestResult.Duplicate
            } else {
                val hasEdit = effectiveGraphJson != null
                val result = TetheredIngestResult.Ingested(
                    photoId = photoId,
                    presetId = if (hasEdit) preset?.presetId else null,
                    hasEdit = hasEdit
                )
                onIngested(result)
                result
            }
        } finally {
            jobDir.deleteRecursively()
        }
    }

    private suspend fun commitSafely(
        photo: PhotoEntity,
        staged: File?,
        graphJson: String?
    ): Long? = try {
        committer.commit(photo, staged, graphJson)
    } catch (io: IOException) {
        Timber.e(io, "Tethered ingest I/O failure while committing photo")
        null
    } catch (error: RuntimeException) {
        Timber.e(error, "Tethered ingest database failure while committing photo")
        null
    }

    private data class ActivePreset(val presetId: String, val graphJson: String)

    private suspend fun resolveActivePreset(): ActivePreset? {
        val presetId = editStore.getLiveTetherPresetId()?.takeIf { it.isNotBlank() } ?: return null
        val preset = try {
            editStore.getPreset(presetId)
        } catch (io: IOException) {
            Timber.e(io, "LiveTetherPreset lookup failed")
            null
        } catch (error: RuntimeException) {
            Timber.e(error, "LiveTetherPreset database lookup failed")
            null
        }
        val graph = preset?.graphSubsetJson?.let { EditGraphJsonCodec.decode(it) }
        if (preset == null || graph == null || graph.isIdentity()) {
            Timber.w("LiveTetherPreset %s is unusable; importing ungraded", presetId)
            return null
        }
        return ActivePreset(presetId = presetId, graphJson = EditGraphJsonCodec.encode(graph))
    }

    private suspend fun stagePreview(
        file: File,
        isRaw: Boolean,
        preset: ActivePreset?,
        outDir: File
    ): File? {
        val graph = preset?.graphJson?.let { EditGraphJsonCodec.decode(it) } ?: return null
        val staged = try {
            renderPreview(file.absolutePath, isRaw, graph, outDir)
        } catch (io: IOException) {
            Timber.e(io, "LiveTetherPreset preview render failed; importing ungraded")
            null
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "LiveTetherPreset preview render ran out of memory; importing ungraded")
            null
        } catch (error: RuntimeException) {
            Timber.e(error, "LiveTetherPreset preview render threw; importing ungraded")
            null
        }
        if (staged == null) {
            Timber.w("LiveTetherPreset preview could not be staged; importing ungraded")
        }
        return staged?.takeIf { it.isFile }
    }

    companion object {
        /** Sub-directory of the app cache used while a preset preview has no photo id yet. */
        const val STAGING_DIR_NAME = "tethered_staging"
    }
}
