package cn.devcxl.photosync.editor.ui

import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.render.EditorPreviewSession

/**
 * Persists a finished edit: renders the screen-sized preview cache, then stores the [EditGraph]
 * through the host `EditStore`, which drops the record when the graph is an identity edit.
 *
 * Blocking; call from an IO dispatcher.
 */
internal class EditorSaveCoordinator(
    private val photoId: Long,
    private val previewSession: EditorPreviewSession
) {

    suspend fun persist(graph: EditGraph) {
        val previewPath = if (graph.isIdentity()) null else previewSession.writePreviewCache(graph)
        val store = EditorHostRegistry.editStore
        if (store == null) {
            Timber.e("EditorHostRegistry.editStore is null; edit cannot be persisted")
            return
        }
        store.saveEditForPhoto(
            photoId = photoId,
            graph = graph,
            previewCachePath = previewPath
        )
    }
}
