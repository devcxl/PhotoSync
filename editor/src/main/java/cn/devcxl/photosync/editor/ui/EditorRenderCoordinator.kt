package cn.devcxl.photosync.editor.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.render.CanvasViewportTransform
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.panels.EditorPreviewGeometry

/**
 * Drives the preview canvas from the current [EditGraph]: it resolves the preview-only geometry,
 * pushes the graph into [EditorPreviewSession], refreshes the drawer and the overlays, and keeps the
 * top-bar pills in sync.
 *
 * Extracting this from [EditorActivity] keeps the activity responsible for lifecycle and intent
 * plumbing only.
 */
internal class EditorRenderCoordinator(
    private val binding: ActivityEditorBinding,
    private val renderer: EditorGlRenderer,
    private val previewSession: EditorPreviewSession,
    private val liquifySession: LiquifySession,
    private val undoController: EditorUndoController,
    private val topBar: EditorTopBarBinder,
    private val portraitHost: EditorPortraitHost,
    private val scope: CoroutineScope,
    private val isRaw: () -> Boolean,
    private val panelProvider: () -> EditorPanelController,
    private val overlaySyncProvider: () -> EditorOverlaySync
) {

    /** Renders the current graph, optionally at the 1024px proxy resolution. */
    fun renderCurrentGraph(useProxy: Boolean) {
        val graph = undoController.currentGraph
        val panel = panelProvider()
        portraitHost.syncWarps(graph)
        val preview = EditorPreviewGeometry.previewGraph(graph, panel.currentTool)
        val lutWarm = previewSession.applyGraph(preview, useProxy = useProxy)
        overlaySyncProvider().sync(graph)
        panel.refresh()
        updateBaseLookPill(graph.baseLook)
        binding.glSurfaceView.requestRender()
        if (!lutWarm) preloadLutAsync(graph)
    }

    /** Re-renders after an undo, redo or history jump. */
    fun applyHistoryGraph(graph: EditGraph) {
        renderCurrentGraph(useProxy = false)
        updateBaseLookPill(graph.baseLook)
        updateHistoryButtons()
    }

    /** Mirrors a pinch/pan gesture into the renderer and every overlay. */
    fun onCanvasTransformChanged(transform: CanvasTransform) {
        renderer.canvasTransform =
            CanvasViewportTransform(transform.scale, transform.offsetX, transform.offsetY)
        overlaySyncProvider().syncCanvasTransform(transform)
        binding.glSurfaceView.requestRender()
    }

    /** Re-stages the liquify grid after a stroke or a reset. */
    fun publishLiquifyMesh() {
        previewSession.applyLiquifyMesh(liquifySession.mesh)
        binding.glSurfaceView.requestRender()
    }

    /** Shows the `BaseLook` pill only for RAW sources that carry a linear buffer. */
    fun updateBaseLookPill(baseLook: BaseLook) {
        topBar.updateBaseLookPill(baseLook, isRaw() && previewSession.supportsBaseLook)
    }

    /** Refreshes the undo / redo buttons. */
    fun updateHistoryButtons() {
        topBar.updateHistoryButtons()
    }

    /**
     * Decodes the referenced 3D LUT off the UI thread and re-requests a frame once it is warm.
     */
    private fun preloadLutAsync(graph: EditGraph) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { previewSession.preloadLut(graph) }
            if (loaded && undoController.currentGraph.color.lut?.id == graph.color.lut?.id) {
                binding.glSurfaceView.requestRender()
            }
        }
    }
}
