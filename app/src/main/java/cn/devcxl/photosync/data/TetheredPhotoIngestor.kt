package cn.devcxl.photosync.data

import java.io.File
import java.io.IOException
import java.util.UUID

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.TetheredSkyPresetPreparer
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SubjectMask
import cn.devcxl.photosync.editor.render.TetheredSubjectPresetPreparer

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

    /**
     * Same transaction as [commit], plus a subject-mask payload that only gets its final
     * `edits/<photoId>/` path once Room has assigned the id.
     *
     * The implementation must move the mask into the content-addressed store, rewrite
     * `subject.maskRef` in [graphJson] and insert that rewritten row in the same transaction. When
     * the mask cannot be written the transaction must fail with [SubjectMaskUnavailableException]
     * (the photo row rolls back) so the ingestor can retry with its traditional-grade preview
     * instead of publishing an ungraded photo or a row it cannot reproduce.
     *
     * The default delegates to [commit], which is the correct behaviour for hosts without AI assets
     * (and for JVM test fakes that only record the graph).
     */
    suspend fun commitWithSubject(
        photo: PhotoEntity,
        stagedPreview: File?,
        graphJson: String?,
        subjectMaskPayload: ByteArray?
    ): Long = commit(photo, stagedPreview, graphJson)

    /**
     * Same transaction as [commitWithSubject], plus an optional sky-mask payload.
     *
     * The production committer moves both masks into the content-addressed store and rewrites
     * `subject.maskRef` / `sky.maskRef` in one transaction. The default delegates to
     * [commitWithSubject], which is correct for hosts that cannot store AI assets: the graph keeps
     * its parameters but the corresponding `maskRef` stays blank, so the effect renders as a no-op
     * instead of pointing at a file that does not exist.
     */
    suspend fun commitWithAi(
        photo: PhotoEntity,
        stagedPreview: File?,
        graphJson: String?,
        subjectMaskPayload: ByteArray?,
        skyMaskPayload: ByteArray?
    ): Long = commitWithSubject(photo, stagedPreview, graphJson, subjectMaskPayload)
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
 * 3. When the preset carries a non-identity `SubjectOps`, optionally run the on-device MODNet
 *    preparation once. A low-memory device or a missing model degrades silently to the traditional
 *    grade — the USB queue never blocks and never drops the photo.
 * 4. Render the preview JPEG into an isolated per-ingest staging directory *before* the row exists.
 * 5. Commit the row, the edit and the staged subject mask together in one transaction.
 * 6. Clean up the staging directory and announce the photo id.
 */
