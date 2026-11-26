package cn.devcxl.photosync.editor.domain

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Seam under test: [SkyCustomAssetStore] — SAF sky import, content addressing, long-edge cap and
 * per-asset deletion.
 */
class SkyCustomAssetStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shouldStoreAJPegByItsDigest_whenTheEdgeFitsTheBudget() {
        val store = SkyCustomAssetStore(temporaryFolder.root)
        val jpeg = jpegOf(1920, 1080)

        val stored = store.importJpeg(jpeg, 1920, 1080)

        assertNotNull(stored)
        val asset = checkNotNull(stored)
        assertEquals("skies/sky_${asset.sha256}.jpg", asset.relativePath)
        assertTrue(File(temporaryFolder.root, asset.relativePath).isFile)
        assertTrue(store.read(asset.sha256)?.contentEquals(jpeg) == true)
    }

    @Test
    fun shouldRejectAnAsset_whenTheLongEdgeExceeds4096Pixels() {
        val store = SkyCustomAssetStore(temporaryFolder.root)

        val stored = store.importJpeg(jpegOf(8192, 4096), 8192, 4096)

        assertNull(stored)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun shouldRejectBytesThatAreNotJPeg_whenImporting() {
        val store = SkyCustomAssetStore(temporaryFolder.root)

        val stored = store.importJpeg(byteArrayOf(1, 2, 3, 4), 100, 100)

        assertNull(stored)
    }

    @Test
    fun shouldReuseTheSameFile_whenTheSameJpegIsImportedTwice() {
        val store = SkyCustomAssetStore(temporaryFolder.root)
        val jpeg = jpegOf(800, 600)

        val first = checkNotNull(store.importJpeg(jpeg, 800, 600))
        val second = checkNotNull(store.importJpeg(jpeg, 800, 600))

        assertEquals(first.sha256, second.sha256)
        assertEquals(1, store.list().size)
    }

    @Test
    fun shouldRefuseToRead_whenTheStoredBytesNoLongerMatchTheDigest() {
        val store = SkyCustomAssetStore(temporaryFolder.root)
        val asset = checkNotNull(store.importJpeg(jpegOf(640, 480), 640, 480))
        val file = checkNotNull(store.fileFor(asset.sha256))
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))

        assertNull(store.read(asset.sha256))
    }

    @Test
    fun shouldDeleteOnlyTheRequestedSky_whenTwoAssetsExist() {
        val store = SkyCustomAssetStore(temporaryFolder.root)
        val first = checkNotNull(store.importJpeg(jpegOf(320, 200), 320, 200))
        val second = checkNotNull(store.importJpeg(jpegOf(320, 240), 320, 240))

        assertTrue(store.delete(first.sha256))

        assertNull(store.fileFor(first.sha256)?.takeIf { it.exists() })
        assertTrue(store.contains(second.sha256))
        assertEquals(listOf(second.sha256), store.list().map { it.sha256 })
    }

    @Test
    fun shouldReportTheStoredDimensions_whenListingThePicker() {
        val store = SkyCustomAssetStore(temporaryFolder.root)
        val asset = checkNotNull(store.importJpeg(jpegOf(1024, 512), 1024, 512))

        val listed = store.list().single()

        assertEquals(asset.sha256, listed.sha256)
        assertEquals(1024, listed.width)
        assertEquals(512, listed.height)
    }

    /** Minimal JPEG: SOI, one SOF0 carrying [width] x [height], EOI. */
    private fun jpegOf(width: Int, height: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(),
        0x00, 0x11,
        0x08,
        (height ushr 8).toByte(), height.toByte(),
        (width ushr 8).toByte(), width.toByte(),
        0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        0xFF.toByte(), 0xD9.toByte()
    )
}
