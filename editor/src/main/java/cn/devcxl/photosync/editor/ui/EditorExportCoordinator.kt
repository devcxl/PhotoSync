package cn.devcxl.photosync.editor.ui

import java.io.File

import android.content.Context
import android.widget.Toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.export.BakeExporter
import cn.devcxl.photosync.editor.export.BakeRequest
import cn.devcxl.photosync.editor.export.BakeResult

/**
 * Runs the top-bar "导出" action: a full-resolution [BakeExporter] bake of the current graph,
 * published through the host [cn.devcxl.photosync.editor.contract.GallerySink].
 *
 * Keeping this out of `EditorActivity` leaves the activity a thin controller and gives the export
 * button a single owner for its enabled state and feedback toasts.
 */
internal class EditorExportCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val filesDir: File,
    private val onExportingChanged: (Boolean) -> Unit,
    private val onFinished: (Boolean) -> Unit
) {

    /**
     * Starts a bake for [graph].
     *
     * @param sourcePath Original JPEG/RAW file of the edited photo.
     * @param photoId Photo id, used for the fallback display name.
     * @param isRaw True for a RAW source decoded through `:libraw`.
     */
    fun export(sourcePath: String, photoId: Long, isRaw: Boolean, graph: EditGraph) {
        val sink = EditorHostRegistry.gallerySink
        if (sink == null) {
            Toast.makeText(context, R.string.editor_export_no_sink, Toast.LENGTH_SHORT).show()
            return
        }
        onExportingChanged(true)
        scope.launch {
            val result = BakeExporter(context, sink, filesDir).bake(
                BakeRequest(
                    sourcePath = sourcePath,
                    displayName = displayName(sourcePath, photoId),
                    isRaw = isRaw,
                    graph = graph
                )
            )
            onExportingChanged(false)
            val success = result is BakeResult.Success
            onFinished(success)
            val message = if (success) {
                R.string.editor_export_done
            } else {
                R.string.editor_export_failed
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(sourcePath: String, photoId: Long): String {
        val base = File(sourcePath).name.substringBeforeLast('.').ifBlank { "IMG_$photoId" }
        return "${base}_edited.jpg"
    }
}
