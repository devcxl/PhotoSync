package cn.devcxl.photosync.editor.ui

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Shared "Done" action: persists the edit graph, then returns the photo identity to the host.
 *
 * Extracted from `EditorActivity` so the activity only wires callbacks; the save itself stays in
 * [EditorSaveCoordinator].
 */
internal object EditorFinishAction {

    /**
     * Persists [graph] and finishes [activity] with the photo result extras.
     *
     * @param save Persists the graph; `false` keeps the editor open and re-enables Done.
     * @param onSaveFailed Invoked on the main thread when persistence failed.
     */
    fun saveAndFinish(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        photoId: Long,
        sourcePath: String,
        graph: EditGraph,
        save: suspend (EditGraph) -> Boolean,
        onSaveFailed: () -> Unit
    ) {
        scope.launch {
            if (!save(graph)) {
                onSaveFailed()
                return@launch
            }
            val resultIntent = android.content.Intent().apply {
                putExtra(EditorActivity.EXTRA_PHOTO_ID, photoId)
                putExtra(EditorActivity.EXTRA_SOURCE_PATH, sourcePath)
            }
            activity.setResult(Activity.RESULT_OK, resultIntent)
            activity.finish()
        }
    }
}
