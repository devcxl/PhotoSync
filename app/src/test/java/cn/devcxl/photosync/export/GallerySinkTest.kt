package cn.devcxl.photosync.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GallerySinkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeScopedMediaStoreBackend : ScopedMediaStoreBackend<String> {
        val events = mutableListOf<String>()
        val writtenBytes = ByteArrayOutputStream()
        val exifStore = mutableMapOf<String, String?>()
        var failOnExifSave = false

        override fun insertPending(displayName: String, mime: String, relativePath: String): String {
            events += "insertPending:$displayName:$mime:$relativePath"
            return "content://media/external/images/media/42"
        }

        override fun openWriteStream(handle: String): OutputStream {
            events += "openWriteStream"
            return object : OutputStream() {
                override fun write(b: Int) {
                    writtenBytes.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    writtenBytes.write(b, off, len)
                }

                override fun close() {
                    events += "closeWriteStream"
                }
            }
        }

        override fun openRwAndSaveExif(handle: String, block: (ExifTagWriter) -> Unit) {
            events += "openRwForExif"
            val writer = object : ExifTagWriter {
                override fun setAttribute(tag: String, value: String?) {
                    exifStore[tag] = value
                }

                override fun setLatLong(latitude: Double, longitude: Double) {
                    exifStore["GPS"] = "$latitude,$longitude"
                }

                override fun setAltitude(altitude: Double) {
                    exifStore["ALT"] = altitude.toString()
                }
            }
            block(writer)
            if (failOnExifSave) {
                throw IOException("Simulated EXIF saveAttributes failure on FUSE FD")
            }
            events += "saveAttributesAndCloseRw"
        }

        override fun publishPending(handle: String) {
            events += "publishPending"
        }

        override fun deleteEntry(handle: String) {
            events += "deleteEntry"
        }
    }

    @Test
    fun shouldWriteStreamThenReopenRwForExifBeforePublishing_whenSavingOnApi29Plus() {
        val backend = FakeScopedMediaStoreBackend()
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())

        val result = saveViaScopedMediaStore(
            backend = backend,
            displayName = "DSC04821_export.jpg",
            mime = "image/jpeg",
            relativePath = "Pictures/PhotoSync",
            write = { out -> out.write(payload) },
            writeExif = { exif -> exif.setAttribute("Orientation", "1") }
        )

        assertEquals("content://media/external/images/media/42", result)
        assertArrayEquals(payload, backend.writtenBytes.toByteArray())
        assertEquals("1", backend.exifStore["Orientation"])
        assertEquals(
            listOf(
                "insertPending:DSC04821_export.jpg:image/jpeg:Pictures/PhotoSync",
                "openWriteStream",
                "closeWriteStream",
                "openRwForExif",
                "saveAttributesAndCloseRw",
                "publishPending"
            ),
            backend.events
        )
    }

    @Test
    fun shouldDeletePendingEntryAndNotPublish_whenExifWritebackFailsOnApi29Plus() {
        val backend = FakeScopedMediaStoreBackend().apply {
            failOnExifSave = true
        }

        val result = saveViaScopedMediaStore(
            backend = backend,
            displayName = "DSC04821_export.jpg",
            mime = "image/jpeg",
            relativePath = "Pictures/PhotoSync",
            write = { out -> out.write(byteArrayOf(1, 2, 3)) },
            writeExif = { exif -> exif.setAttribute("Orientation", "1") }
        )

        assertNull(result)
        assertTrue(backend.events.contains("deleteEntry"))
        assertFalse(backend.events.contains("publishPending"))
    }

    @Test
    fun shouldWriteToTempFileApplyExifAndAtomicallyRename_whenSavingOnLegacyApi24To28() {
        val targetDir = tempFolder.newFolder("Pictures", "PhotoSync")
        val appliedTags = mutableMapOf<String, String?>()
        var announcedPath: String? = null

        val result = saveViaLegacyDirectory(
            targetDir = targetDir,
            displayName = "IMG_0001_export.jpg",
            write = { out -> out.write("JPEG_BYTES".toByteArray()) },
            applyExifToFile = { tmpFile, block ->
                assertTrue("Temp file must exist during EXIF writeback", tmpFile.exists())
                assertTrue("Temp file must have .tmp suffix", tmpFile.name.endsWith(".tmp"))
                val writer = object : ExifTagWriter {
                    override fun setAttribute(tag: String, value: String?) {
                        appliedTags[tag] = value
                    }
                    override fun setLatLong(latitude: Double, longitude: Double) {}
                    override fun setAltitude(altitude: Double) {}
                }
                block(writer)
            },
            writeExif = { exif -> exif.setAttribute("Orientation", "1") },
            announceFile = { finalFile ->
                announcedPath = finalFile.absolutePath
                "content://media/external/images/media/99"
            }
        )

        assertEquals("content://media/external/images/media/99", result)
        val finalFile = File(targetDir, "IMG_0001_export.jpg")
        assertTrue(finalFile.exists())
        assertEquals("JPEG_BYTES", finalFile.readText())
        assertEquals("1", appliedTags["Orientation"])
        assertEquals(finalFile.absolutePath, announcedPath)
        assertTrue(
            "No .tmp file should remain after atomic rename",
            targetDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") }
        )
    }

    @Test
    fun shouldCleanUpTempFileAndLeaveNoCorruptTarget_whenLegacyWriteOrExifFails() {
        val targetDir = tempFolder.newFolder("Pictures", "PhotoSync_Fail")

        val result = saveViaLegacyDirectory<String>(
            targetDir = targetDir,
            displayName = "IMG_FAIL.jpg",
            write = { out ->
                out.write("HALF_WRITTEN".toByteArray())
                throw IOException("Disk full midway")
            },
            applyExifToFile = { _, _ -> },
            writeExif = null,
            announceFile = { "should_not_be_called" }
        )

        assertNull(result)
        assertEquals(0, targetDir.listFiles().orEmpty().size)
    }
}
