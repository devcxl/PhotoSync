package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.CropOverlayView

/**
 * Translates [CropOverlayView] gestures into [EditGraph] mutations.
 *
 * Each drag gesture writes through [EditorUndoController] with the parameter key of the tool that
 * owns it, so a whole drag collapses into one undo step and the host only has to re-render.
 */
internal class EditorGeometryGestures(
    private val undoController: EditorUndoController,
    private val onDragged: () -> Unit,
    private val onFinished: () -> Unit,
    private val onCanvasTransformChanged: (CanvasTransform) -> Unit,
    private val onCompareHold: (Boolean) -> Unit
) : CropOverlayView.Listener {

    override fun onCropChanged(rect: CropRect) {
        drag(EditParameterKey.GEOMETRY_CROP) { graph ->
            graph.copy(geometry = graph.geometry.copy(crop = rect))
        }
    }

    override fun onCropChangeFinished() = finish()

    override fun onPerspectiveChanged(quad: FloatArray) {
        val perspective = quad.toList()
        drag(EditParameterKey.GEOMETRY_PERSPECTIVE) { graph ->
            graph.copy(geometry = graph.geometry.copy(perspective = perspective))
        }
    }

    override fun onPerspectiveChangeFinished() = finish()

    override fun onStraightenDrag(deltaDegrees: Float) {
        val current = undoController.currentGraph.geometry.straighten
        val target = (current + deltaDegrees).coerceIn(MIN_STRAIGHTEN, MAX_STRAIGHTEN)
        drag(EditParameterKey.GEOMETRY_STRAIGHTEN) { graph ->
            graph.copy(geometry = graph.geometry.copy(straighten = target))
        }
    }

    override fun onStraightenDragFinished() = finish()

    override fun onCanvasTransformChanged(transform: CanvasTransform) {
        onCanvasTransformChanged.invoke(transform)
    }

    override fun onCompareHold(active: Boolean) {
        onCompareHold.invoke(active)
    }

    private fun drag(parameterKey: EditParameterKey, transform: (EditGraph) -> EditGraph) {
        undoController.onParameterDrag(transform(undoController.currentGraph), parameterKey)
        onDragged()
    }

    private fun finish() {
        undoController.onParameterRelease()
        onFinished()
    }

    private companion object {
        private const val MIN_STRAIGHTEN = -45.0f
        private const val MAX_STRAIGHTEN = 45.0f
    }
}
