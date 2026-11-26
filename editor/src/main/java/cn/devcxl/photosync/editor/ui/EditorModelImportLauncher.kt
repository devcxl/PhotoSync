package cn.devcxl.photosync.editor.ui

import java.io.InputStream

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import timber.log.Timber

/**
 * Routes the next SAF `.onnx` selection to whichever AI tool asked for it.
 *
 * The launcher is registered once per activity, while the pending importer changes between the
 * subject and erase model sheets; a cancelled picker simply drops the pending importer.
 */
internal class EditorModelImportLauncher(activity: ComponentActivity) {

    private var pendingImport: ((InputStream) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val importer = pendingImport
        pendingImport = null
        if (uri == null || importer == null) return@registerForActivityResult
        runCatching { activity.contentResolver.openInputStream(uri) }
            .onSuccess { stream -> stream?.use(importer) }
            .onFailure { error -> Timber.e(error, "The selected ONNX model could not be opened") }
    }

    /** Opens the document picker and hands the chosen stream to [importer]. */
    fun launch(importer: (InputStream) -> Unit) {
        pendingImport = importer
        launcher.launch(arrayOf("application/octet-stream", "*/*"))
    }
}
