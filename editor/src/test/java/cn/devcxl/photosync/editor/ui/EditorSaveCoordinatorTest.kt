package cn.devcxl.photosync.editor.ui

import java.io.File
import java.nio.file.Files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Seam under test: the commit rules of [EditorSaveCoordinator] — what a Done action is allowed to
 * report and which state it may leave behind.
 *
 * Every test here fails without the rule it names: a snapshot that aliases the live liquify grid, a
 * row committed without a preview, a store written after a mesh failure, a cancellation reported as
 * a failed save, a save that runs on the caller's thread, or source pixels recycled under a running
 * save. Payload file lifetime is covered by [EditorSavePayloadTest].
 */
class EditorSaveCoordinatorTest {

    private val photoId = SAVE_TEST_PHOTO_ID
    private val tempDir: File = Files.createTempDirectory("editor-save").toFile()
    private val meshStore = LiquifyMeshStore(tempDir)
    private val store = RecordingEditStore()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldCopyTheDisplacementGrid_whenTheSnapshotIsCaptured() {
        val session = strokedLiquifySession(meshStore)
        val expected = session.mesh.copy()
        val coordinator = coordinator()

        val snapshot = coordinator.captureSnapshot(
            graph = toneGraph(),
            liquify = session,
            portrait = null,
            hasSource = true
        )
        session.reset()

        val frozen = snapshot.liquifyMesh
        assertNotNull(frozen)
        assertTrue(frozen!!.contentEquals(expected))
        assertFalse(session.hasMesh)
    }

    @Test
    fun shouldClearTheLiquifyReference_whenTheCapturedGridIsEmpty() {
        val coordinator = coordinator()

        val snapshot = coordinator.captureSnapshot(
            graph = toneGraph(),
            liquify = LiquifySession(meshStore),
            portrait = null,
            hasSource = true
        )

        assertNull(snapshot.liquifyMesh)
        assertTrue(runBlocking { coordinator.persist(snapshot) })
        assertNull(store.requireSaved().graph.geometry.liquify)
    }

    @Test
    fun shouldNotCommitAnEditWithoutPreview_whenThePreviewCannotBeRendered() {
        var renderCount = 0
        val coordinator = coordinator(
            renderPreview = { _, _, _ ->
                renderCount++
                null
            }
        )

        val persisted = runBlocking { coordinator.persist(snapshot(toneGraph())) }

        assertFalse(persisted)
        assertEquals(1, renderCount)
        assertEquals(0, store.commits.size)
    }

    @Test
    fun shouldNotCommitAnEditWithoutPreview_whenTheSourceIsGone() {
        var renderCount = 0
        val coordinator = coordinator(
            renderPreview = { _, _, _ ->
                renderCount++
                previewJpegFile(tempDir)
            }
        )

        val persisted = runBlocking {
            coordinator.persist(snapshot(toneGraph(), hasSource = false))
        }

        assertFalse(persisted)
        assertEquals(0, renderCount)
        assertEquals(0, store.commits.size)
    }

    @Test
    fun shouldDeleteTheRecordWithoutRenderingAPreview_whenTheEditIsIdentity() {
        var renderCount = 0
        val coordinator = coordinator(
            renderPreview = { _, _, _ ->
                renderCount++
                previewJpegFile(tempDir)
            }
        )

        val persisted = runBlocking { coordinator.persist(snapshot(EditGraph())) }

        assertTrue(persisted)
        assertEquals(0, renderCount)
        val saved = store.requireSaved()
        assertNull(saved.previewCachePath)
        assertTrue(saved.graph.isIdentity())
    }

    @Test
    fun shouldFeedTheComposedWarpGridToThePreview_whenTheEditHasBrushStrokes() {
        val coordinator = coordinator()
        val snapshot = coordinator.captureSnapshot(
            graph = toneGraph(),
            liquify = strokedLiquifySession(meshStore),
            portrait = null,
            hasSource = true
        )

        assertTrue(runBlocking { coordinator.persist(snapshot) })

        val warpField = store.previewedWarpField
        assertNotNull(warpField)
        assertFalse(warpField!!.isZero())
        assertNotNull(store.requireSaved().graph.geometry.liquify)
    }

