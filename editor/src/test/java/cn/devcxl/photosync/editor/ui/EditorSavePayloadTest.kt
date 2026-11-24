package cn.devcxl.photosync.editor.ui

import java.io.File
import java.nio.file.Files

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshCodec
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.LiquifySession

/**
 * Seam under test: how [EditorSaveCoordinator] treats the liquify payloads on disk.
 *
 * What a save may write is one thing; what it may destroy is another. Every test here fails without
 * the rule it names: a save that overwrites the payload the previous row verifies, that deletes the
 * payload of an edit it clears, of a deleted record, of the legacy fixed name, of another writer, or
 * that leaves a payload no row can read any more. The commit rules themselves — preview gating,
 * cancellation, source lease — are covered by [EditorSaveCoordinatorTest].
 */
class EditorSavePayloadTest {

    private val photoId = SAVE_TEST_PHOTO_ID
    private val tempDir: File = Files.createTempDirectory("editor-payload").toFile()
    private val meshStore = LiquifyMeshStore(tempDir)
    private val store = RecordingEditStore()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldKeepThePreviousPayload_whenTheSecondSaveFails() {
        val coordinator = coordinator()
        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) })
        val firstRef = firstPayloadRef()
        val firstBytes = fileOf(firstRef).readBytes()

        store.failWith = IllegalStateException("database is gone")
        val second = runBlocking {
            coordinator.persist(coordinator.captureStroked(meshStore, dragTo = 0.62f))
        }

        assertFalse(second)
        assertArrayEquals(
            "a failed save must not rewrite the payload the previous row verifies",
            firstBytes,
            fileOf(firstRef).readBytes()
        )
        assertNotNull(meshStore.read(firstRef))
    }

    @Test
    fun shouldKeepThePreviousPayload_whenTheGridIsClearedAndTheSaveFails() {
        val coordinator = coordinator()
        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) })
        val firstRef = firstPayloadRef()

        store.failWith = IllegalStateException("database is gone")
        val cleared = coordinator.captureSnapshot(
            graph = toneGraph(),
            liquify = LiquifySession(meshStore),
            portrait = null,
            hasSource = true
        )
        val persisted = runBlocking { coordinator.persist(cleared) }

        assertFalse(persisted)
        assertTrue(
            "clearing the grid must not delete the payload the old row references",
            fileOf(firstRef).isFile
        )
        assertNotNull(meshStore.read(firstRef))
    }

    @Test
    fun shouldKeepBothPayloadsReadable_whenASecondSaveCommits() {
        val coordinator = coordinator()
        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) })
        val firstRef = firstPayloadRef()
        val firstBytes = fileOf(firstRef).readBytes()

        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore, dragTo = 0.62f)) })
        val secondRef = firstPayloadRef()

        assertNotEquals(firstRef.meshRelPath, secondRef.meshRelPath)
        assertArrayEquals(
            "the superseded payload must stay byte-identical",
            firstBytes,
            fileOf(firstRef).readBytes()
        )
        assertTrue("the new payload must be written", fileOf(secondRef).isFile)
        assertEquals("both payloads must remain on disk", 2, payloadFiles(tempDir).size)
        assertNotNull(
            "the row that still verifies the old payload must keep loading it",
            meshStore.read(firstRef)
        )
        assertNotNull(meshStore.read(secondRef))
    }

    @Test
    fun shouldNamePayloadsWithTheWholeDigest_whenSaving() {
        val coordinator = coordinator()

        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) })
        val ref = firstPayloadRef()

        assertEquals(64, ref.sha256.length)
        assertEquals(
            "a truncated digest could let two grids share a path and overwrite each other",
            LiquifyMeshCodec.fileNameFor(ref.sha256),
            File(ref.meshRelPath).name
        )
    }

    @Test
    fun shouldKeepThePreviousPayload_whenTheEditBecomesIdentity() {
        val coordinator = coordinator()
        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) })
        val firstRef = firstPayloadRef()

        val identity = coordinator.captureSnapshot(
            graph = EditGraph(),
            liquify = LiquifySession(meshStore),
            portrait = null,
            hasSource = true
        )

        assertTrue(runBlocking { coordinator.persist(identity) })
        assertTrue(
            "deleting the record must not delete a payload another row or an export may still read",
            fileOf(firstRef).isFile
        )
        assertNotNull(meshStore.read(firstRef))
    }

    @Test
    fun shouldKeepTheLegacyPayload_whenAContentAddressedRowIsWritten() {
        val legacyPath = writeLegacyPayload(strokedLiquifySession(meshStore).mesh)
        val legacyBytes = fileOf(legacyPath).readBytes()
        val coordinator = coordinator()

        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) })

        assertArrayEquals(
            "rows written before content addressing must keep their payload",
            legacyBytes,
            fileOf(legacyPath).readBytes()
        )
        assertNotNull(
            meshStore.read(
                LiquifyRef(legacyPath, LiquifyMesh.GRID_SIZE, LiquifyMeshCodec.sha256Hex(legacyBytes))
            )
        )
        assertEquals(2, payloadFiles(tempDir).size)
    }

    @Test
    fun shouldLeaveAnotherWritersFilesAlone_whenSaving() {
        val directory = payloadDirectory(tempDir).apply { mkdirs() }
        val foreignPayload = File(directory, LiquifyMeshCodec.fileNameFor("f".repeat(64)))
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val foreignTemporary = File(directory, "liquify_128x128_inflight.bin.7f3a.tmp")
            .apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val coordinator = coordinator()

        assertTrue(runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) })

        assertTrue("another writer's payload must not be deleted", foreignPayload.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), foreignPayload.readBytes())
        assertTrue("another writer's temporary file must not be deleted", foreignTemporary.isFile)
        assertArrayEquals(byteArrayOf(4, 5, 6), foreignTemporary.readBytes())
    }

    @Test
    fun shouldReadAPayloadWrittenBeforeContentAddressing() {
        val mesh = strokedLiquifySession(meshStore).mesh
        val legacyPath = writeLegacyPayload(mesh)

        val restored = meshStore.read(
            LiquifyRef(
                meshRelPath = legacyPath,
                gridSize = LiquifyMesh.GRID_SIZE,
                sha256 = LiquifyMeshCodec.sha256Hex(fileOf(legacyPath).readBytes())
            )
        )

        assertNotNull("rows written before content addressing must keep loading", restored)
        // The payload is a half-float grid, so compare against the codec's own round trip.
        val expected = requireNotNull(LiquifyMeshCodec.decodeRaw(LiquifyMeshCodec.encodeRaw(mesh)))
        assertTrue(restored!!.contentEquals(expected))
    }

    private fun coordinator(): EditorSaveCoordinator = EditorSaveCoordinator(
        photoId = photoId,
        meshStore = meshStore,
        previewRenderer = store.previewRenderer { _, _, _ -> previewJpegFile(tempDir) },
        editStore = { store }
    )

    private fun firstPayloadRef(): LiquifyRef = store.requireSaved().graph.geometry.liquify!!

    private fun fileOf(relativePath: String): File = File(tempDir, relativePath)

    private fun fileOf(ref: LiquifyRef): File = fileOf(ref.meshRelPath)

    private fun writeLegacyPayload(mesh: LiquifyMesh): String {
        val relativePath = LiquifyMeshCodec.persistedRelativePath(photoId)
        val file = fileOf(relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(LiquifyMeshCodec.deflate(LiquifyMeshCodec.encodeRaw(mesh)))
        return relativePath
    }
}
