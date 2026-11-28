package cn.devcxl.photosync.editor.render

import java.io.File

import android.app.Application
import android.graphics.Bitmap

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.ui.SkyImportPipeline

/**
 * Seam under test: the `preview_cache_jpeg_quality` default shared by the CPU cache writer, the
 * offscreen chain and the sky importer. Keeping one constant is what stops the three writers from
 * drifting to three different qualities while the settings page shows a fourth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PreviewQualityWiringTest {

    @Test
    fun shouldShareOneDefaultAcrossEveryPreviewWriter() {
        assertEquals(
            EditorSettings.DEFAULT_PREVIEW_CACHE_JPEG_QUALITY,
            PreviewCacheWriter.DEFAULT_PREVIEW_JPEG_QUALITY
        )
        assertEquals(
            PreviewCacheWriter.DEFAULT_PREVIEW_JPEG_QUALITY,
            SkyImportPipeline.DEFAULT_JPEG_QUALITY
        )
    }

    @Test
    fun shouldDefaultTheOffscreenRequestToTheSharedQuality() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val source = EditorSource.BitmapSource(full = bitmap, proxy = bitmap)

        val request = OffscreenPreviewRequest(
            source = source,
            graph = EditGraph(),
            outDir = File("/tmp/preview")
        )

        assertEquals(PreviewCacheWriter.DEFAULT_PREVIEW_JPEG_QUALITY, request.jpegQuality)
    }
}
