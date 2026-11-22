package cn.devcxl.photosync.editor.export

import java.io.File

import android.content.Context
import android.graphics.BitmapFactory

import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import kotlinx.coroutines.runBlocking

import cn.devcxl.photosync.editor.domain.ChromaticAberrationOps
import cn.devcxl.photosync.editor.domain.DetailOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EffectsOps
import cn.devcxl.photosync.editor.domain.GrainOps
import cn.devcxl.photosync.editor.domain.VignetteOps

/**
 * End-to-end bake assertions on a real device: headless EGL + the four GLES 3.0 passes +
 * `libjpeg-turbo` + `ExifInterface`.
 *
 * Pixel comparisons run on the rendered `RGBA8` bands (captured before JPEG quantization) so the
 * `<= 1/255` budgets stay meaningful; the EXIF assertions run through the production
 * [BakeExporter] and a file-backed gallery sink.
 */
@RunWith(AndroidJUnit4::class)
class BakeExporterInstrumentedTest {

    private lateinit var context: Context
    private lateinit var workDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDir = File(context.cacheDir, "bake-instrumented")
        workDir.listFiles()?.forEach { it.deleteRecursively() }
        workDir.mkdirs()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun shouldMatchDecodedSourceWithinOneUnit_whenGraphIsIdentity() {
        val source = BakeTestSources.createJpeg(workDir, "identity.jpg", 640, 480)
        val session = checkNotNull(BakeTestSession.open(context, source.absolutePath, EditGraph())) {
            "Bake session could not start"
        }
        val baked = session.use { it.renderFullFrame() }
        val expected = BakeTestSources.decodedArgb(source.absolutePath)
        assertEquals(expected.size, baked.size)
        val delta = BakeTestSources.maxChannelDelta(expected, baked)
        assertTrue("Identity bake deviated by $delta/255", delta <= IDENTITY_TOLERANCE)
    }

    @Test
    fun shouldMatchFullFrameWithinOneUnit_whenRenderingColumnBandsWithHalo() {
        val source = BakeTestSources.createJpeg(workDir, "tiled.jpg", 2000, 768)
        val graph = EditGraph(
            detail = DetailOps(clarity = 60, texture = 40, dehaze = 35),
            effects = EffectsOps(
                grain = GrainOps(amount = 45, size = 35, roughness = 55, seed = 4242),
                vignette = VignetteOps(amount = 40, midpoint = 55, feather = 60, roundness = 30),
                ca = ChromaticAberrationOps(redCyan = 40, blueYellow = -30)
            )
        )
        val session = checkNotNull(BakeTestSession.open(context, source.absolutePath, graph)) {
            "Bake session could not start"
        }
        session.use { active ->
            val fullFrame = active.renderFullFrame()
            val tiled = active.renderTiled()
            assertEquals(fullFrame.size, tiled.size)
            val delta = BakeTestSources.maxChannelDelta(fullFrame, tiled)
            assertTrue("Column-band seam deviated by $delta/255", delta <= TILE_TOLERANCE)
        }
    }

    @Test
    fun shouldNormalizeOrientationAndKeepShootingExif_whenBakingEveryOrientation() {
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )
        requireExportChain()
        val gallerySink = FileGallerySink(File(workDir, "gallery"))
        val exporter = BakeExporter(context, gallerySink, context.filesDir)
        for (orientation in orientations) {
            val source = BakeTestSources.createJpeg(
                workDir,
                "orientation_$orientation.jpg",
                SOURCE_WIDTH,
                SOURCE_HEIGHT,
                orientation = orientation
            )
            val result = runBlocking {
                exporter.bake(
                    BakeRequest(
                        sourcePath = source.absolutePath,
                        displayName = "baked_$orientation.jpg",
                        isRaw = false,
                        graph = EditGraph()
                    )
                )
            }
            assertTrue("Bake failed for orientation $orientation: $result",
                result is BakeResult.Success)
            val success = result as BakeResult.Success
            val quarterTurned = orientation in QUARTER_TURNED
            val expectedWidth = if (quarterTurned) SOURCE_HEIGHT else SOURCE_WIDTH
            val expectedHeight = if (quarterTurned) SOURCE_WIDTH else SOURCE_HEIGHT
            assertEquals("Width for orientation $orientation", expectedWidth, success.width)
            assertEquals("Height for orientation $orientation", expectedHeight, success.height)
            assertNormalizedExif(File(workDir, "gallery/baked_$orientation.jpg"))
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                assertRedMarkerMovedToTopRight(File(workDir, "gallery/baked_$orientation.jpg"))
            }
        }
    }

    /** Skips the EXIF test on devices whose GLES 3 driver cannot render RGBA16F targets. */
    private fun requireExportChain() {
        val egl = SurfacelessEglContext.create()
        val chain = BakePassChain()
        val ready = egl != null && chain.init()
        chain.close()
        egl?.close()
        Assume.assumeTrue("Device lacks the RGBA16F export chain", ready)
    }

    private fun assertNormalizedExif(output: File) {
        assertTrue("Baked file missing: ${output.name}", output.isFile)
        val exif = ExifInterface(output.absolutePath)
        assertEquals(
            "Orientation must be normalized",
            ExifInterface.ORIENTATION_NORMAL,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        )
        assertEquals("SONY", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("ILCE-7M4", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2026:11:22 14:30:45", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("2.8", exif.getAttribute(ExifInterface.TAG_F_NUMBER))
    }

    private fun assertRedMarkerMovedToTopRight(output: File) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(output.absolutePath)) {
            "Baked JPEG could not be decoded"
        }
        val box = minOf(bitmap.width, bitmap.height) / 8
        var red = 0
        var green = 0
        var blue = 0
        var samples = 0
        for (y in 0 until box) {
            for (x in bitmap.width - box until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                samples++
            }
        }
        bitmap.recycle()
        assertTrue("Rotated bake is not upright (r=$red g=$green b=$blue)",
            red > green + 60 && red > blue + 60 && samples > 0)
    }

    private companion object {
        private const val IDENTITY_TOLERANCE = 1
        private const val TILE_TOLERANCE = 1
        private const val SOURCE_WIDTH = 96
        private const val SOURCE_HEIGHT = 64
        private val QUARTER_TURNED = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )
    }
}
