package cn.devcxl.photosync.editor.ui.panels

import kotlin.math.ceil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintWorkRegion
import cn.devcxl.photosync.editor.ui.INPAINT_OVERSIZE_LIMIT_PX
import cn.devcxl.photosync.editor.ui.InpaintBrushCanvasHost
import cn.devcxl.photosync.editor.ui.InpaintBrushMode
import cn.devcxl.photosync.editor.ui.InpaintPanelHost
import cn.devcxl.photosync.editor.ui.InpaintStrokeProjection
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.InpaintBrushOverlayView
import cn.devcxl.photosync.editor.ui.widgets.InpaintPanelView

/**
 * Coordinates the inpainting brush overlay, its panel and the asynchronous removal run.
 *
 * ## Behaviour
 * - A two-finger touch discards the in-flight stroke (the overlay does that itself) and keeps every
 *   committed stroke; the controller only observes it and never confirms a removal implicitly.
 * - The painted bbox is mirrored to the amber panel advice; above 256 px on either side the copy
 *   proposes a split count but never blocks the user, per the design.
 * - Confirm rasterizes the committed strokes, runs [InpaintExecutor] and, on success, clears the
 *   strokes because they have become an `EditGraph.inpaint` patch. Cancelling the run keeps the
 *   strokes and the previous graph intact.
 *
 * The controller has no Android view dependency beyond the two host interfaces and the two view
 * listener contracts, so the whole state machine is JVM-testable. Host callbacks run on the
 * dispatcher of [scope]; integration installs a main-scoped controller and posts nothing itself.
 */
