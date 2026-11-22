package cn.devcxl.photosync.editor.ui

import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.domain.PortraitWarpComposer
import cn.devcxl.photosync.editor.render.EditorPreviewSession

/**
 * Persists a finished edit: writes the liquify displacement grid, renders the screen-sized preview
 * cache, then stores the [EditGraph] through the host `EditStore`, which drops the record when the
 * graph is an identity edit.
 *
 * Blocking; call from an IO dispatcher.
 */
internal class EditorSaveCoordinator(
    private val photoId: Long,
    private val previewSession: EditorPreviewSession,
    private val liquifySession: LiquifySession,
    private val portraitAnalysis: () -> PortraitAnalysis? = { null }
) {

    /**
     * Persists [graph] and any modified liquify displacement mesh to disk, renders the updated
     * preview cache, and commits the record to the host `EditStore`.
     *
     * @return `true` if persisted safely, `false` if saving the liquify grid or store failed.
     */
    suspend fun persist(graph: EditGraph): Boolean {
        val hadMesh = liquifySession.hasMesh
        val liquifyRef = liquifySession.persist()
        if (hadMesh && liquifyRef == null) {
            Timber.e("Failed to persist liquify displacement mesh for photoId=%d", photoId)
            return false
        }
        val finalGraph = graph.copy(geometry = graph.geometry.copy(liquify = liquifyRef))
        val previewPath = if (finalGraph.isIdentity()) {
            null
        } else {
            writePreviewCache(finalGraph)
        }
        val store = EditorHostRegistry.editStore
        if (store == null) {
            Timber.e("EditorHostRegistry.editStore is null; edit cannot be persisted")
            return false
        }
        store.saveEditForPhoto(
            photoId = photoId,
            graph = finalGraph,
            previewCachePath = previewPath
        )
        return true
    }

    /**
     * Renders the preview cache with the exact `uWarpTex` and `skinMask` the canvas showed, so the
     * gallery thumbnail cannot disagree with the editor about a face or body warp.
     */
    private fun writePreviewCache(finalGraph: EditGraph): String? {
        val analysis = portraitAnalysis()
        val warpField = PortraitWarpComposer.compose(
            analysis?.faceWarp(finalGraph.portrait.reshape),
            analysis?.bodyWarp(finalGraph.portrait.body),
            liquifySession.mesh
        )
        return previewSession.writePreviewCache(finalGraph, warpField, analysis?.skinMask)
    }
}
