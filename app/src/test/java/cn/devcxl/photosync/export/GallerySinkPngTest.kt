package cn.devcxl.photosync.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.contract.ExifTagWriter
import cn.devcxl.photosync.editor.contract.GallerySink

/**
 * PNG-specific guarantees of the gallery publishing paths: the MIME type reaches `MediaStore`,
 * the bytes are written unchanged, and half-written entries are removed on failure.
 */
class GallerySinkPngTest {

    private class FakeScopedBackend : ScopedMediaStoreBackend<String> {
        val events = mutableListOf<String>()
        val writtenBytes = ByteArrayOutputStream()
        var failWrite = false

        override fun insertPending(displayName: String, mime: String, relativePath: String): String {
            events += "insertPending:$displayName:$mime:$relativePath"
            return "content://media/external/images/media/77"
        }

        override fun openWriteStream(handle: String): OutputStream {
            events += "openWriteStream"
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (failWrite) throw IOException("PNG write failed midway")
                    writtenBytes.write(value)
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (failWrite) throw IOException("PNG write failed midway")
                    writtenBytes.write(bytes, offset, length)
                }

                override fun close() {
                    events += "closeWriteStream"
                }
            }
        }

        override fun openRwAndSaveExif(handle: String, block: (ExifTagWriter) -> Unit) {
            events += "openRwForExif"
        }

        override fun publishPending(handle: String) {
            events += "publishPending"
        }

        override fun deleteEntry(handle: String) {
            events += "deleteEntry"
        }
    }

    private class FakeLegacyBackend : LegacyDirectoryBackend<String> {
        val events = mutableListOf<String>()
        val tempBytes = ByteArrayOutputStream()
        var committedBytes: ByteArray? = null
        var tempExists = false
        var failWrite = false

        override fun openTempWriteStream(displayName: String): OutputStream {
            events += "openTemp:$displayName.tmp"
            tempExists = true
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (failWrite) throw IOException("PNG temp write failed")
                    tempBytes.write(value)
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (failWrite) throw IOException("PNG temp write failed")
                    tempBytes.write(bytes, offset, length)
                }

                override fun close() {
                    events += "closeTemp:$displayName.tmp"
                }
            }
        }

        override fun applyExifToTemp(displayName: String, block: (ExifTagWriter) -> Unit) {
            events += "applyExifToTemp:$displayName.tmp"
        }

        override fun commitTempAndAnnounce(displayName: String, mime: String): String {
            events += "renameTempToFinal:$displayName"
            events += "announceMediaStore:$displayName:$mime"
            committedBytes = tempBytes.toByteArray()
            tempExists = false
            return "content://media/external/images/media/88"
        }

        override fun deleteTemp(displayName: String) {
            events += "deleteTemp:$displayName.tmp"
            tempExists = false
        }
    }

    @Test
    fun shouldPublishPngWithImagePngMime_whenSavingOnApi29Plus() {
        val backend = FakeScopedBackend()
        val payload = pngHeaderBytes()

        val result = saveViaScopedMediaStore(
            backend = backend,
            displayName = "DSC04821_edited.png",
            mime = GallerySink.PNG_MIME,
            relativePath = "Pictures/PhotoSync",
            write = { output -> output.write(payload) },
            writeExif = null
        )

        assertEquals("content://media/external/images/media/77", result)
        assertArrayEquals(payload, backend.writtenBytes.toByteArray())
        assertEquals(
            listOf(
                "insertPending:DSC04821_edited.png:image/png:Pictures/PhotoSync",
                "openWriteStream",
                "closeWriteStream",
                "publishPending"
            ),
            backend.events
        )
    }

    @Test
    fun shouldDeletePendingPngEntryAndNotPublish_whenWritingFailsOnApi29Plus() {
        val backend = FakeScopedBackend().apply { failWrite = true }

        val result = saveViaScopedMediaStore(
            backend = backend,
            displayName = "DSC04821_edited.png",
            mime = GallerySink.PNG_MIME,
            relativePath = "Pictures/PhotoSync",
            write = { output -> output.write(pngHeaderBytes()) },
            writeExif = null
        )

        assertNull(result)
        assertTrue(backend.events.contains("deleteEntry"))
        assertFalse(backend.events.contains("publishPending"))
    }

    @Test
    fun shouldDeletePendingPngEntryAndNotPublish_whenWritingIsCancelledOnApi29Plus() {
        val backend = FakeScopedBackend()

        val result = saveViaScopedMediaStore(
            backend = backend,
            displayName = "DSC04821_edited.png",
            mime = GallerySink.PNG_MIME,
            relativePath = "Pictures/PhotoSync",
            write = { throw CancellationException("Bake cancelled midway") },
            writeExif = null
        )

        assertNull(result)
        assertTrue(backend.events.contains("deleteEntry"))
        assertFalse(backend.events.contains("publishPending"))
    }

    @Test
    fun shouldAtomicallyRenamePngTempAndAnnounceImagePng_whenSavingOnLegacyApi24To28() {
        val backend = FakeLegacyBackend()
        val payload = pngHeaderBytes()

        val result = saveViaLegacyBackend(
            backend = backend,
            displayName = "IMG_0001_edited.png",
            mime = GallerySink.PNG_MIME,
            write = { output -> output.write(payload) },
            writeExif = null
        )

        assertEquals("content://media/external/images/media/88", result)
        assertArrayEquals(payload, backend.committedBytes)
        assertFalse(backend.tempExists)
        assertEquals(
            listOf(
                "openTemp:IMG_0001_edited.png.tmp",
                "closeTemp:IMG_0001_edited.png.tmp",
                "renameTempToFinal:IMG_0001_edited.png",
                "announceMediaStore:IMG_0001_edited.png:image/png"
            ),
            backend.events
        )
    }

    @Test
    fun shouldDeletePngTempAndNotAnnounce_whenLegacyWriteFails() {
        val backend = FakeLegacyBackend().apply { failWrite = true }

        val result = saveViaLegacyBackend(
            backend = backend,
            displayName = "IMG_FAIL_edited.png",
            mime = GallerySink.PNG_MIME,
            write = { output -> output.write(pngHeaderBytes()) },
            writeExif = null
        )

        assertNull(result)
        assertNull(backend.committedBytes)
        assertFalse(backend.tempExists)
        assertTrue(backend.events.contains("deleteTemp:IMG_FAIL_edited.png.tmp"))
        assertFalse(backend.events.any { it.startsWith("announceMediaStore") })
    }

    private fun pngHeaderBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44
    )
}
