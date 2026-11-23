package cn.devcxl.photosync.editor.ui

import java.util.Collections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Seam under test: [EditorFinishAction] — which outcomes may finish the editor activity, and that the
 * source lease a snapshot borrows is always returned.
 *
 * The rules under test are the ones a user can observe: Done freezes the session before any work
 * leaves the calling thread, a failed save keeps the editor open with the UI restored, an activity
 * teardown is never reported as a failed save or as a successful one, and no path leaks the bitmap
 * lease that keeps the staged source alive.
 */
class EditorFinishActionTest {

    @Test
    fun shouldFreezeTheSessionAndShowProgressBeforeTheSaveIsDispatched() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = {
                order += "snapshot"
                snapshot()
            },
            save = {
                order += "save"
                true
            },
            onSavingStarted = { order += "saving" },
            onSaved = { order += "saved" },
            onSaveFailed = { order += "failed" }
        )

        assertEquals(listOf("snapshot", "saving", "save", "saved"), order)
    }

    @Test
    fun shouldHandThePhotoBack_whenTheSaveSucceeded() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val outcome = CompletableDeferred<String>()

        val job = EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { snapshot() },
            save = { true },
            onSavingStarted = {},
            onSaved = { outcome.complete("saved") },
            onSaveFailed = { outcome.complete("failed") }
        )

        assertEquals("saved", await(outcome))
        assertTrue(job?.isCompleted == true)
    }

    @Test
    fun shouldRestoreTheUiAndStayOpen_whenTheSaveFailed() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val outcome = CompletableDeferred<String>()

        EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { snapshot() },
            save = { false },
            onSavingStarted = {},
            onSaved = { outcome.complete("saved") },
            onSaveFailed = { outcome.complete("failed") }
        )

        assertEquals("failed", await(outcome))
    }

    @Test
    fun shouldRestoreTheUi_whenTheSaveThrows() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val outcome = CompletableDeferred<String>()

        EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { snapshot() },
            save = { error("bitmap was recycled") },
            onSavingStarted = {},
            onSaved = { outcome.complete("saved") },
            onSaveFailed = { outcome.complete("failed") }
        )

        assertEquals("failed", await(outcome))
    }

    @Test
    fun shouldReportFailureWithoutSaving_whenTheSnapshotCannotBeCaptured() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val outcome = CompletableDeferred<String>()
        var savingShown = false

        val job = EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { error("out of memory copying the grid") },
            save = { outcome.complete("saved"); true },
            onSavingStarted = { savingShown = true },
            onSaved = { outcome.complete("saved") },
            onSaveFailed = { outcome.complete("failed") }
        )

        assertEquals("failed", await(outcome))
        assertNull(job)
        assertFalse("the spinner must not be shown for a capture that never started", savingShown)
    }

    @Test
    fun shouldReportFailureWithoutSaving_whenTheCaptureRunsOutOfMemory() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val outcome = CompletableDeferred<String>()

        val job = EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { throw OutOfMemoryError("128 KiB grid copy") },
            save = { outcome.complete("saved"); true },
            onSavingStarted = {},
            onSaved = { outcome.complete("saved") },
            onSaveFailed = { outcome.complete("failed") }
        )

        assertEquals("failed", await(outcome))
        assertNull(job)
    }

    @Test
    fun shouldReturnTheSourceLease_whenTheProgressCallbackThrows() {
        var releases = 0
        val frozen = leasedSnapshot { releases++ }
        var reported: String? = null

        val job = EditorFinishAction.saveAndFinish(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            snapshot = { frozen },
            save = { reported = "saved"; true },
            onSavingStarted = { error("the top bar is gone") },
            onSaved = { reported = "saved" },
            onSaveFailed = { reported = "failed" }
        )

        assertNull(job)
        assertEquals("failed", reported)
        assertEquals("the borrowed source pixels must be returned", 1, releases)
    }

    @Test
    fun shouldReturnTheSourceLease_whenTheScopeIsAlreadyCancelled() {
        var releases = 0
        var saveRan = false
        val frozen = leasedSnapshot { releases++ }
        val scope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Unconfined)

        val job = EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { frozen },
            save = { saveRan = true; true },
            onSavingStarted = {},
            onSaved = {},
            onSaveFailed = {}
        )

        assertTrue("a dead scope completes the job without running the body", job?.isCancelled == true)
        assertFalse(saveRan)
        assertEquals("a save that never ran must still return the lease", 1, releases)
    }

    @Test
    fun shouldCloseTheSnapshotOnce_whenTheSaveAndTheCompletionBothCloseIt() {
        var releases = 0
        val frozen = leasedSnapshot { releases++ }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val outcome = CompletableDeferred<Unit>()

        EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { frozen },
            save = {
                frozen.close()
                true
            },
            onSavingStarted = {},
            onSaved = { outcome.complete(Unit) },
            onSaveFailed = { outcome.complete(Unit) }
        )

        await(outcome)
        assertEquals(1, releases)
    }

    @Test
    fun shouldReturnTheSourceLease_whenTheSaveIsCancelled() {
        var releases = 0
        val frozen = leasedSnapshot { releases++ }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveEntered = CompletableDeferred<Unit>()

        val job = EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { frozen },
            save = {
                saveEntered.complete(Unit)
                CompletableDeferred<Boolean>().await()
            },
            onSavingStarted = {},
            onSaved = {},
            onSaveFailed = {}
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) { saveEntered.await() }
            scope.cancel()
            job?.join()
        }

        assertTrue(job?.isCancelled == true)
        assertEquals("a cancelled save must still return the lease", 1, releases)
    }

    @Test
    fun shouldNotFinishAndNotReportAFailure_whenTheActivityIsDestroyedMidSave() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saveEntered = CompletableDeferred<Unit>()
        var reported: String? = null

        val job = EditorFinishAction.saveAndFinish(
            scope = scope,
            snapshot = { snapshot() },
            save = {
                saveEntered.complete(Unit)
                CompletableDeferred<Boolean>().await()
            },
            onSavingStarted = {},
            onSaved = { reported = "saved" },
            onSaveFailed = { reported = "failed" }
        )

        runBlocking {
            withTimeout(TIMEOUT_MS) { saveEntered.await() }
            scope.cancel()
            job?.join()
        }

        assertTrue(job?.isCancelled == true)
        assertNull("a teardown must not be mistaken for a save outcome", reported)
    }

    private fun snapshot(): EditorSaveSnapshot = EditorSaveSnapshot(
        graph = EditGraph(),
        liquifyMesh = null,
        portrait = null,
        hasSource = true
    )

    private fun leasedSnapshot(onClose: () -> Unit): EditorSaveSnapshot = EditorSaveSnapshot(
        graph = EditGraph(),
        liquifyMesh = null,
        portrait = null,
        hasSource = true,
        releaseSourceLease = onClose
    )

    private fun await(outcome: CompletableDeferred<String>): String =
        runBlocking { withTimeout(TIMEOUT_MS) { outcome.await() } }

    private fun await(outcome: CompletableDeferred<Unit>) {
        runBlocking { withTimeout(TIMEOUT_MS) { outcome.await() } }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
