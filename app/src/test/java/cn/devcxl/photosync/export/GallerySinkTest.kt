package cn.devcxl.photosync.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySinkTest {

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

    private class FakeLegacyDirectoryBackend : LegacyDirectoryBackend<String> {
        val events = mutableListOf<String>()
        val tempBuffer = ByteArrayOutputStream()
        var committedBytes: ByteArray? = null
        val exifStore = mutableMapOf<String, String?>()
        var tempExists = false

        override fun openTempWriteStream(displayName: String): OutputStream {
            events += "openTemp:$displayName.tmp"
            tempExists = true
            return object : OutputStream() {
                override fun write(b: Int) {
                    tempBuffer.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    tempBuffer.write(b, off, len)
                }

                override fun close() {
                    events += "closeTemp:$displayName.tmp"
                }
            }
        }

        override fun applyExifToTemp(displayName: String, block: (ExifTagWriter) -> Unit) {
            events += "applyExifToTemp:$displayName.tmp"
            val writer = object : ExifTagWriter {
                override fun setAttribute(tag: String, value: String?) {
                    exifStore[tag] = value
                }

                override fun setLatLong(latitude: Double, longitude: Double) {}
                override fun setAltitude(altitude: Double) {}
            }
            block(writer)
        }

        override fun commitTempAndAnnounce(displayName: String, mime: String): String {
            events += "renameTempToFinal:$displayName"
            events += "announceMediaStore:$displayName:$mime"
            committedBytes = tempBuffer.toByteArray()
            tempExists = false
            return "content://media/external/images/media/99"
        }

        override fun deleteTemp(displayName: String) {
            events += "deleteTemp:$displayName.tmp"
            tempExists = false
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
    fun shouldWriteToTempApplyExifAndAtomicallyCommit_whenSavingOnLegacyApi24To28() {
        val backend = FakeLegacyDirectoryBackend()

        val result = saveViaLegacyBackend(
            backend = backend,
            displayName = "IMG_0001_export.jpg",
            mime = "image/jpeg",
            write = { out -> out.write("JPEG_BYTES".toByteArray()) },
            writeExif = { exif -> exif.setAttribute("Orientation", "1") }
        )

        assertEquals("content://media/external/images/media/99", result)
        assertEquals("JPEG_BYTES", String(backend.committedBytes!!))
        assertEquals("1", backend.exifStore["Orientation"])
        assertFalse(backend.tempExists)
        assertEquals(
            listOf(
                "openTemp:IMG_0001_export.jpg.tmp",
                "closeTemp:IMG_0001_export.jpg.tmp",
                "applyExifToTemp:IMG_0001_export.jpg.tmp",
                "renameTempToFinal:IMG_0001_export.jpg",
                "announceMediaStore:IMG_0001_export.jpg:image/jpeg"
            ),
            backend.events
        )
    }

    @Test
    fun shouldCleanUpTempAndNotCommit_whenLegacyWriteFails() {
        val backend = FakeLegacyDirectoryBackend()

        val result = saveViaLegacyBackend(
            backend = backend,
            displayName = "IMG_FAIL.jpg",
            mime = "image/jpeg",
            write = { out ->
                out.write("HALF_WRITTEN".toByteArray())
                throw IOException("Disk full midway")
            },
            writeExif = null
        )

        assertNull(result)
        assertNull(backend.committedBytes)
        assertFalse(backend.tempExists)
        assertTrue(backend.events.contains("deleteTemp:IMG_FAIL.jpg.tmp"))
    }
}
