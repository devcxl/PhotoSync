package cn.devcxl.photosync.data

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.domain.BuiltinPresets
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.render.TetheredSkyPreparation
import cn.devcxl.photosync.editor.render.TetheredSkyPresetPreparer
import cn.devcxl.photosync.editor.render.TetheredSubjectPreparation
import cn.devcxl.photosync.editor.render.TetheredSubjectPresetPreparer

/**
 * Seam under test: [TetheredPhotoIngestor] — the `LiveTetherPreset` auto-apply and single-transaction
 * commit contract.
 */
class TetheredPhotoIngestorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private companion object {
        /** A PTP object handle, recorded so the photo can still be deleted from the camera. */
        const val CAMERA_HANDLE = 0x10042
    }

    private class FakeEditStore(
        private val presets: Map<String, UserPresetRecord> = emptyMap(),
        var activePresetId: String? = null
    ) : EditStore {
        override suspend fun getEditForPhoto(photoId: Long): PhotoEditRecord? = null

        override suspend fun saveEditForPhoto(
            photoId: Long,
            graph: EditGraph,
            previewCachePath: String?
        ) = Unit

        override suspend fun deleteEditForPhoto(photoId: Long) = Unit

        override fun observeEditedPhotoMap(): Flow<Map<Long, PhotoEditRecord>> = flowOf(emptyMap())

        override fun observePresets(): Flow<List<UserPresetRecord>> = flowOf(presets.values.toList())

        override suspend fun listPresets(): List<UserPresetRecord> = presets.values.toList()

        override suspend fun getPreset(id: String): UserPresetRecord? = presets[id]

        override suspend fun savePreset(preset: UserPresetRecord) = Unit

        override suspend fun renamePreset(id: String, newName: String) = Unit

        override suspend fun deletePreset(id: String) = Unit

        override fun getLiveTetherPresetId(): String? = activePresetId

        override fun setLiveTetherPresetId(presetId: String?) {
            activePresetId = presetId
        }

        override fun observeLiveTetherPresetId(): Flow<String?> = flowOf(activePresetId)
    }

    /** Records what the ingestor asked for and answers with deterministic photo ids. */
    private class RecordingCommitter(
        private val nextId: (PhotoEntity) -> Long = { 7L }
    ) : TetheredPhotoCommitter {
        val commits = mutableListOf<Commit>()
        var stagedPreviewAtCommit: File? = null

        data class Commit(val photo: PhotoEntity, val stagedPreview: File?, val graphJson: String?)

        override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long {
            stagedPreviewAtCommit = stagedPreview
            commits += Commit(photo, stagedPreview, graphJson)
            return nextId(photo)
        }
    }

    /** A non-identity graph with one tone axis set, i.e. something a preset can actually apply. */
    private fun gradedGraph(contrast: Int = 0, glow: Int = 0): EditGraph =
        EditGraph(tone = ToneOps(contrast = contrast, glow = glow))

    private fun preset(id: String, graph: EditGraph): UserPresetRecord = UserPresetRecord(
        id = id,
        name = id,
        graphSubsetJson = EditGraphJsonCodec.encode(graph),
        isBuiltin = true
    )

    private fun ingestor(
        editStore: EditStore,
        committer: TetheredPhotoCommitter,
        renderPreview: suspend (String, Boolean, EditGraph, File) -> File? = { _, _, _, _ -> null }
    ): TetheredPhotoIngestor = TetheredPhotoIngestor(
        editStore = editStore,
        committer = committer,
        stagingDir = temporaryFolder.root,
        renderPreview = renderPreview
    )

    private fun sourceFile(name: String = "DSC04821.ARW"): File {
        val file = File(temporaryFolder.root, name)
        file.createNewFile()
        return file
    }

    @Test
    fun shouldSerializeTheStageAndCommitPipeline_whenTwoDownloadsArriveTogether() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val presetStore = FakeEditStore(
            presets = mapOf("gated" to preset("gated", gradedGraph(contrast = 10))),
            activePresetId = "gated"
        )
        val gated = TetheredPhotoIngestor(
            editStore = presetStore,
            committer = RecordingCommitter(),
            stagingDir = temporaryFolder.root,
            renderPreview = { _, _, _, _ ->
                peak.set(maxOf(peak.get(), active.incrementAndGet()))
                entered.complete(Unit)
                release.await()
                active.decrementAndGet()
                null
            }
        )
        val firstFile = sourceFile("first.ARW")
        val secondFile = sourceFile("second.ARW")

        val first = launch(Dispatchers.Default) {
            gated.onFileDownloaded(firstFile, 1, isRaw = true, isJpeg = false)
        }
        entered.await()
        val second = launch(Dispatchers.Default) {
            gated.onFileDownloaded(secondFile, 2, isRaw = true, isJpeg = false)
        }
        delay(50)
        assertEquals("the second ingest must wait for the first", 1, active.get())

        release.complete(Unit)
        first.join()
        second.join()

        assertEquals("two previews must never render at the same time", 1, peak.get())
    }

    @Test
    fun shouldApplyLiveTetherPreset_whenOneIsActive() = runBlocking {
        val presetGraph = EditGraph(tone = ToneOps(exposure = 0.8f, contrast = 12))
        val store = FakeEditStore(
            presets = mapOf("p1" to preset("p1", presetGraph)),
            activePresetId = "p1"
        )
        val staged = File(temporaryFolder.root, "preview_1.jpg").apply { createNewFile() }
        val committer = RecordingCommitter()
        val ingestor = ingestor(store, committer) { _, _, _, _ -> staged }

        val result = ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = true, isJpeg = false)

        val ingested = result as TetheredIngestResult.Ingested
        assertEquals(7L, ingested.photoId)
        assertEquals("p1", ingested.presetId)
        assertTrue(ingested.hasEdit)
        val commit = committer.commits.single()
        assertEquals(staged, commit.stagedPreview)
        assertEquals(presetGraph, EditGraphJsonCodec.decode(commit.graphJson ?: ""))
    }

    @Test
    fun shouldSkipPreset_whenLiveTetherPresetIsNull() = runBlocking {
        val store = FakeEditStore(activePresetId = null)
        val committer = RecordingCommitter()
        var rendered = 0
        val ingestor = ingestor(store, committer) { _, _, _, _ ->
            rendered++
            null
        }

        val result = ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = false, isJpeg = true)

        val ingested = result as TetheredIngestResult.Ingested
        assertEquals(7L, ingested.photoId)
        assertNull(ingested.presetId)
        assertEquals(0, rendered)
        assertNull(committer.commits.single().graphJson)
        assertNull(committer.commits.single().stagedPreview)
    }

    @Test
    fun shouldImportUngraded_whenPresetRowIsMissing() = runBlocking {
        val store = FakeEditStore(activePresetId = "does_not_exist")
        val committer = RecordingCommitter()
        val ingestor = ingestor(store, committer)

        val result = ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertNull((result as TetheredIngestResult.Ingested).presetId)
        assertNull(committer.commits.single().graphJson)
    }

    @Test
    fun shouldImportUngraded_whenPresetGraphIsIdentity() = runBlocking {
        val store = FakeEditStore(
            presets = mapOf("empty" to preset("empty", EditGraph())),
            activePresetId = "empty"
        )
        val committer = RecordingCommitter()
        val ingestor = ingestor(store, committer)

        val result = ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertNull((result as TetheredIngestResult.Ingested).presetId)
        assertNull(committer.commits.single().graphJson)
    }

    @Test
    fun shouldReportDuplicateAndDropPreview_whenInsertIsIgnored() = runBlocking {
        val store = FakeEditStore(
            presets = mapOf("p1" to preset("p1", gradedGraph(contrast = 4))),
            activePresetId = "p1"
        )
        val staged = File(temporaryFolder.root, "preview_dup.jpg").apply { createNewFile() }
        val committer = RecordingCommitter(nextId = { -1L })
        val ingestor = ingestor(store, committer) { _, _, _, _ -> staged }

        val result = ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(TetheredIngestResult.Duplicate, result)
        assertTrue("a duplicate insert must delete its staged preview", !staged.exists())
    }

    @Test
    fun shouldIgnoreNonPhotoFiles_whenExtensionIsNeitherRawNorJpeg() = runBlocking {
        val store = FakeEditStore(activePresetId = "p1")
        val committer = RecordingCommitter()
        val ingestor = ingestor(store, committer)

        val result = ingestor.onFileDownloaded(
            sourceFile("notes.txt"),
            objectHandle = CAMERA_HANDLE,
            isRaw = false,
            isJpeg = false
        )

        assertEquals(TetheredIngestResult.Ignored, result)
        assertTrue(committer.commits.isEmpty())
    }

    @Test
    fun shouldImportUngraded_whenPreviewRenderFails() = runBlocking {
        val store = FakeEditStore(
            presets = mapOf("p1" to preset("p1", gradedGraph(glow = 9))),
            activePresetId = "p1"
        )
        val committer = RecordingCommitter()
        val ingestor = ingestor(store, committer) { _, _, _, _ -> null }

        val result = ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = true, isJpeg = false)

        val ingested = result as TetheredIngestResult.Ingested
        assertNull(ingested.presetId)
        assertTrue("no preview means no bound edit", !ingested.hasEdit)
        assertNull(committer.commits.single().stagedPreview)
        assertNull(committer.commits.single().graphJson)
    }

    @Test
    fun shouldPropagateCancellationAndRemoveStaging_whenPreviewRenderIsCancelled() {
        val store = FakeEditStore(
            presets = mapOf("p1" to preset("p1", gradedGraph(glow = 9))),
            activePresetId = "p1"
        )
        val committer = RecordingCommitter()
        var jobDir: File? = null
        val ingestor = ingestor(store, committer) { _, _, _, outDir ->
            jobDir = outDir
            outDir.mkdirs()
            File(outDir, "partial").writeText("partial")
            throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)
            }
        }
        assertTrue(committer.commits.isEmpty())
        assertTrue(jobDir?.exists() == false)
    }

    @Test
    fun shouldPropagateCancellationAndRemoveStaging_whenCommitIsCancelled() {
        val store = FakeEditStore(
            presets = mapOf("p1" to preset("p1", gradedGraph(contrast = 4))),
            activePresetId = "p1"
        )
        val committer = object : TetheredPhotoCommitter {
            override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long {
                throw CancellationException("cancelled")
            }
        }
        var jobDir: File? = null
        val ingestor = ingestor(store, committer) { _, _, _, outDir ->
            jobDir = outDir
            outDir.mkdirs()
            File(outDir, "preview.jpg").apply { writeText("preview") }
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)
            }
        }
        assertTrue(jobDir?.exists() == false)
    }

    @Test
    fun shouldSkipBothAiPreparers_whenLivePresetApplyAiIsDisabled() = runBlocking {
        val presetGraph = EditGraph(
            tone = ToneOps(contrast = 12, glow = 4),
            subject = SubjectOps(bgMode = SubjectBackgroundMode.SOLID),
            sky = SkyOps(assetType = SkyAssetType.PROCEDURAL, assetIdOrRelPath = "builtin-sunset")
        )
        val store = FakeEditStore(
            presets = mapOf("p1" to preset("p1", presetGraph)),
            activePresetId = "p1"
        )
        var subjectCalls = 0
        var skyCalls = 0
        var aiRenderCalls = 0
        var traditionalRenderCalls = 0
        val staged = File(temporaryFolder.root, "preview_traditional.jpg").apply { createNewFile() }
        val committer = RecordingCommitter()
        val ingestor = TetheredPhotoIngestor(
            editStore = store,
            committer = committer,
            stagingDir = temporaryFolder.root,
            renderPreview = { _, _, _, _ ->
                traditionalRenderCalls++
                staged
            },
            subjectPreparer = object : TetheredSubjectPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSubjectPreparation? {
                    subjectCalls++
                    return null
                }
            },
            skyPreparer = object : TetheredSkyPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSkyPreparation? {
                    skyCalls++
                    return null
                }
            },
            renderPreviewWithAi = { _, _, _, _, _, _ ->
                aiRenderCalls++
                staged
            },
            livePresetApplyAi = { false }
        )

        val result = ingestor.onFileDownloaded(
            sourceFile(),
            objectHandle = CAMERA_HANDLE,
            isRaw = true,
            isJpeg = false
        )

        val ingested = result as TetheredIngestResult.Ingested
        assertEquals("p1", ingested.presetId)
        assertEquals(0, subjectCalls)
        assertEquals(0, skyCalls)
        assertEquals(0, aiRenderCalls)
        assertEquals(1, traditionalRenderCalls)
        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.commits.single().graphJson ?: ""))
        assertEquals("the traditional tone axes must survive", 12, committed.tone.contrast)
        assertEquals(SubjectOps(), committed.subject)
        assertEquals(SkyOps(), committed.sky)
    }

    @Test
    fun shouldAnnounceCommittedPhotoId_whenIngestSucceeds() = runBlocking {
        val store = FakeEditStore(activePresetId = null)
        val announced = mutableListOf<TetheredIngestResult>()
        val ingestor = TetheredPhotoIngestor(
            editStore = store,
            committer = RecordingCommitter(),
            stagingDir = temporaryFolder.root,
            renderPreview = { _, _, _, _ -> null },
            onIngested = { result -> announced += result }
        )

        ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = false, isJpeg = true)

        assertEquals(1, announced.size)
        assertEquals(7L, (announced.single() as TetheredIngestResult.Ingested).photoId)
    }

    @Test
    fun shouldRecordCameraObjectHandle_whenIngesting() = runBlocking {
        val committer = RecordingCommitter()
        val ingestor = ingestor(FakeEditStore(), committer)

        ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(0x10042, committer.commits.single().photo.objectHandle)
    }

    @Test
    fun shouldBuildValidPresetJson_whenApplyingABuiltin() = runBlocking {
        val spec = BuiltinPresets.specs().first { it.id == BuiltinPresets.FILM_CHROME_ID }
        val builtin = UserPresetRecord(
            id = spec.id,
            name = spec.id,
            graphSubsetJson = spec.graphSubsetJson,
            isBuiltin = true,
            createdAt = spec.createdAt
        )
        val store = FakeEditStore(
            presets = mapOf(builtin.id to builtin),
            activePresetId = builtin.id
        )
        val staged = File(temporaryFolder.root, "preview_builtin.jpg").apply { createNewFile() }
        val committer = RecordingCommitter()
        val ingestor = ingestor(store, committer) { _, _, _, _ -> staged }

        ingestor.onFileDownloaded(sourceFile(), objectHandle = CAMERA_HANDLE, isRaw = true, isJpeg = false)

        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.commits.single().graphJson ?: ""))
        assertTrue(committed.effects.grain.amount > 0)
        assertEquals(builtin.graphSubsetJson, EditGraphJsonCodec.encode(committed))
    }
}
