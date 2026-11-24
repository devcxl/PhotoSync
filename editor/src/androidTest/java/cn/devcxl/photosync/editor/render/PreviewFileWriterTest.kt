package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewFileWriterTest {
    private val directory = File(
        ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "preview-writer-test-${java.util.UUID.randomUUID()}"
    )

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun shouldKeepPreviousPreview_whenWritingAnotherPreview() {
        val first = checkNotNull(PreviewFileWriter.write(directory) {
            it.write(1)
            true
        })
        val second = checkNotNull(PreviewFileWriter.write(directory) {
            it.write(2)
            true
        })

        assertNotEquals(first, second)
        assertEquals(1, File(first).readBytes().single().toInt())
        assertEquals(2, File(second).readBytes().single().toInt())
    }

    @Test
    fun shouldDeleteIncompletePreview_whenEncoderFails() {
        val first = checkNotNull(PreviewFileWriter.write(directory) {
            it.write(1)
            true
        })
        val result = PreviewFileWriter.write(directory) {
            it.write(2)
            false
        }

        assertNull(result)
        assertEquals(listOf(File(first)), directory.listFiles()?.toList())
    }

    @Test
    fun shouldDeleteIncompletePreview_whenEncoderThrows() {
        val first = checkNotNull(PreviewFileWriter.write(directory) {
            it.write(1)
            true
        })

        assertThrows(IOException::class.java) {
            PreviewFileWriter.write(directory) { throw IOException("encoder failed") }
        }
        assertEquals(listOf(File(first)), directory.listFiles()?.toList())
    }
}
