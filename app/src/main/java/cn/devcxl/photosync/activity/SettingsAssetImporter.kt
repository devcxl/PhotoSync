package cn.devcxl.photosync.activity

import java.io.IOException

import android.net.Uri
import android.provider.OpenableColumns

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.SkyCustomAssetStore
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.ui.SkyImportPipeline

/** Terminal result of one SAF import, so the settings section can toast and refresh. */
internal enum class SettingsAssetImportOutcome {

    /** The `.cube` document was parsed, compiled and stored. */
    LUT_IMPORTED,

    /** The selected document is not a `.cube` file. */
    LUT_WRONG_TYPE,

    /** The LUT store rejected the document or the stream could not be read. */
    LUT_FAILED,

    /** The image was re-encoded and stored as a content-addressed sky. */
    SKY_IMPORTED,

    /** The sky store rejected the image or the stream could not be read. */
    SKY_FAILED
}

/**
 * SAF import path shared by the settings page: a `.cube` LUT and a sky photograph.
 *
 * Keeping the stream handling and error mapping here lets [SettingsStorageSection] stay a metrics
 * and cleanup controller. The sky re-encode uses the same `preview_cache_jpeg_quality` value as the
 * editor's own import, so a file added from settings and one added from the editor cannot differ in
 * quality or resolution.
 */
internal class SettingsAssetImporter(
    private val activity: AppCompatActivity,
    private val lutStore: CubeLutStore,
    private val skyStore: SkyCustomAssetStore,
    private val editorSettings: EditorSettings,
    private val onFinished: (SettingsAssetImportOutcome) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = activity.lifecycleScope
) {

    /** Imports the `.cube` document selected through SAF. */
    fun importLut(uri: Uri) {
        val name = queryDisplayName(uri)
        if (name == null || !name.endsWith(".cube", ignoreCase = true)) {
            onFinished(SettingsAssetImportOutcome.LUT_WRONG_TYPE)
            return
        }
        scope.launch {
            val imported = withContext(ioDispatcher) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        lutStore.importFromStream(name, stream)
                    } ?: Result.failure(IOException("The selected LUT document could not be opened"))
                } catch (error: IOException) {
                    Timber.w(error, "The selected LUT document could not be read")
                    Result.failure(error)
                } catch (error: SecurityException) {
                    Timber.w(error, "The platform denied reading the selected LUT document")
                    Result.failure(error)
                }
            }
            onFinished(
                if (imported.isSuccess) {
                    SettingsAssetImportOutcome.LUT_IMPORTED
                } else {
                    SettingsAssetImportOutcome.LUT_FAILED
                }
            )
        }
    }

    /** Imports the sky image selected through SAF with the shared preview-cache quality. */
    fun importSky(uri: Uri) {
        scope.launch {
            val asset = withContext(ioDispatcher) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        SkyImportPipeline.import(
                            input = stream,
                            store = skyStore,
                            jpegQuality = editorSettings.previewCacheJpegQuality
                        )
                    }
                } catch (error: IOException) {
                    Timber.w(error, "The selected sky image could not be read")
                    null
                } catch (error: SecurityException) {
                    Timber.w(error, "The platform denied reading the selected sky image")
                    null
                }
            }
            onFinished(
                if (asset != null) {
                    SettingsAssetImportOutcome.SKY_IMPORTED
                } else {
                    SettingsAssetImportOutcome.SKY_FAILED
                }
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        activity.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (error: SecurityException) {
        Timber.w(error, "Could not read the selected document name")
        null
    }
}
