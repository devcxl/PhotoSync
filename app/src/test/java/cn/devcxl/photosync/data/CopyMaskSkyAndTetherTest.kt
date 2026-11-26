package cn.devcxl.photosync.data

import java.io.File

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
import cn.devcxl.photosync.editor.domain.CopyMask
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.PresetGraphMerger
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.render.TetheredSkyPreparation
import cn.devcxl.photosync.editor.render.TetheredSkyPresetPreparer
import cn.devcxl.photosync.editor.vision.SkyMask

/**
 * Seam under test: the `SkyOps` cross-photo contract.
 *
 * A preset, a `CopyMask` paste and a `LiveTetherPreset` all carry the *parameters* of a sky (asset,
 * relight and horizon) but never the source frame's mask: the target photo computes its own
 * `SkyMask`, and the tethered ingestor persists it atomically with the preview.
 */
class CopyMaskSkyAndTetherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private companion object {
        const val CAMERA_HANDLE = 0x10043
        val SOURCE_MASK = "edits/999/sky_mask_${"d".repeat(64)}.png"
    }

    @Test
    fun shouldCopySkyParametersAndClearTheTargetMask_whenPastingAcrossPhotos() {
        val source = EditGraph(
            sky = SkyOps(
                maskRef = SOURCE_MASK,
                assetType = SkyAssetType.PROCEDURAL,
                assetIdOrRelPath = "sunset_glow",
                relight = 42,
                horizonOffset = 0.12f,
                horizonFeather = 30,
                edgeShift = -1
            )
        )
        val target = EditGraph(
            sky = SkyOps(
                maskRef = "edits/7/sky_mask_${"a".repeat(64)}.png",
                assetType = SkyAssetType.CUSTOM,
                assetSha256 = "b".repeat(64)
            )
        )

        val pasted = CopyMask(includeAiSky = true).apply(source, target)

        assertEquals(SkyAssetType.PROCEDURAL, pasted.sky.assetType)
        assertEquals("sunset_glow", pasted.sky.assetIdOrRelPath)
        assertEquals(42, pasted.sky.relight)
        assertEquals(0.12f, pasted.sky.horizonOffset, 1e-6f)
        assertEquals(30, pasted.sky.horizonFeather)
        assertEquals(-1, pasted.sky.edgeShift)
        assertEquals("the target must recompute its own horizon", "", pasted.sky.maskRef)
    }

    @Test
    fun shouldKeepTheTargetSky_whenTheSkyAxisIsDisabled() {
        val source = EditGraph(sky = SkyOps(assetType = SkyAssetType.PROCEDURAL))
        val target = EditGraph(sky = SkyOps(assetType = SkyAssetType.CUSTOM, assetSha256 = "c".repeat(64)))

        val pasted = CopyMask(includeAiSky = false).apply(source, target)

        assertEquals(SkyAssetType.CUSTOM, pasted.sky.assetType)
    }

    @Test
    fun shouldDropTheMaskButKeepTheParameters_whenExtractingAPreset() {
        val graph = EditGraph(
            sky = SkyOps(
                maskRef = SOURCE_MASK,
                assetType = SkyAssetType.PROCEDURAL,
                assetIdOrRelPath = "blue_hour",
                relight = 70
            )
        )

        val preset = PresetGraphMerger.extractPreset(graph)

        assertEquals("", preset.sky.maskRef)
        assertEquals(SkyAssetType.PROCEDURAL, preset.sky.assetType)
        assertEquals(70, preset.sky.relight)
        assertTrue(!PresetGraphMerger.hasNeutralPresetAxes(graph))
    }

    @Test
    fun shouldComputeTheTargetSkyMaskAndCommitIt_whenATetheredSkyPresetArrives() = runBlocking {
        val store = FakeEditStore(
            preset = preset("sky", skyGraph()),
            activePresetId = "sky"
        )
        val committer = SkyRecordingCommitter()
        var aiRenders = 0
        val staged = File(temporaryFolder.root, "sky_preview.jpg").apply { createNewFile() }
        val ingestor = TetheredPhotoIngestor(
            editStore = store,
            committer = committer,
            stagingDir = temporaryFolder.root,
            renderPreview = { _, _, _, _ -> staged },
            skyPreparer = object : TetheredSkyPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSkyPreparation = TetheredSkyPreparation(mask(), byteArrayOf(7, 7))
            },
            renderPreviewWithAi = { _, _, _, _, _, _ ->
                aiRenders++
                staged
            }
        )

        val result = ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(1, aiRenders)
        assertEquals(0, committer.plainCommits)
        assertEquals(1, committer.aiCommits)
        assertTrue(byteArrayOf(7, 7).contentEquals(committer.skyMaskPayload ?: ByteArray(0)))
        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
        assertEquals(SkyAssetType.PROCEDURAL, committed.sky.assetType)
        assertEquals("sunset_glow", committed.sky.assetIdOrRelPath)
        assertEquals(55, committed.sky.relight)
        assertEquals("", committed.sky.maskRef)
        assertTrue((result as TetheredIngestResult.Ingested).hasEdit)
    }

    @Test
    fun shouldDegradeToTheTraditionalGrade_whenTheSkySegmenterFindsNoSky() = runBlocking {
        val store = FakeEditStore(
            preset = preset("sky", skyGraph()),
            activePresetId = "sky"
        )
        val committer = SkyRecordingCommitter()
        val staged = File(temporaryFolder.root, "sky_plain.jpg").apply { createNewFile() }
        val ingestor = TetheredPhotoIngestor(
            editStore = store,
            committer = committer,
            stagingDir = temporaryFolder.root,
            renderPreview = { _, _, _, _ -> staged },
            skyPreparer = object : TetheredSkyPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSkyPreparation? = null
            }
        )

        ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(0, committer.aiCommits)
        assertEquals(1, committer.plainCommits)
        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
        assertEquals(SkyOps(), committed.sky)
        assertEquals(0.6f, committed.tone.exposure, 1e-4f)
    }

    @Test
    fun shouldFallBack_whenTheSkyMaskCannotBeStored() = runBlocking {
        val store = FakeEditStore(
            preset = preset("sky", skyGraph()),
            activePresetId = "sky"
        )
        val committer = SkyStoreFailingCommitter()
        val aiPreview = File(temporaryFolder.root, "sky_ai.jpg").apply { createNewFile() }
        val fallbackPreview = File(temporaryFolder.root, "sky_fallback.jpg").apply { createNewFile() }
        val ingestor = TetheredPhotoIngestor(
            editStore = store,
            committer = committer,
            stagingDir = temporaryFolder.root,
            renderPreview = { _, _, _, _ -> fallbackPreview },
            skyPreparer = object : TetheredSkyPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSkyPreparation = TetheredSkyPreparation(mask(), byteArrayOf(3))
            },
            renderPreviewWithAi = { _, _, _, _, _, _ -> aiPreview }
        )

        val result = ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(1, committer.aiAttempts)
        assertEquals(1, committer.plainCommits)
        assertTrue("the abandoned AI preview must be removed", !aiPreview.exists())
        val committed = checkNotNull(EditGraphJsonCodec.decode(committer.graphJson ?: ""))
        assertEquals(SkyOps(), committed.sky)
        assertTrue((result as TetheredIngestResult.Ingested).hasEdit)
    }

    @Test
    fun shouldSkipThePreparer_whenThePresetCarriesNoSkyOps() = runBlocking {
        val store = FakeEditStore(
            preset = preset("plain", EditGraph(tone = ToneOps(contrast = 4))),
            activePresetId = "plain"
        )
        val committer = SkyRecordingCommitter()
        var prepared = 0
        val staged = File(temporaryFolder.root, "plain.jpg").apply { createNewFile() }
        val ingestor = TetheredPhotoIngestor(
            editStore = store,
            committer = committer,
            stagingDir = temporaryFolder.root,
            renderPreview = { _, _, _, _ -> staged },
            skyPreparer = object : TetheredSkyPresetPreparer {
                override suspend fun prepare(
                    sourcePath: String,
                    isRaw: Boolean,
                    graph: EditGraph
                ): TetheredSkyPreparation? {
                    prepared++
                    return TetheredSkyPreparation(mask(), byteArrayOf(1))
                }
            }
        )

        ingestor.onFileDownloaded(sourceFile(), CAMERA_HANDLE, isRaw = true, isJpeg = false)

        assertEquals(0, prepared)
        assertEquals(1, committer.plainCommits)
    }

    private fun skyGraph(): EditGraph = EditGraph(
        tone = ToneOps(exposure = 0.6f),
        sky = SkyOps(
            assetType = SkyAssetType.PROCEDURAL,
            assetIdOrRelPath = "sunset_glow",
            relight = 55,
            horizonFeather = 20
        )
    )

    private fun preset(id: String, graph: EditGraph): UserPresetRecord = UserPresetRecord(
        id = id,
        name = id,
        graphSubsetJson = EditGraphJsonCodec.encode(graph),
        isBuiltin = true
    )

    private fun mask(): SkyMask = SkyMask(2, 2, FloatArray(4) { 1.0f }, coverageRatio = 1.0f)

    private fun sourceFile(): File =
        File(temporaryFolder.root, "DSC09002.ARW").apply { createNewFile() }

    private class FakeEditStore(
        private val preset: UserPresetRecord,
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
        override fun observePresets(): Flow<List<UserPresetRecord>> = flowOf(listOf(preset))
        override suspend fun listPresets(): List<UserPresetRecord> = listOf(preset)
        override suspend fun getPreset(id: String): UserPresetRecord? = preset.takeIf { it.id == id }
        override suspend fun savePreset(preset: UserPresetRecord) = Unit
        override suspend fun renamePreset(id: String, newName: String) = Unit
        override suspend fun deletePreset(id: String) = Unit
        override fun getLiveTetherPresetId(): String? = activePresetId
        override fun setLiveTetherPresetId(presetId: String?) {
            activePresetId = presetId
        }
        override fun observeLiveTetherPresetId(): Flow<String?> = flowOf(activePresetId)
    }

    private class SkyRecordingCommitter : TetheredPhotoCommitter {
        var plainCommits = 0
        var aiCommits = 0
        var graphJson: String? = null
        var skyMaskPayload: ByteArray? = null

        override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long {
            plainCommits++
            this.graphJson = graphJson
            return 11L
        }

        override suspend fun commitWithAi(
            photo: PhotoEntity,
            stagedPreview: File?,
            graphJson: String?,
            subjectMaskPayload: ByteArray?,
            skyMaskPayload: ByteArray?
        ): Long {
            aiCommits++
            this.graphJson = graphJson
            this.skyMaskPayload = skyMaskPayload
            return 11L
        }
    }

    private class SkyStoreFailingCommitter : TetheredPhotoCommitter {
        var aiAttempts = 0
        var plainCommits = 0
        var graphJson: String? = null

        override suspend fun commit(photo: PhotoEntity, stagedPreview: File?, graphJson: String?): Long {
            plainCommits++
            this.graphJson = graphJson
            return 12L
        }

        override suspend fun commitWithAi(
            photo: PhotoEntity,
            stagedPreview: File?,
            graphJson: String?,
            subjectMaskPayload: ByteArray?,
            skyMaskPayload: ByteArray?
        ): Long {
            aiAttempts++
            throw SkyMaskUnavailableException()
        }
    }
}
