package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.CropOverlayView
import cn.devcxl.photosync.editor.ui.widgets.LiquifyBrushOverlayView

/**
 * Builds the on-canvas gesture listeners so [EditorActivity] only has to supply the callbacks that
 * keep its rendering and history state in sync.
 */
internal object EditorGestureFactory {

    /** Creates the crop/straighten/perspective overlay listener. */
    fun cropOverlay(
        undoController: EditorUndoController,
        onDragged: () -> Unit,
        onFinished: () -> Unit,
        onCanvasTransformChanged: (CanvasTransform) -> Unit,
        onCompareHold: (Boolean) -> Unit
    ): CropOverlayView.Listener = EditorGeometryGestures(
        undoController = undoController,
        onDragged = onDragged,
        onFinished = onFinished,
        onCanvasTransformChanged = onCanvasTransformChanged,
        onCompareHold = onCompareHold
    )

    /** Creates the liquify brush overlay listener, projecting strokes through the live crop. */
    fun liquifyOverlay(
        session: LiquifySession,
        undoController: EditorUndoController,
        renderer: EditorGlRenderer,
        onMeshChanged: () -> Unit,
        onStrokeFinished: () -> Unit,
        onCanvasTransformChanged: (CanvasTransform) -> Unit,
        onResetRequested: () -> Unit
    ): LiquifyBrushOverlayView.Listener = LiquifyGestures(
        session = session,
        projection = {
            LiquifyStrokeProjection.of(undoController.currentGraph.geometry, renderer.sourceAspect())
        },
        onMeshChanged = onMeshChanged,
        onStrokeFinished = onStrokeFinished,
        onCanvasTransformChanged = onCanvasTransformChanged,
        onResetRequested = onResetRequested
    )
}
