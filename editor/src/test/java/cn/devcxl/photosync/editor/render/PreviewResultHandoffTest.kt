package cn.devcxl.photosync.editor.render

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [handOffPreviewFile] — a render result must reach the caller exactly once, and a
 * file that cannot be delivered (cancelled caller, no caller at all) must be deleted instead of
 * leaking into the cache directory.
 */
class PreviewResultHandoffTest {

    @Test
    fun shouldDeliverTheFile_whenTheCallerIsStillWaiting() = runBlocking {
        val file = temporaryFile()

        val received = async {
            suspendCancellableCoroutine<File?> { continuation ->
                handOffPreviewFile(file, continuation)
            }
        }.await()

        assertEquals(file, received)
        assertTrue("a delivered result belongs to the caller", file.exists())
    }

    @Test
    fun shouldDeliverNull_whenTheRenderProducedNothing() = runBlocking {
        val received = async {
            suspendCancellableCoroutine<File?> { continuation ->
                handOffPreviewFile(null, continuation)
            }
        }.await()

        assertNull(received)
    }

    @Test
    fun shouldDeleteTheFile_whenTheCallerWasCancelledBeforeTheResultArrived() = runBlocking {
        val file = temporaryFile()
        val continuationRef = AtomicReference<CancellableContinuation<File?>?>()
        val waiter = launch {
            suspendCancellableCoroutine<File?> { continuation -> continuationRef.set(continuation) }
        }
        while (continuationRef.get() == null) yield()
        waiter.cancelAndJoin()

        val continuation = requireNotNull(continuationRef.get())
        assertFalse(continuation.isActive)
        handOffPreviewFile(file, continuation)

        assertFalse("a cancelled preview must not leave an orphan", file.exists())
    }

    @Test
    fun shouldDeleteTheFile_whenNoWaiterExistsAtAll() {
        val file = temporaryFile()

        handOffPreviewFile(file, continuation = null)

        assertFalse(file.exists())
    }

    @Test
    fun shouldNotTouchAnything_whenNothingWasRenderedAndNoWaiterExists() {
        // Must be a no-op rather than a null-pointer or a spurious deletion.
        handOffPreviewFile(null, continuation = null)
    }

    private fun temporaryFile(): File = File.createTempFile("preview_", ".jpg").apply {
        deleteOnExit()
        writeText("frame")
    }
}
