package cn.devcxl.photosync.ptp.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class DownloadNamingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shouldKeepTheCameraName_whenItIsAlreadySafe() {
        assertEquals("IMG_0550.JPG", DownloadNaming.sanitize("IMG_0550.JPG"))
    }

    @Test
    fun shouldReplacePathSeparators_andCharactersWindowsRejects() {
        assertEquals("DCIM_100CANON_A_B_.JPG", DownloadNaming.sanitize("DCIM/100CANON\\A:B?.JPG"))
    }

    @Test
    fun shouldTrimSurroundingWhitespace() {
        assertEquals("IMG_1.JPG", DownloadNaming.sanitize("  IMG_1.JPG  "))
    }

    @Test
    fun shouldReturnNull_whenTheCameraDidNotNameTheObject() {
        assertNull(DownloadNaming.sanitize(null))
        assertNull(DownloadNaming.sanitize("   "))
    }

    @Test
    fun shouldReturnNull_whenTheNameWouldEscapeTheDirectory() {
        // ".." survives sanitizing because it holds no forbidden character, and
        // File(directory, "..") is the parent directory.
        assertNull(DownloadNaming.sanitize(".."))
        assertNull(DownloadNaming.sanitize("."))
    }

    @Test
    fun shouldKeepName_whenOnlySomeCharactersWereReplaced() {
        assertEquals("_hidden", DownloadNaming.sanitize("/hidden"))
    }

    @Test
    fun shouldGenerateADistinctFallbackName() {
        val random = Random(7)

        val first = DownloadNaming.generatedName(random)
        val second = DownloadNaming.generatedName(random)

        assertTrue(first.startsWith("photo_"))
        assertTrue(first.endsWith(".jpg"))
        assertTrue(first != second)
    }

    @Test
    fun shouldUseTheName_whenNoFileExistsYet() {
        val directory = temporaryFolder.newFolder()

        val file = DownloadNaming.uniqueFile(directory, "IMG_0001.JPG")

        assertEquals("IMG_0001.JPG", file.name)
    }

    @Test
    fun shouldAppendSuffix_beforeTheExtension_whenTheNameIsTaken() {
        val directory = temporaryFolder.newFolder()
        File(directory, "IMG_0001.JPG").createNewFile()
        File(directory, "IMG_0001_1.JPG").createNewFile()

        val file = DownloadNaming.uniqueFile(directory, "IMG_0001.JPG")

        assertEquals("IMG_0001_2.JPG", file.name)
        assertEquals(directory, file.parentFile)
    }

    @Test
    fun shouldAppendSuffix_whenTheNameHasNoExtension() {
        val directory = temporaryFolder.newFolder()
        File(directory, "noext").createNewFile()

        assertEquals("noext_1", DownloadNaming.uniqueFile(directory, "noext").name)
    }
}
