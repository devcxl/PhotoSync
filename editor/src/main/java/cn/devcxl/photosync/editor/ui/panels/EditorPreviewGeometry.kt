package cn.devcxl.photosync.editor.ui.panels

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Decides which geometry the preview canvas shows while a geometry tool is active.
 *
 * An on-canvas tool must show the frame its handles live in, otherwise the handles and the picture
 * disagree about where the edges are:
 * - Crop and Straighten reveal the whole uncropped frame, because the crop rectangle is what the
 *   user is dragging over it.
 * - Perspective reveals the straightened frame *before* the quad warp, because the four pins are
 *   dragged onto the subject corners in that frame; the warp becomes visible as soon as the tool
 *   is left.
 * - Every other tool renders the graph exactly as it will be exported.
 */
object EditorPreviewGeometry {

    /**
     * Returns the graph that the preview renderer should display for the active [tool].
     */
    fun previewGraph(graph: EditGraph, tool: EditorTool): EditGraph = when (tool) {
        EditorTool.CROP, EditorTool.STRAIGHTEN ->
            graph.copy(geometry = graph.geometry.copy(crop = CropRect()))
        EditorTool.PERSPECTIVE -> graph.copy(
            geometry = graph.geometry.copy(
                crop = CropRect(),
                perspective = GeometryOps.DEFAULT_PERSPECTIVE_QUAD
            )
        )
        else -> graph
    }

    /**
     * Width/height ratio of the frame rendered for [tool].
     */
    fun previewAspect(graph: EditGraph, tool: EditorTool, sourceAspect: Float): Float =
        GeometryMath.outputAspect(previewGraph(graph, tool).geometry, sourceAspect)
}