internal class InpaintBrushController(
    private val host: Host,
    private val canvas: InpaintBrushCanvasHost,
    private val panel: InpaintPanelHost,
    private val scope: CoroutineScope,
    private val executor: InpaintExecutor,
    private val projectionProvider: () -> InpaintStrokeProjection? = { null }
) {

    /**
     * Integration callbacks for the writer shell.
     */
    interface Host {
        /** Two fingers changed the preview zoom/pan. */
        fun onInpaintCanvasTransformChanged(transform: CanvasTransform)

        /** Painted bbox changed; `0 x 0` means nothing is painted. */
        fun onInpaintBboxEstimated(widthPx: Int, heightPx: Int, isOversized: Boolean)

        /** Shows the non-blocking inference bar. */
        fun onInpaintInferenceStarted()

        /** Moves the inference bar, `0..100`. */
        fun onInpaintInferenceProgress(percent: Int)

        /** Hides the inference bar; called once per run, including cancellation. */
        fun onInpaintInferenceFinished()

        /** The run committed ordered patches that may be appended to the edit graph. */
        fun onInpaintPatchesReady(patches: List<InpaintPatchRef>)

        /** The run failed; the strokes are still there for a retry. */
        fun onInpaintFailed(cause: Throwable)

        /** Confirm was tapped with no committed stroke. */
        fun onInpaintNoStrokes()

        /** The user cleared the strokes. */
        fun onInpaintStrokesCleared()
    }

    /**
     * Runs the actual removal for [strokes].
     *
     * Integration adapts [cn.devcxl.photosync.editor.vision.MiGanInpainter]; tests provide a fake.
     * Strokes are the visible (post-Pass A) normalized capsules the user painted; projecting and
     * rasterizing them at the true source resolution is the executor's job.
     */
    fun interface InpaintExecutor {

        /**
         * @param strokes Committed visible-frame strokes, oldest first.
         * @param onProgress Completion callback in `0..100`, already on the work dispatcher.
         */
        suspend fun inpaint(
            strokes: List<InpaintStroke>,
            onProgress: (Int) -> Unit
        ): Result<List<InpaintPatchRef>>
    }

    /** True while a removal run is in flight. */
    var isInferring: Boolean = false
        private set

    /** Width of the painted bbox in source pixels, or `0` when nothing is painted. */
    var paintedWidthPx: Int = 0
        private set

    /** Height of the painted bbox in true source pixels, or `0` when nothing is painted. */
    var paintedHeightPx: Int = 0
        private set

    /**
     * Upright full-resolution source size once the host has probed it.
     *
     * Until then the overlay's preview size is used, so the amber advice still works on the proxy
     * at a slightly conservative scale. Setting a new size re-evaluates the committed strokes.
     */
    var sourcePixelSize: Pair<Int, Int>? = null
        set(value) {
            field = value
            refreshPaintedBounds()
        }

    private var inferenceJob: Job? = null
    private var lastVisibleStrokes: List<InpaintStroke> = emptyList()

    /** Wires the real overlay and panel listeners and pushes the current state into both. */
    fun attach(overlay: InpaintBrushOverlayView, panelView: InpaintPanelView) {
        overlay.listener = object : InpaintBrushOverlayView.Listener {
            override fun onInpaintStrokesChanged(strokes: List<InpaintStroke>) {
                handleStrokesChanged(strokes)
            }

            override fun onInpaintTwoFingerPreempted() {
                handleTwoFingerPreemption()
            }

            override fun onInpaintCanvasTransformChanged(transform: CanvasTransform) {
                host.onInpaintCanvasTransformChanged(transform)
            }
        }
        panelView.listener = object : InpaintPanelView.Listener {
            override fun onInpaintModeChanged(mode: InpaintBrushMode) = setBrushMode(mode)

            override fun onInpaintBrushSizeChanged(radiusNorm: Float) =
                setBrushRadiusNorm(radiusNorm)

            override fun onInpaintClearRequested() = clearStrokes()

            override fun onInpaintConfirmRequested() {
                confirmInpaint()
            }
        }
        panelView.setBrushMode(canvas.brushMode)
        panelView.setBrushRadiusNorm(canvas.brushRadiusNorm)
        panelView.setBusy(isInferring)
    }

    /** Switches the brush/eraser mode on both the canvas and the panel. */
    fun setBrushMode(mode: InpaintBrushMode) {
        canvas.brushMode = mode
        panel.setBrushMode(mode)
    }

    /** Sets the brush size on both the canvas and the panel. */
    fun setBrushRadiusNorm(radiusNorm: Float) {
        canvas.brushRadiusNorm = radiusNorm
        panel.setBrushRadiusNorm(radiusNorm)
    }

    /** Drops every committed stroke and the amber advice. */
    fun clearStrokes() {
        canvas.clearStrokes()
        lastVisibleStrokes = emptyList()
        clearPaintedBounds()
        host.onInpaintStrokesCleared()
    }

    /** Handles a two-finger gesture that preempted a single-finger stroke. */
    fun handleTwoFingerPreemption() {
        canvas.abortActiveStroke()
        // Committed strokes are intentionally kept: only the unfinished dab is discarded.
    }

    /** Mirrors the painted bbox into the panel advice and the host. */
    fun handleStrokesChanged(strokes: List<InpaintStroke>) {
        lastVisibleStrokes = strokes
        refreshPaintedBounds()
    }

    private fun refreshPaintedBounds() {
        val (sourceWidth, sourceHeight) = currentSourceSize() ?: run {
            clearPaintedBounds()
            return
        }
        val bounds = if (lastVisibleStrokes.isEmpty()) {
            null
        } else {
            val projection = projectionProvider()
            val projected = projection?.projectStrokes(lastVisibleStrokes) ?: lastVisibleStrokes
            InpaintWorkRegion.boundsOf(projected, sourceWidth, sourceHeight)
        }
        if (bounds == null) {
            clearPaintedBounds()
            return
        }
        paintedWidthPx = bounds.width
        paintedHeightPx = bounds.height
        val oversized = bounds.width > INPAINT_OVERSIZE_LIMIT_PX ||
            bounds.height > INPAINT_OVERSIZE_LIMIT_PX
        if (oversized) {
            panel.showOversizeWarning(
                bounds.width,
                bounds.height,
                suggestedSplits(bounds.width, bounds.height)
            )
        } else {
            panel.hideOversizeWarning()
        }
        host.onInpaintBboxEstimated(bounds.width, bounds.height, oversized)
    }

    private fun currentSourceSize(): Pair<Int, Int>? {
        sourcePixelSize?.let { (width, height) ->
            if (width > 0 && height > 0) return width to height
        }
        val width = canvas.sourceWidth
        val height = canvas.sourceHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun clearPaintedBounds() {
        paintedWidthPx = 0
        paintedHeightPx = 0
        panel.hideOversizeWarning()
        host.onInpaintBboxEstimated(0, 0, false)
    }

    /**
     * Starts a removal run for the committed strokes.
     *
     * @return False when a run is already active or there is nothing to remove.
     */
    fun confirmInpaint(): Boolean {
        if (isInferring) return false
        if (canvas.strokeLayer.isEmpty) {
            host.onInpaintNoStrokes()
            return false
        }
        if (canvas.sourceWidth <= 0 || canvas.sourceHeight <= 0) {
            host.onInpaintFailed(IllegalStateException("Inpaint source size is not ready"))
            return false
        }
        isInferring = true
        panel.setBusy(true)
        host.onInpaintInferenceStarted()
        inferenceJob = scope.launch {
            var outcome: Result<List<InpaintPatchRef>>? = null
            try {
                val strokes = canvas.strokeLayer.snapshot()
                currentCoroutineContext().ensureActive()
                outcome = executor.inpaint(strokes) { percent ->
                    host.onInpaintInferenceProgress(percent.coerceIn(0, 100))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                outcome = Result.failure(error)
            } finally {
                isInferring = false
                panel.setBusy(false)
                host.onInpaintInferenceFinished()
            }
            finish(outcome)
        }
        return true
    }

    /** Cancels the run in flight; the committed strokes stay in place. */
    fun cancelInference() {
        inferenceJob?.cancel()
        inferenceJob = null
    }

    private fun finish(outcome: Result<List<InpaintPatchRef>>?) {
        if (outcome == null) return
        outcome.fold(
            onSuccess = { patches ->
                canvas.clearStrokes()
                lastVisibleStrokes = emptyList()
                clearPaintedBounds()
                host.onInpaintPatchesReady(patches)
            },
            onFailure = { error ->
                Timber.e(error, "Inpainting failed")
                host.onInpaintFailed(error)
            }
        )
    }

    private fun suggestedSplits(widthPx: Int, heightPx: Int): Int {
        val columns = ceil(widthPx.toDouble() / INPAINT_OVERSIZE_LIMIT_PX).toInt().coerceAtLeast(1)
        val rows = ceil(heightPx.toDouble() / INPAINT_OVERSIZE_LIMIT_PX).toInt().coerceAtLeast(1)
        return (columns * rows).coerceAtLeast(2)
    }
}
