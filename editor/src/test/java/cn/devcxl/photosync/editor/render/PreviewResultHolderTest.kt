package cn.devcxl.photosync.editor.render

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PreviewResultHolder] — a preview written in the instant a timeout fires must be
 * deleted instead of leaking, and a file that reached the caller must never be deleted.
 */
class PreviewResultHolderTest {

    @Test
    fun shouldDeleteTheFile_whenTheDeadlineExpiredBeforeDelivery() {
        val file = File("preview_100.jpg")
        val deleted = mutableListOf<File>()
        val holder = PreviewResultHolder { deleted += it }

        holder.park(file)
        val result = holder.settle(delivered = null)

        assertNull(result)
        assertEquals(listOf(file), deleted)
    }

    @Test
    fun shouldKeepTheDeliveredFile_whenTheCallerReceivedIt() {
        val file = File("preview_101.jpg")
        val deleted = mutableListOf<File>()
        val holder = PreviewResultHolder { deleted += it }

        holder.park(file)
        val result = holder.settle(delivered = file)

        assertSame(file, result)
        assertTrue("a delivered file belongs to the caller", deleted.isEmpty())
    }

    @Test
    fun shouldDeleteAtOnce_whenTheFileArrivesAfterTheCallerSettled() {
        val late = File("preview_102.jpg")
        val deleted = mutableListOf<File>()
        val holder = PreviewResultHolder { deleted += it }

        holder.settle(delivered = null)
        holder.park(late)

        assertEquals(listOf(late), deleted)
    }

    @Test
    fun shouldDeleteOnlyOnce_whenTheHandoverAndTheSettleBothDiscard() {
        val file = File("preview_103.jpg")
        var deletions = 0
        val holder = PreviewResultHolder { deletions++ }

        holder.park(file)
        holder.discardParked()
        holder.settle(delivered = null)

        assertEquals("the hand-over discard wins; settle must not delete twice", 1, deletions)
    }

    @Test
    fun shouldDoNothing_whenNoFileWasProduced() {
        var deletions = 0
        val holder = PreviewResultHolder { deletions++ }

        holder.park(null)
        holder.discardParked()
        holder.settle(delivered = null)

        assertEquals(0, deletions)
    }

    @Test
    fun shouldIgnoreRepeatedSettles_whenTheWaitAlreadyFinished() {
        val file = File("preview_104.jpg")
        val deletions = AtomicInteger()
        val holder = PreviewResultHolder { deletions.incrementAndGet() }

        holder.park(file)
        holder.settle(delivered = file)
        holder.settle(delivered = null)

        assertEquals(0, deletions.get())
    }
}
