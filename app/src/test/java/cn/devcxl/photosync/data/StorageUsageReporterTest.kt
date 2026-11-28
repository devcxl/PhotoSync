package cn.devcxl.photosync.data

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Seam under test: [StorageUsageReporter] — exact per-folder accounting, missing folders, per-category
 * cleanup and the one-key sweep. Only files are touched, which is what keeps the Room edit rows safe.
 */
class StorageUsageReporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shouldReportZero_whenNoCategoryDirectoryExists() {
        val reporter = reporter()
        val snapshot = reporter.snapshot()

        StorageCategory.entries.forEach { category ->
            assertEquals(0L, snapshot.bytes(category))
        }
        assertEquals(0L, snapshot.totalBytes)
    }

    @Test
    fun shouldSumEveryCategoryExactly_whenFilesExist() {
        write("models/modnet/model.onnx", bytes = 100)
        write("models/modnet/model.onnx.part", bytes = 50)
        write("edits/7/mask.png", bytes = 10)
        write("luts/film.lut16f", bytes = 30)
        write("skies/sky_abc.jpg", bytes = 40)
        write("cache/edits/7/preview.jpg", bytes = 20)
        // Loose files at the model root are part of the folder and must be counted too.
        write("models/portrait.tflite", bytes = 5)

        val snapshot = reporter().snapshot()

        assertEquals(155L, snapshot.bytes(StorageCategory.MODELS))
        assertEquals(10L, snapshot.bytes(StorageCategory.AI_EDITS))
        assertEquals(20L, snapshot.bytes(StorageCategory.PREVIEW_CACHE))
        assertEquals(30L, snapshot.bytes(StorageCategory.LUTS))
        assertEquals(40L, snapshot.bytes(StorageCategory.SKY))
        assertEquals(255L, snapshot.totalBytes)
    }

    @Test
    fun shouldClearOneCategoryOnly_whenAskedForIt() {
        write("models/a.onnx", bytes = 100)
        write("edits/7/mask.png", bytes = 10)
        write("cache/edits/7/preview.jpg", bytes = 20)

        val freed = reporter().clear(StorageCategory.PREVIEW_CACHE)

        assertEquals(20L, freed)
        assertEquals(0L, reporter().bytes(StorageCategory.PREVIEW_CACHE))
        assertEquals(100L, reporter().bytes(StorageCategory.MODELS))
        assertEquals(10L, reporter().bytes(StorageCategory.AI_EDITS))
    }

    @Test
    fun shouldReleaseEverything_whenTheOneKeySweepRuns() {
        write("models/a.onnx", bytes = 100)
        write("edits/7/mask.png", bytes = 10)
        write("luts/film.lut16f", bytes = 30)
        write("skies/sky_abc.jpg", bytes = 40)
        write("cache/edits/7/preview.jpg", bytes = 20)

        val freed = reporter().clearAll()

        assertEquals(200L, freed)
        assertEquals(0L, reporter().snapshot().totalBytes)
    }

    @Test
    fun shouldBeANoOp_whenClearingAMissingCategory() {
        assertEquals(0L, reporter().clear(StorageCategory.SKY))
    }

    @Test
    fun shouldFormatHumanReadableSizes() {
        assertEquals("0 B", StorageUsageReporter.formatBytes(0L))
        assertEquals("512 B", StorageUsageReporter.formatBytes(512L))
        assertEquals("1.0 KB", StorageUsageReporter.formatBytes(1024L))
        assertEquals("1.5 KB", StorageUsageReporter.formatBytes(1536L))
        assertEquals("20.0 MB", StorageUsageReporter.formatBytes(20L * 1024L * 1024L))
        assertEquals("2.00 GB", StorageUsageReporter.formatBytes(2L * 1024L * 1024L * 1024L))
    }

    @Test
    fun shouldNotTouchTheDatabaseDirectory_whenClearingTypedCategories() {
        // A sibling "databases" folder proves the reporter only addresses the five typed paths.
        write("databases/photosync.db", bytes = 70)
        reporter().clearAll()
        assertTrue(File(temporaryFolder.root, "files/databases/photosync.db").isFile)
    }

    private fun reporter(): StorageUsageReporter =
        StorageUsageReporter(File(temporaryFolder.root, "files"), File(temporaryFolder.root, "cache"))

    private fun write(relativePath: String, bytes: Int) {
        val file = if (relativePath.startsWith("cache/")) {
            File(temporaryFolder.root, relativePath)
        } else {
            File(temporaryFolder.root, "files/$relativePath")
        }
        val parent = checkNotNull(file.parentFile) { "No parent for $file" }
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Could not create $parent")
        }
        file.writeBytes(ByteArray(bytes) { 1 })
    }
}
