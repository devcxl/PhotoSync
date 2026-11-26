package cn.devcxl.photosync.data

import java.io.File

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.render.ModnetTetheredSubjectPresetPreparer
import cn.devcxl.photosync.editor.vision.OrtSessionManager
import cn.devcxl.photosync.editor.vision.SubjectMask
import cn.devcxl.photosync.editor.render.TetheredSubjectPreparation
import cn.devcxl.photosync.editor.render.TetheredSubjectPresetPreparer

/**
 * Seam under test: [TetheredPhotoIngestor] + the MODNet preparer — the `LiveTetherPreset`
 * subject-mask contract.
 *
 * A standard-tier device with the model installed computes the new photo's own mask and commits it
 * together with the photo; a `<= 4 GiB` device, a missing model or any inference failure silently
 * degrades to the traditional grade and never blocks or drops the USB ingest.
 */
class TetheredSubjectPresetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private companion object {
        const val CAMERA_HANDLE = 0x10042
    }

    private fun subjectGraph(): EditGraph = EditGraph(
        tone = ToneOps(exposure = 0.6f, contrast = 8),
        subject = SubjectOps(
            bgMode = SubjectBackgroundMode.SOLID,
            bgColorArgb = 0xFF336699.toInt(),
            blur = 55,
            fgExposure = 12,
            bgExposure = -18
        )
    )

    private fun preset(id: String, graph: EditGraph): UserPresetRecord = UserPresetRecord(
        id = id,
        name = id,
        graphSubsetJson = EditGraphJsonCodec.encode(graph),
        isBuiltin = true
    )

    private class FakeEditStore(
        private val presets: Map<String, UserPresetRecord>,
        var activePresetId: String?
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

    private class RecordingCommitter : TetheredPhotoCommitter {
        var plainCommits = 0
        var subjectCommits = 0
        var graphJson: String? = null
        var subjectMaskPayload: ByteArray? = null

        override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long {
            plainCommits++
            this.graphJson = graphJson
            return 7L
        }

        override suspend fun commitWithSubject(
            photo: PhotoEntity,
            stagedPreview: File?,
            graphJson: String?,
            subjectMaskPayload: ByteArray?
        ): Long {
            subjectCommits++
            this.graphJson = graphJson
            this.subjectMaskPayload = subjectMaskPayload
            return 7L
        }
    }

    private fun mask(): SubjectMask = SubjectMask(2, 2, FloatArray(4) { 1.0f })

    private fun ingestor(
        store: EditStore,
        committer: TetheredPhotoCommitter,
        preparer: TetheredSubjectPresetPreparer?,
        renderPreview: suspend (String, Boolean, EditGraph, File) -> File? = { _, _, _, _ -> null },
        renderWithSubject:
            (suspend (String, Boolean, EditGraph, SubjectMask, File) -> File?)? = null
    ): TetheredPhotoIngestor = TetheredPhotoIngestor(
        editStore = store,
        committer = committer,
        stagingDir = temporaryFolder.root,
        renderPreview = renderPreview,
        renderPreviewWithSubject = renderWithSubject,
        subjectPreparer = preparer
    )

    private fun sourceFile(): File =
        File(temporaryFolder.root, "DSC09001.ARW").apply { createNewFile() }

    @Test
    fun shouldGenerateThePhotosOwnMaskAndCommitIt_whenTheDeviceIsStandardAndTheModelIsReady() =
        runBlocking {
            val store = FakeEditStore(
                presets = mapOf("subject" to preset("subject", subjectGraph())),
                activePresetId = "subject"
            )
            val committer = RecordingCommitter()
            val payload = byteArrayOf(1, 2, 3, 4)
            var subjectRenders = 0
            val staged = File(temporaryFolder.root, "preview.jpg").apply { createNewFile() }
            val ingestor = ingestor(
                store = store,
                committer = committer,
                preparer = object : TetheredSubjectPresetPreparer {
                    override suspend fun prepare(
                        sourcePath: String,
                        isRaw: Boolean,
                        graph: EditGraph
                    ): TetheredSubjectPreparation =
                        TetheredSubjectPreparation(mask(), payload)
                },
                renderWithSubject = { _, _, _, _, _ ->
                    subjectRenders++
                    staged
                }
            )

            val result = ingestor.onFileDownloaded(
                sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false
            )

            assertEquals(1, subjectRenders)
            assertEquals(0, committer.plainCommits)
            assertEquals(1, committer.subjectCommits)
            assertTrue(payload.contentEquals(committer.subjectMaskPayload ?: ByteArray(0)))
            val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
            assertEquals(SubjectBackgroundMode.SOLID, committed.subject.bgMode)
            assertEquals(55, committed.subject.blur)
            assertEquals("", committed.subject.maskRef)
            assertEquals(0.6f, committed.tone.exposure, 1e-4f)
            assertTrue((result as TetheredIngestResult.Ingested).hasEdit)
        }

    @Test
    fun shouldNeverInheritThePresetMaskRef_whenPreparingTheNewPhoto() = runBlocking {
        val stale = subjectGraph().copy(
            subject = subjectGraph().subject.copy(
                maskRef = "edits/999/subject_mask_${"d".repeat(64)}.png"
            )
        )
        val store = FakeEditStore(
            presets = mapOf("subject" to preset("subject", stale)),
            activePresetId = "subject"
        )
        val committer = RecordingCommitter()
        val staged = File(temporaryFolder.root, "preview_stale.jpg").apply { createNewFile() }
        val ingestor = ingestor(
            store = store,
            committer = committer,
            preparer = object : TetheredSubjectPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSubjectPreparation = TetheredSubjectPreparation(mask(), byteArrayOf(4))
            },
            renderWithSubject = { _, _, _, _, _ -> staged }
        )

        ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)

        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
        assertEquals("", committed.subject.maskRef)
    }

    @Test
    fun shouldDegradeToTheTraditionalGrade_whenThePreparerReturnsNull() = runBlocking {
        val store = FakeEditStore(
            presets = mapOf("subject" to preset("subject", subjectGraph())),
            activePresetId = "subject"
        )
        val committer = RecordingCommitter()
        var subjectRenders = 0
        val staged = File(temporaryFolder.root, "preview_plain.jpg").apply { createNewFile() }
        val ingestor = ingestor(
            store = store,
            committer = committer,
            preparer = object : TetheredSubjectPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSubjectPreparation? = null
            },
            renderPreview = { _, _, _, _ -> staged },
            renderWithSubject = { _, _, _, _, _ ->
                subjectRenders++
                staged
            }
        )

        val result = ingestor.onFileDownloaded(
            sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false
        )

        assertEquals("degraded import must never render the subject path", 0, subjectRenders)
        assertEquals(1, committer.plainCommits)
        assertEquals(0, committer.subjectCommits)
        assertNull(committer.subjectMaskPayload)
        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
        assertEquals(SubjectOps(), committed.subject)
        assertEquals(0.6f, committed.tone.exposure, 1e-4f)
        assertTrue((result as TetheredIngestResult.Ingested).hasEdit)
    }

    @Test
    fun shouldFallBackToTheTraditionalGrade_whenTheSubjectPreviewFails() = runBlocking {
        val store = FakeEditStore(
            presets = mapOf("subject" to preset("subject", subjectGraph())),
            activePresetId = "subject"
        )
        val committer = RecordingCommitter()
        val staged = File(temporaryFolder.root, "preview_fallback.jpg").apply { createNewFile() }
        val ingestor = ingestor(
            store = store,
            committer = committer,
            preparer = object : TetheredSubjectPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSubjectPreparation = TetheredSubjectPreparation(mask(), byteArrayOf(9))
            },
            renderPreview = { _, _, _, _ -> staged },
            renderWithSubject = { _, _, _, _, _ -> null }
        )

        ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(1, committer.plainCommits)
        assertEquals(0, committer.subjectCommits)
        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
        assertEquals(SubjectOps(), committed.subject)
    }

    @Test
    fun shouldDegradeWithoutThrowing_whenThePreparerThrows() = runBlocking {
        val store = FakeEditStore(
            presets = mapOf("subject" to preset("subject", subjectGraph())),
            activePresetId = "subject"
        )
        val committer = RecordingCommitter()
        val staged = File(temporaryFolder.root, "preview_throw.jpg").apply { createNewFile() }
        val ingestor = ingestor(
            store = store,
            committer = committer,
            preparer = object : TetheredSubjectPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSubjectPreparation? = throw IllegalStateException("inference failed")
            },
            renderPreview = { _, _, _, _ -> staged }
        )

        val result = ingestor.onFileDownloaded(
            sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false
        )

        assertTrue(result is TetheredIngestResult.Ingested)
        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
        assertEquals(SubjectOps(), committed.subject)
    }

    @Test
    fun shouldNotRunThePreparer_whenThePresetCarriesNoSubjectOps() = runBlocking {
        val plain = EditGraph(tone = ToneOps(contrast = 5))
        val store = FakeEditStore(
            presets = mapOf("plain" to preset("plain", plain)),
            activePresetId = "plain"
        )
        val committer = RecordingCommitter()
        var prepared = 0
        val staged = File(temporaryFolder.root, "preview_plain2.jpg").apply { createNewFile() }
        val ingestor = ingestor(
            store = store,
            committer = committer,
            preparer = object : TetheredSubjectPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSubjectPreparation? {
                    prepared++
                    return TetheredSubjectPreparation(mask(), byteArrayOf(1))
                }
            },
            renderPreview = { _, _, _, _ -> staged }
        )

        ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(0, prepared)
        assertEquals(1, committer.plainCommits)
    }

    @Test
    fun shouldPropagateCancellation_whenThePreparerIsCancelled() = runBlocking {
        val store = FakeEditStore(
            presets = mapOf("subject" to preset("subject", subjectGraph())),
            activePresetId = "subject"
        )
        val ingestor = ingestor(
            store = store,
            committer = RecordingCommitter(),
            preparer = object : TetheredSubjectPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSubjectPreparation? = throw CancellationException("cancelled")
            }
        )

        try {
            ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)
            throw AssertionError("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun shouldReturnNull_whenTheDeviceIsLowMemoryOrTheModelIsMissing() = runBlocking {
        val lowTier = ModnetTetheredSubjectPresetPreparer(
            filesDir = temporaryFolder.newFolder("files-low"),
            sessions = OrtSessionManager(tier = DeviceMemoryTier.LOW),
            tier = DeviceMemoryTier.LOW
        )
        assertNull(lowTier.prepare("unused", isRaw = false, graph = subjectGraph()))

        val standardWithoutModel = ModnetTetheredSubjectPresetPreparer(
            filesDir = temporaryFolder.newFolder("files-standard"),
            sessions = OrtSessionManager(tier = DeviceMemoryTier.STANDARD),
            tier = DeviceMemoryTier.STANDARD
        )
        assertNull(standardWithoutModel.prepare("unused", isRaw = false, graph = subjectGraph()))
        assertNull(standardWithoutModel.prepare("unused", isRaw = false, graph = EditGraph()))
    }
}
