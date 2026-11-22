package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform

/**
 * Implements the canvas-gesture half of [EditorCanvasBinder.Host]: preview drags, liquify strokes,
 * press-and-hold comparison, pan/zoom and the Body Reshaping guide drags.
 *
 * The activity keeps the lifecycle and intent plumbing; every callback here is a one-line forward,
 * which is exactly the split the interface was designed for.
 */
internal class EditorGestureHost(
    private val binding: ActivityEditorBinding,
    private val renderer: EditorGlRenderer,
    private val topBar: EditorTopBarBinder,
    private val historyCoordinator: EditorHistoryCoordinator,
    private val portraitHost: EditorPortraitHost,
    private val renderCoordinatorProvider: () -> EditorRenderCoordinator,
    private val cancelSettle: () -> Unit
) {

    /** A closable drag started; render at proxy resolution immediately. */
    fun onDragStarted() {
        cancelSettle()
        renderCoordinatorProvider().renderCurrentGraph(useProxy = true)
    }

    /** A drag ended; render at full quality and refresh the history buttons. */
    fun onDragFinished() {
        renderCoordinatorProvider().renderCurrentGraph(useProxy = false)
        renderCoordinatorProvider().updateHistoryButtons()
    }

    /** The liquify grid changed mid-stroke. */
    fun onLiquifyMeshChanged() {
        renderCoordinatorProvider().publishLiquifyMesh()
    }

    /** A liquify stroke was committed to its own undo timeline. */
    fun onLiquifyStrokeFinished() {
        historyCoordinator.onLiquifyStrokeCommitted()
        renderCoordinatorProvider().renderCurrentGraph(useProxy = false)
        renderCoordinatorProvider().updateHistoryButtons()
    }

    /** Press-and-hold comparison started or ended. */
    fun onCompareChanged(comparing: Boolean) {
        if (renderer.compareOriginal == comparing) return
        renderer.compareOriginal = comparing
        topBar.setComparePillVisible(comparing)
        binding.glSurfaceView.requestRender()
    }

    /** Pinch/pan changed the canvas transform. */
    fun onCanvasTransformChanged(transform: CanvasTransform) {
        renderCoordinatorProvider().onCanvasTransformChanged(transform)
    }

    /** A Body Reshaping guide line moved. */
    fun onBodyGuidesChanged(waistY: Float, hipY: Float) {
        portraitHost.onGuidesChanged(waistY, hipY)
    }

    /** A Body Reshaping guide drag ended. */
    fun onBodyGuidesCommitted() {
        portraitHost.onGuidesCommitted()
    }
}
