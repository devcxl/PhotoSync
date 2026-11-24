package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.LiquifyBrushMode
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.LiquifyBrushOverlayView

/**
 * Translates [LiquifyBrushOverlayView] gestures into [LiquifySession] mutations.
 *
 * The overlay reports where the finger is on the *visible* canvas, while the displacement grid is
 * defined in the *pre-crop* frame Pass A samples. [projection] performs that conversion, including
 * scaling the brush radius by the local crop derivative, so a stroke lands on the pixels under the
 * ring even when the photo is cropped, straightened or perspective-corrected.
 *
 * A stroke is buffered by the session so the two-finger preemption rule can roll it back without
 * leaving an undo step behind; only a finished, moved stroke reaches [onStrokeFinished], which is
 * where the host records the `EditGraph.geometry.liquify` reference and re-renders.
 */
internal class LiquifyGestures(
    private val session: LiquifySession,
    private val projection: () -> LiquifyStrokeProjection,
    private val onMeshChanged: () -> Unit,
    private val onStrokeFinished: () -> Unit,
    private val onCanvasTransformChanged: (CanvasTransform) -> Unit,
    private val onResetRequested: () -> Unit
) : LiquifyBrushOverlayView.Listener {

    override fun onLiquifyStrokeBegin() {
        session.beginStroke()
    }

    override fun onLiquifyStrokeMove(prevUv: Uv, nextUv: Uv) {
        val strokeProjection = projection()
        val radiusNorm = session.settings.sizeNorm * strokeProjection.radiusScaleAt(nextUv)
        when (session.settings.mode) {
            LiquifyBrushMode.PUSH -> session.pushStroke(
                prevUv = strokeProjection.toPreCrop(prevUv),
                nextUv = strokeProjection.toPreCrop(nextUv),
                radiusNorm = radiusNorm,
                frameAspect = strokeProjection.frameAspect
            )
            LiquifyBrushMode.RESTORE -> session.restoreStroke(
                uv = strokeProjection.toPreCrop(nextUv),
                radiusNorm = radiusNorm,
                frameAspect = strokeProjection.frameAspect
            )
        }
        onMeshChanged()
    }

    override fun onLiquifyStrokeFinished(committed: Boolean) {
        if (committed) session.commitStroke() else session.cancelStroke()
        onMeshChanged()
        onStrokeFinished()
    }

    override fun onLiquifyCanvasTransformChanged(transform: CanvasTransform) {
        onCanvasTransformChanged.invoke(transform)
    }

    override fun onLiquifyResetRequested() {
        onResetRequested.invoke()
    }
}
