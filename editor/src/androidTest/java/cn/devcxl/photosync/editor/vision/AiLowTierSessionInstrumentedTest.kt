package cn.devcxl.photosync.editor.vision

import java.io.File
import java.security.MessageDigest

import android.content.Context

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import kotlinx.coroutines.runBlocking

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer

/**
 * Case 4 of the 24 MP golden regression: the LOW memory tier with the real pinned models.
 *
 * The unit tests prove the tier contract with a fake session factory; this test proves it with the
 * production [OnnxRuntimeSessionFactory] and real MODNet / MI-GAN inference: after each call
 * [OrtSessionManager.activeSessionCount] must be `0`, so a `<= 4 GB` device never keeps a native
 * session resident between two AI tools.
 *
 * Models are never bundled. Stage them first, for example
 * `adb push modnet_photographic_portrait_matting.onnx /data/local/tmp/`, or leave them in
 * `/sdcard/Download/`; without a verifiable artifact every method is skipped via [assumeTrue] so CI
 * stays green. The staged copies are installed through [AiModelDownloadManager.importLocal], which
 * enforces the pinned byte size and SHA-256, and are deleted again in [tearDown].
 */
@RunWith(AndroidJUnit4::class)
class AiLowTierSessionInstrumentedTest {

    private lateinit var context: Context
    private lateinit var stagedDir: File
    private lateinit var downloads: AiModelDownloadManager
    private lateinit var manager: OrtSessionManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        stagedDir = File(context.cacheDir, STAGED_DIR_NAME).apply {
            deleteRecursively()
            mkdirs()
        }
        downloads = AiModelDownloadManager(File(context.filesDir, AiModelCatalog.MODELS_DIR_NAME))
        manager = OrtSessionManager(tier = DeviceMemoryTier.LOW)
    }

    @After
    fun tearDown() {
        manager.close()
        stagedDir.deleteRecursively()
        // Only the test app's own model directory is touched: this is not the user's app install.
        downloads.delete(AiModelCatalog.MODNET_PORTRAIT)
        downloads.delete(AiModelCatalog.MI_GAN_GENERATOR_LEGACY)
        AiAssetStore(context.filesDir).deletePhotoAssets(PHOTO_ID)
    }

    @Test
    fun shouldLeaveNoSessionResident_whenModnetRunsOnTheLowTier() {
        val modelFile = installOrSkip(AiModelCatalog.MODNET_PORTRAIT, MODNET_SOURCE)
        val segmenter = ModnetSubjectSegmenter(manager, AiAssetStore(context.filesDir))
        val image = SubjectImage(96, 64, IntArray(96 * 64) { index ->
            val column = index % 96
            0xFF shl 24 or (column * 2 shl 16) or (120 shl 8) or 90
        })

        val mask = segmenter.segment(image, modelFile).getOrElse {
            throw AssertionError("MODNet inference failed on the LOW tier", it)
        }

        assertEquals(512, mask.width)
        assertEquals(512, mask.height)
        assertTrue("the mask must stay in [0, 1]", mask.alpha.all { it in 0.0f..1.0f })
        assertEquals("LOW tier must release the MODNet session", 0, manager.activeSessionCount)
    }

    @Test
    fun shouldLeaveNoSessionResident_whenMiGanRunsOnTheLowTier() {
        installOrSkip(AiModelCatalog.MI_GAN_GENERATOR_LEGACY, MIGAN_SOURCE)
        val inpainter = MiGanInpainter(
            sessionManager = manager,
            downloadManager = downloads,
            assetStore = AiAssetStore(context.filesDir),
            memoryTier = DeviceMemoryTier.LOW
        )
        val width = 640
        val height = 480
        val source = IntArray(width * height) { index ->
            val column = index % width
            val row = index / width
            0xFF shl 24 or (40 shl 16) or (60 shl 8) or (minOf(255, 80 + column / 8 + row / 8))
        }
        val coverage = ByteArray(width * height) { index ->
            val column = index % width
            val row = index / width
            if (column in 280..360 && row in 200..280) 0xFF.toByte() else 0
        }

        val patches = runBlocking {
            inpainter.inpaint(PHOTO_ID, source, width, height, InpaintMaskBuffer(width, height, coverage))
        }.getOrElse { throw AssertionError("MI-GAN inference failed on the LOW tier", it) }

        assertTrue("the painted stroke must produce a patch", patches.patches.isNotEmpty())
        assertEquals("LOW tier must release the MI-GAN session", 0, manager.activeSessionCount)
    }

    /**
     * Copies a staged model into the app's model directory through the production import path.
     *
     * @return The verified model file, or a skipped test when no artifact is available.
     */
    private fun installOrSkip(spec: AiModelSpec, sourceName: String): File {
        val source = candidateSources(sourceName).firstOrNull { it.isFile }
        assumeTrue("push $sourceName to one of ${sourceDirsHint()}", source != null)
        val installed = checkNotNull(source).inputStream().use { input ->
            runBlocking { downloads.importLocal(spec, input) }
        }.getOrElse { throw AssertionError("$sourceName could not be imported", it) }
        assertEquals("$sourceName byte size", spec.byteSize, installed.length())
        assertEquals("$sourceName sha256", spec.sha256, sha256Of(installed))
        return installed
    }

    private fun candidateSources(name: String): List<File> = listOfNotNull(
        context.getExternalFilesDir(STAGED_DIR_NAME)?.let { File(it, name) },
        context.externalCacheDir?.let { File(it, name) },
        File("/sdcard/Download/$name"),
        File("/data/local/tmp/$name")
    )

    private fun sourceDirsHint(): String = candidateSources(ANY_FILE).joinToString(" | ") { it.parent }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        file.inputStream().use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private companion object {
        const val PHOTO_ID = 4343L
        const val STAGED_DIR_NAME = "ai-low-tier-models"
        const val MODNET_SOURCE = "modnet_photographic_portrait_matting.onnx"
        const val MIGAN_SOURCE = "migan_generator_512.onnx"
        const val ANY_FILE = "x"
        const val BUFFER_BYTES = 64 * 1024
    }
}
