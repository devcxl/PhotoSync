package cn.devcxl.photosync.editor.render

import java.io.File

import android.app.Application
import android.graphics.Bitmap

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seam under test: the preview-resolution setting reaching [EditorBitmapLoader] and
 * [EditorSourceLoader]. A 1024px choice must cap the full preview and the proxy; the default keeps
 * the historical 2048/1024 pair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorPreviewCapsWiringTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shouldKeepTheHistoricalCaps_whenLoadedWithDefaults() {
        val image = sourceImage()
        val preview = EditorBitmapLoader.loadUprightPreviewBitmap(image.absolutePath, isRaw = false)

        assertNotNull(preview)
        assertTrue(maxOf(preview!!.width, preview.height) <= EditorBitmapLoader.EDITOR_PREVIEW_MAX_EDGE_PX)
        preview.recycle()
    }

    @Test
    fun shouldHonourTheSelectedPreviewEdge_whenLoadingAPreview() {
        val image = sourceImage()
        val preview = EditorBitmapLoader.loadUprightPreviewBitmap(
            image.absolutePath,
            isRaw = false,
            maxEdgePx = 1024
        )

        assertNotNull(preview)
        val longEdge = maxOf(preview!!.width, preview.height)
        assertTrue("a 1024px choice must cap the preview, was $longEdge", longEdge <= 1024)
        preview.recycle()
    }

    @Test
    fun shouldCapFullPreviewAndProxy_whenLoadingAnEditorSource() {
        val image = sourceImage()
        val source = EditorSourceLoader.load(
            sourcePath = image.absolutePath,
            isRaw = false,
            preferLinear = false,
            previewMaxEdgePx = 1024
        )

        assertNotNull(source)
        val bitmapSource = source as EditorSource.BitmapSource
        assertTrue(maxOf(bitmapSource.full.width, bitmapSource.full.height) <= 1024)
        assertTrue(maxOf(bitmapSource.proxy.width, bitmapSource.proxy.height) <= 1024)
        bitmapSource.release()
    }

    /** A 3000x1200 JPEG so both the 2048 default and the 1024 override have something to scale. */
    private fun sourceImage(): File {
        val bitmap = Bitmap.createBitmap(3000, 1200, Bitmap.Config.ARGB_8888)
        val file = File(temporaryFolder.root, "source.jpg")
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, DEFAULT_FIXTURE_QUALITY, stream)
        }
        bitmap.recycle()
        return file
    }

    private companion object {
        const val DEFAULT_FIXTURE_QUALITY = 90
    }
}
