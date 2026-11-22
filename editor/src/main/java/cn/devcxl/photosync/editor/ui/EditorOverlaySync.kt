package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyBrushMode
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController

/**
 * Pushes the current edit state into the two canvas overlays.
 *
 * The crop overlay needs the crop rectangle, the perspective pins and the frame aspect the GL pass
 * is letterboxing; the liquify overlay needs the same frame aspect plus the brush radius and mode.
 * Keeping that projection in one place means the overlays can never disagree with the shader about
 * where a UV coordinate landed.
 */
internal class EditorOverlaySync(
    private val binding: ActivityEditorBinding,
    private val panel: EditorPanelController,
    private val liquifySession: LiquifySession,
    private val previewAspect: () -> Float
) {

    /** Refreshes both overlays for [graph]. */
    fun sync(graph: EditGraph) {
        val aspect = previewAspect()
        val cropOverlay = binding.cropOverlayView
        cropOverlay.cropRect = graph.geometry.crop
        cropOverlay.perspectiveQuad = graph.geometry.perspective.toFloatArray()
        cropOverlay.lockedAspect = panel.lockedAspect
        cropOverlay.frameAspect = aspect
        cropOverlay.tool = panel.currentTool
        cropOverlay.group = panel.currentGroup
        syncBrush()
    }

    /** Refreshes only the liquify brush ring, leaving the crop overlay untouched. */
    fun syncBrush() {
        val overlay = binding.liquifyBrushOverlayView
        overlay.frameAspect = previewAspect()
        overlay.brushRadiusNorm = liquifySession.settings.sizeNorm
        overlay.restoring = liquifySession.settings.mode == LiquifyBrushMode.RESTORE
        overlay.canvasTransform = binding.cropOverlayView.canvasTransform
    }

    /** Mirrors a pinch/pan gesture into both overlays. */
    fun syncCanvasTransform(transform: CanvasTransform) {
        binding.cropOverlayView.canvasTransform = transform
        binding.liquifyBrushOverlayView.canvasTransform = transform
    }
}
