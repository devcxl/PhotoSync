package cn.devcxl.photosync.editor.render

import java.io.File

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

import cn.devcxl.photosync.editor.domain.InpaintWorkRegion

/**
 * Seam under test: [InpaintSourceReader] — the full-resolution window decoder of the erase tool.
 *
 * The editor preview is capped at 2048 px; an inpainting patch must not inherit that cap. The test
 * materializes a 3000 px JPEG and proves that both the probe and the region read stay at the
 * original pixel size instead of scaling the preview.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InpaintSourceReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shouldReadTheWindowAtOneToOne_whenTheJpegIsWiderThanThePreviewCap() = runBlocking {
        val file = writeJpeg(width = 3000, height = 2000)
        val reader = InpaintSourceReader()

        val dimensions = reader.probeDimensions(file.absolutePath, isRaw = false)

        assertEquals(3000 to 2000, dimensions)
        val region = checkNotNull(
            InpaintWorkRegion.around(
                bounds = cn.devcxl.photosync.editor.domain.InpaintPixelRect(0, 0, 3000, 500),
                sourceWidth = 3000,
                sourceHeight = 2000
            )
        )
        val pixels = reader.readRegion(
            sourcePath = file.absolutePath,
            isRaw = false,
            region = region,
            patches = emptyList(),
            filesDir = tempFolder.root
        )

        assertNotNull(pixels)
        assertEquals(region.width * region.height, pixels!!.size)
        assertEquals(3000, region.width)
    }

    @Test
    fun shouldKeepTheRequestedSubWindow_whenTheRegionIsNotTheWholeImage() = runBlocking {
        val file = writeJpeg(width = 3000, height = 2000)
        val reader = InpaintSourceReader()
        val region = checkNotNull(
            InpaintWorkRegion.around(
                bounds = cn.devcxl.photosync.editor.domain.InpaintPixelRect(
                    left = 1200,
                    top = 800,
                    right = 1800,
                    bottom = 1200
                ),
                sourceWidth = 3000,
                sourceHeight = 2000,
                marginPx = 0
            )
        )

        val pixels = reader.readRegion(
            sourcePath = file.absolutePath,
            isRaw = false,
            region = region,
            patches = emptyList(),
            filesDir = tempFolder.root
        )

        assertNotNull(pixels)
        assertEquals(600, region.width)
        assertEquals(400, region.height)
        assertEquals(600 * 400, pixels!!.size)
    }

    private fun writeJpeg(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GRAY)
            val file = File(tempFolder.root, "full-resolution-$width.jpg")
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    "Could not encode the fixture JPEG"
                }
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }
}