class TetheredPhotoIngestor(
    private val editStore: EditStore,
    private val committer: TetheredPhotoCommitter,
    private val stagingDir: File,
    private val renderPreview: suspend (sourcePath: String, isRaw: Boolean, graph: EditGraph, outDir: File) -> File?,
    private val renderPreviewWithSubject:
        (suspend (String, Boolean, EditGraph, SubjectMask, File) -> File?)? = null,
    private val subjectPreparer: TetheredSubjectPresetPreparer? = null,
    private val skyPreparer: TetheredSkyPresetPreparer? = null,
    private val renderPreviewWithAi:
        (suspend (String, Boolean, EditGraph, SubjectMask?, SkyMask?, File) -> File?)? = null,
    private val onIngested: (TetheredIngestResult) -> Unit = {}
) {

    /**
     * Serializes the whole `stage -> commit` pipeline.
     *
     * The listener fires from the USB reader thread and may overlap with the previous file's
     * preview render. One ingest at a time keeps a second 45-60 MP decode, MODNet mask and OpenGL
     * preview from stacking their peak memory next to a running one on a 4 GiB phone.
     */
    private val ingestMutex = Mutex()

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
        return ingestMutex.withLock { ingest(file, objectHandle, isRaw) }
    }

    private suspend fun ingest(
        file: File,
        objectHandle: Int?,
        isRaw: Boolean
    ): TetheredIngestResult {
        val preset = resolveActivePreset()
        val prepared = preparePreset(file, isRaw, preset)
        val jobDir = File(stagingDir, "$STAGING_DIR_NAME/${UUID.randomUUID()}")
        return try {
            val staged = stagePreview(file, isRaw, prepared, jobDir)
            val photo = PhotoEntity(
                path = file.absolutePath,
                name = file.name,
                uriString = null,
                objectHandle = objectHandle
            )
            val photoId = commitStaged(photo, staged, file, isRaw, prepared, jobDir)
            finishIngest(photoId, staged, preset)
        } finally {
            jobDir.deleteRecursively()
        }
    }

    private fun finishIngest(
        photoId: Long?,
        staged: StagedPreview?,
        preset: ActivePreset?
    ): TetheredIngestResult {
        if (photoId == null) {
            staged?.preview?.delete()
            return TetheredIngestResult.Ignored
        }
        if (photoId <= 0L) {
            staged?.preview?.delete()
            return TetheredIngestResult.Duplicate
        }
        val hasEdit = staged?.graphJson != null
        val result = TetheredIngestResult.Ingested(
            photoId = photoId,
            presetId = if (hasEdit) preset?.presetId else null,
            hasEdit = hasEdit
        )
        onIngested(result)
        return result
    }

    /**
     * Commits the staged row, falling back to the prepared traditional grade when the mask asset
     * cannot be written.
     *
     * [RoomTetheredPhotoCommitter] throws [SubjectMaskUnavailableException] or
     * [SkyMaskUnavailableException] from inside the transaction, so the photo row never lands
     * without its edit. The AI preview is deleted and a fresh preview of the degraded graph is
     * staged before the retry.
     */
    private suspend fun commitStaged(
        photo: PhotoEntity,
        staged: StagedPreview?,
        file: File,
        isRaw: Boolean,
        prepared: PreparedPreset?,
        jobDir: File
    ): Long? {
        val current = staged ?: return commitSafely(photo, null)
        if (current.subjectMaskPayload == null && current.skyMaskPayload == null) {
            return commitSafely(photo, current)
        }
        return try {
            commitSafely(photo, current)
        } catch (maskUnavailable: SubjectMaskUnavailableException) {
            Timber.w(maskUnavailable, "LiveTetherPreset mask could not be stored; using the traditional grade")
            current.preview?.delete()
            commitSafely(photo, stageFallbackPreview(file, isRaw, prepared, jobDir))
        } catch (maskUnavailable: SkyMaskUnavailableException) {
            Timber.w(maskUnavailable, "LiveTetherPreset sky mask could not be stored; using the traditional grade")
            current.preview?.delete()
            commitSafely(photo, stageFallbackPreview(file, isRaw, prepared, jobDir))
        }
    }

    private suspend fun commitSafely(photo: PhotoEntity, staged: StagedPreview?): Long? = try {
        val subjectPayload = staged?.subjectMaskPayload
        val skyPayload = staged?.skyMaskPayload
        when {
            subjectPayload != null || skyPayload != null -> committer.commitWithAi(
                photo,
                staged?.preview,
                staged?.graphJson,
                subjectPayload,
                skyPayload
            )
            else -> committer.commit(photo, staged?.preview, staged?.graphJson)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        Timber.e(io, "Tethered ingest I/O failure while committing photo")
        null
    } catch (error: RuntimeException) {
        Timber.e(error, "Tethered ingest database failure while committing photo")
        null
    }

    private data class ActivePreset(val presetId: String, val graphJson: String)

    /** The preset graph plus the optional staged AI masks of the new photo. */
    private data class PreparedPreset(
        val graphJson: String,
        val fallbackGraphJson: String,
        val subjectMask: SubjectMask?,
        val subjectMaskPayload: ByteArray?,
        val skyMask: SkyMask?,
        val skyMaskPayload: ByteArray?
    )

    /** What one staging step produced; every field must describe the same graph. */
    private data class StagedPreview(
        val preview: File?,
        val graphJson: String?,
        val subjectMaskPayload: ByteArray?,
        val skyMaskPayload: ByteArray?
    )

    private suspend fun resolveActivePreset(): ActivePreset? {
        val presetId = editStore.getLiveTetherPresetId()?.takeIf { it.isNotBlank() } ?: return null
        val preset = try {
            editStore.getPreset(presetId)
        } catch (cancelled: CancellationException) {
            throw cancelled
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

    /**
     * Resolves the graph to render and, when possible, the new photo's own AI masks.
     *
     * A preparation failure is not an ingest failure: the affected group is cleared and the
     * traditional grade still applies. Subject needs MODNet and degrades on `<= 4 GiB` devices or
     * when the model is missing; the classic sky segmenter needs no download and runs here, while a
     * `< 3 %` result or a failure clears `sky` instead of publishing a destructive replacement.
     */
    private suspend fun preparePreset(
        file: File,
        isRaw: Boolean,
        preset: ActivePreset?
    ): PreparedPreset? {
        if (preset == null) return null
        val decoded = EditGraphJsonCodec.decode(preset.graphJson) ?: return null
        // A preset must never hand its own masks to the new photo, even if a stale row still
        // references one: both masks describe the source frame's pixels.
        val cleared = decoded.copy(
            subject = decoded.subject.copy(maskRef = ""),
            sky = decoded.sky.copy(maskRef = "")
        )
        val subject = prepareSubject(file, isRaw, cleared)
        val sky = prepareSky(file, isRaw, subject.graph)
        return PreparedPreset(
            graphJson = EditGraphJsonCodec.encode(sky.graph),
            fallbackGraphJson = EditGraphJsonCodec.encode(degradeAi(decoded)),
            subjectMask = subject.mask,
            subjectMaskPayload = subject.payload,
            skyMask = sky.mask,
            skyMaskPayload = sky.payload
        )
    }

    /** Runs the optional MODNet preparation; a failure clears the subject group only. */
    private suspend fun prepareSubject(
        file: File,
        isRaw: Boolean,
        graph: EditGraph
    ): PreparedSubject {
        if (!graph.subject.requiresMaskProcessing()) return PreparedSubject(graph, null, null)
        val preparation = try {
            subjectPreparer?.prepare(file.absolutePath, isRaw, graph)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            Timber.w(error, "Subject preset preparation threw; applying the traditional grade")
            null
        }
        if (preparation == null) {
            Timber.i("LiveTetherPreset subject effect degraded to the traditional grade")
            return PreparedSubject(graph.copy(subject = SubjectOps()), null, null)
        }
        return PreparedSubject(graph, preparation.mask, preparation.payload)
    }

    /** Runs the classic sky segmentation; a `< 3 %` or failed mask clears the sky group only. */
    private suspend fun prepareSky(
        file: File,
        isRaw: Boolean,
        graph: EditGraph
    ): PreparedSky {
        if (graph.sky.isIdentity()) return PreparedSky(graph, null, null)
        val preparation = try {
            skyPreparer?.prepare(file.absolutePath, isRaw, graph)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            Timber.w(error, "Sky preset preparation threw; applying the traditional grade")
            null
        }
        if (preparation == null) {
            Timber.i("LiveTetherPreset sky effect degraded to the traditional grade")
            return PreparedSky(graph.copy(sky = SkyOps()), null, null)
        }
        return PreparedSky(graph, preparation.mask, preparation.payload)
    }

    /** The graph plus the subject mask staged by [prepareSubject]. */
    private data class PreparedSubject(
        val graph: EditGraph,
        val mask: SubjectMask?,
        val payload: ByteArray?
    )

    /** The graph plus the sky mask staged by [prepareSky]. */
    private data class PreparedSky(
        val graph: EditGraph,
        val mask: SkyMask?,
        val payload: ByteArray?
    )

    private suspend fun stagePreview(
        file: File,
        isRaw: Boolean,
        prepared: PreparedPreset?,
        outDir: File
    ): StagedPreview? {
        if (prepared == null) return null
        val graph = EditGraphJsonCodec.decode(prepared.graphJson) ?: return null
        val hasSubject = prepared.subjectMask != null
        val hasSky = prepared.skyMask != null
        val aiRenderer = renderPreviewWithAi
        if ((hasSubject || hasSky) && aiRenderer != null) {
            val staged = renderSafely {
                aiRenderer(
                    file.absolutePath,
                    isRaw,
                    graph,
                    prepared.subjectMask,
                    prepared.skyMask,
                    outDir
                )
            }
            if (staged != null) {
                return StagedPreview(
                    preview = staged,
                    graphJson = prepared.graphJson,
                    subjectMaskPayload = prepared.subjectMaskPayload,
                    skyMaskPayload = prepared.skyMaskPayload
                )
            }
            Timber.w("The AI preview failed; retrying with the traditional grade")
        } else if (hasSubject && !hasSky && renderPreviewWithSubject != null) {
            val subjectRenderer = checkNotNull(renderPreviewWithSubject)
            val staged = renderSafely {
                subjectRenderer(file.absolutePath, isRaw, graph, checkNotNull(prepared.subjectMask), outDir)
            }
            if (staged != null) {
                return StagedPreview(
                    preview = staged,
                    graphJson = prepared.graphJson,
                    subjectMaskPayload = prepared.subjectMaskPayload,
                    skyMaskPayload = null
                )
            }
            Timber.w("The subject preview failed; retrying with the traditional grade")
        }
        return stageFallbackPreview(file, isRaw, prepared, outDir)
    }

    /** Renders the traditional-grade preview of [prepared] into [outDir]. */
    private suspend fun stageFallbackPreview(
        file: File,
        isRaw: Boolean,
        prepared: PreparedPreset?,
        outDir: File
    ): StagedPreview? {
        val source = prepared ?: return null
        val fallbackGraph = EditGraphJsonCodec.decode(source.fallbackGraphJson) ?: return null
        val fallback = renderSafely {
            renderPreview(file.absolutePath, isRaw, fallbackGraph, outDir)
        }
        if (fallback == null) {
            Timber.w("LiveTetherPreset preview could not be staged; importing ungraded")
            return null
        }
        return StagedPreview(
            preview = fallback,
            graphJson = source.fallbackGraphJson,
            subjectMaskPayload = null,
            skyMaskPayload = null
        )
    }

    private suspend fun renderSafely(render: suspend () -> File?): File? = try {
        render()?.takeIf { it.isFile }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        Timber.e(io, "LiveTetherPreset preview render failed")
        null
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "LiveTetherPreset preview render ran out of memory")
        null
    } catch (error: RuntimeException) {
        Timber.e(error, "LiveTetherPreset preview render threw")
        null
    }

    /** Clears both AI groups while keeping every traditional grade axis. */
    private fun degradeAi(graph: EditGraph): EditGraph =
        graph.copy(subject = SubjectOps(), sky = SkyOps())

    companion object {
        /** Sub-directory of the app cache used while a preset preview has no photo id yet. */
        const val STAGING_DIR_NAME = "tethered_staging"
    }
}
