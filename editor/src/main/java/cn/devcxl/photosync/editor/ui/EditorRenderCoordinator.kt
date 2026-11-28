package cn.devcxl.photosync.editor.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private var stagedCloudKey: String? = null
    private var cloudJob: Job? = null
    private var stagedSkyKey: String? = null
    private var skyJob: Job? = null

    /** Renders the current graph, optionally at the 1024px proxy resolution. */
    fun renderCurrentGraph(useProxy: Boolean) {
        val graph = undoController.currentGraph
        val panel = panelProvider()
        portraitHost.syncWarps(graph)
        val preview = EditorPreviewGeometry.previewGraph(graph, panel.currentTool)
        val lutWarm = previewSession.applyGraph(preview, useProxy = useProxy)
        syncCloudLayer(graph)
        syncSkyLayer(graph)
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

    /**
     * Renders a graph that is **not** in the undo history, e.g. a live cloud opacity preview.
     *
     * The editor stays on [EditorUndoController.currentGraph]; only the canvas uniforms and the
     * staged cloud texture follow the transient graph, so dragging the slider never creates an undo
     * step.
     */
    fun renderTransientGraph(graph: EditGraph) {
        val panel = panelProvider()
        val preview = EditorPreviewGeometry.previewGraph(graph, panel.currentTool)
        previewSession.applyGraph(preview, useProxy = false)
        syncCloudLayer(graph)
        syncSkyLayer(graph)
        binding.glSurfaceView.requestRender()
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
     * Stages the Post-Pass A cloud layer for the canvas, decoding only when its pixels changed.
     *
     * The key deliberately excludes `opacity`: dragging the opacity slider rewrites the graph on
     * every step, and the shader reads the opacity from the frame state, so re-decoding the PNG (and
     * re-uploading the texture) per step would be pure jank.
     */
    private fun syncCloudLayer(graph: EditGraph) {
        val layer = graph.cloudLayer
        val key = layer?.let { "${it.imageRelPath}|${it.sha256}|${it.maskRelPath}|${it.geometryDigest}" }
        if (key == stagedCloudKey) return
        stagedCloudKey = key
        cloudJob?.cancel()
        if (layer == null) {
            previewSession.applyCloudLayer(null)
            return
        }
        cloudJob = scope.launch {
            val pixels = withContext(Dispatchers.IO) { previewSession.decodeCloudLayer(graph) }
            previewSession.applyCloudLayer(pixels)
            binding.glSurfaceView.requestRender()
        }
    }

    /**
     * Builds the graph's sky layer when its mask, asset or output aspect changed.
     *
     * Relight strength, horizon offset, feather and edge shift are Pass B/D uniforms, not decoded
     * pixels, so dragging those sliders never re-decodes the replacement image.
     */
    private fun syncSkyLayer(graph: EditGraph) {
        val sky = graph.sky
        val key = if (sky.isIdentity() || sky.maskRef.isBlank()) {
            null
        } else {
            "${sky.maskRef}|${sky.assetType.wireName}|${sky.assetIdOrRelPath}|${sky.assetSha256}|" +
                graph.geometry.hashCode()
        }
        if (key == stagedSkyKey) return
        stagedSkyKey = key
        skyJob?.cancel()
        if (key == null) {
            previewSession.applySkyLayer(null)
            return
        }
        skyJob = scope.launch {
            val layer = withContext(Dispatchers.IO) { previewSession.prepareSkyLayer(graph) }
            previewSession.applySkyLayer(layer)
            binding.glSurfaceView.requestRender()
        }
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