    @Test
    fun shouldRenderThePreviewBeforeWritingThePayload_whenSaving() {
        var payloadExistedDuringRender = true
        var payloadExistedAtCommit = false
        store.onSave = { payloadExistedAtCommit = payloadFiles(tempDir).isNotEmpty() }
        val coordinator = coordinator(
            renderPreview = { _, _, _ ->
                payloadExistedDuringRender = payloadFiles(tempDir).isNotEmpty()
                previewJpegFile(tempDir)
            }
        )

        val persisted = runBlocking { coordinator.persist(coordinator.captureStroked(meshStore)) }

        assertTrue(persisted)
        assertFalse("the preview must not run after the payload it would reference", payloadExistedDuringRender)
        assertTrue("the payload must be on disk before the row that references it", payloadExistedAtCommit)
    }

    @Test
    fun shouldKeepThePreviousRecord_whenTheMeshCannotBeWritten() {
        val blocked = File(tempDir, "not-a-directory").apply { writeText("blocked") }
        val blockedStore = LiquifyMeshStore(blocked)
        val coordinator = EditorSaveCoordinator(
            photoId = photoId,
            meshStore = blockedStore,
            previewRenderer = store.previewRenderer { _, _, _ -> previewJpegFile(tempDir) },
            editStore = { store }
        )
        val snapshot = coordinator.captureSnapshot(
            graph = toneGraph(),
            liquify = strokedLiquifySession(blockedStore),
            portrait = null,
            hasSource = true
        )

        val persisted = runBlocking { coordinator.persist(snapshot) }

        assertFalse(persisted)
        assertEquals(0, store.commits.size)
    }

    @Test
    fun shouldNotCommit_whenNoEditStoreIsRegistered() {
        val coordinator = coordinator(editStore = { null })

        val persisted = runBlocking { coordinator.persist(snapshot(toneGraph())) }

        assertFalse(persisted)
    }

    @Test
    fun shouldPropagateCancellation_whenTheStoreIsCancelledMidSave() {
        val entered = CompletableDeferred<Unit>()
        store.saveEntered = entered
        store.saveGate = CompletableDeferred()
        val coordinator = coordinator()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var returned: Boolean? = null
        val save = scope.async {
            coordinator.persist(coordinator.captureStroked(meshStore)).also { returned = it }
        }

        runBlocking {
            withTimeout(SAVE_TEST_TIMEOUT_MS) { entered.await() }
            scope.cancel()
            save.join()
        }

        assertTrue(save.isCancelled)
        assertNull("a cancelled save must not report a boolean outcome", returned)
        assertTrue(
            "the payload written before the cancelled commit stays readable",
            payloadFiles(tempDir).isNotEmpty()
        )
    }

    @Test
    fun shouldRecycleTheSourceOnlyAfterTheSaveFinished_whenTheActivityIsDestroyed() {
        val released = CompletableDeferred<Unit>()
        val coordinator = coordinator()
        val snapshot = coordinator.captureStroked(meshStore)

        coordinator.releasePreviewSource { released.complete(Unit) }

        assertFalse("teardown must wait for the running save", released.isCompleted)
        runBlocking { assertTrue(coordinator.persist(snapshot)) }
        assertTrue("teardown must run once the save returned the lease", released.isCompleted)
    }

    @Test
    fun shouldRunTheReleaseImmediately_whenNoSaveHoldsTheLease() {
        var released = false

        coordinator().releasePreviewSource { released = true }

        assertTrue(released)
    }

    @Test
    fun shouldDeferUntilEveryReaderReturnsTheLease_whenTwoSavesShareTheSource() {
        var released = false
        val lease = EditorSaveSourceLease()

        lease.acquire()
        lease.acquire()
        lease.releaseWhenFree { released = true }
        assertFalse(released)

        lease.release()
        assertFalse(released)

        lease.release()
        assertTrue(released)
    }

    @Test
    fun shouldReleaseItsSourceLeaseOnce_whenClosedTwice() {
        var releases = 0
        val snapshot = EditorSaveSnapshot(
            graph = EditGraph(),
            liquifyMesh = null,
            portrait = null,
            hasSource = true,
            releaseSourceLease = { releases++ }
        )

        snapshot.close()
        snapshot.close()

        assertEquals(1, releases)
    }

    private fun coordinator(
        renderPreview: (EditGraph, WarpDisplacementField?, FloatArray?) -> String? = { _, _, _ ->
            previewJpegFile(tempDir)
        },
        editStore: () -> EditStore? = { store }
    ): EditorSaveCoordinator = EditorSaveCoordinator(
        photoId = photoId,
        meshStore = meshStore,
        previewRenderer = store.previewRenderer(renderPreview),
        editStore = editStore
    )

    private fun snapshot(
        graph: EditGraph,
        hasSource: Boolean = true
    ): EditorSaveSnapshot = EditorSaveSnapshot(
        graph = graph,
        liquifyMesh = null,
        portrait = null,
        hasSource = hasSource
    )
}
