package cn.devcxl.photosync.editor.export

import android.app.Application
import android.content.Context

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.wrapper.JpegScanlineContract

/**
 * Seam under test: [BakeRequest.jpegQuality] — the export setting reaching the streaming JPEG writer.
 * The native writer cannot run on the JVM, so the request is the unit under test: it must default to
 * the historical 95 and carry the user's clamped value unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BakeRequestQualityTest {

    @Test
    fun shouldDefaultToTheHistoricalQuality_whenNoSettingIsProvided() {
        val request = request()

        assertEquals(JpegScanlineContract.DEFAULT_QUALITY, request.jpegQuality)
        assertEquals(95, request.jpegQuality)
    }

    @Test
    fun shouldCarryTheUserSetting_whenExporting() {
        val settings = freshSettings()
        settings.exportJpegQuality = 88

        val request = request(jpegQuality = settings.exportJpegQuality)

        assertEquals(88, request.jpegQuality)
    }

    @Test
    fun shouldCarryTheClampedValue_whenTheStoredValueIsOutOfRange() {
        val settings = freshSettings()
        settings.exportJpegQuality = 20

        val request = request(jpegQuality = settings.exportJpegQuality)

        assertEquals(EditorSettings.MIN_EXPORT_JPEG_QUALITY, request.jpegQuality)
    }

    private fun request(
        jpegQuality: Int = JpegScanlineContract.DEFAULT_QUALITY
    ): BakeRequest = BakeRequest(
        sourcePath = "/tmp/source.jpg",
        displayName = "source_edited.jpg",
        isRaw = false,
        graph = EditGraph(),
        jpegQuality = jpegQuality
    )

    private fun freshSettings(): EditorSettings {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = EditorSettings(context)
        settings.resetAll()
        return settings
    }
}
