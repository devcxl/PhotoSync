package cn.devcxl.photosync.editor.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LutRef
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.render.EditorPreviewSession

/**
 * Drives the SAF `.cube` import flow: launches the system document picker, pre-bakes the document on
 * an IO dispatcher, binds the new LUT to the edit graph and reports progress through toasts.
 */
internal class EditorLutImportCoordinator(
    private val activity: ComponentActivity,
    private val lutStore: CubeLutStore,
    private val undoController: EditorUndoController,
    private val previewSession: EditorPreviewSession,
    private val onGraphChanged: () -> Unit
) {

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Timber.d("LUT import cancelled")
        } else {
            import(uri)
        }
    }

    /** Opens the system picker; `.cube` has no registered MIME type, so every document is offered. */
    fun launch() {
        launcher.launch(arrayOf(CUBE_MIME_FILTER))
    }

    private fun import(uri: Uri) {
        activity.lifecycleScope.launch {
            Toast.makeText(activity, R.string.editor_lut_importing, Toast.LENGTH_SHORT).show()
            val imported = withContext(Dispatchers.IO) {
                EditorLutImporter.import(activity.contentResolver, lutStore, uri)
            }
            if (imported == null) {
                Toast.makeText(activity, R.string.editor_lut_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            bindLut(imported.id)
            Toast.makeText(activity, R.string.editor_lut_imported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindLut(id: String) {
        val graph = undoController.currentGraph
        val withLut = graph.copy(color = graph.color.copy(lut = LutRef(id, DEFAULT_LUT_STRENGTH)))
        undoController.onParameterDrag(withLut, EditParameterKey.COLOR_LUT)
        undoController.onParameterRelease()
        previewSession.preloadLut(undoController.currentGraph)
        onGraphChanged()
    }

    private companion object {
        private const val DEFAULT_LUT_STRENGTH = 100
        private const val CUBE_MIME_FILTER = "*/*"
    }
}
