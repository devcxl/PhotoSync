package cn.devcxl.photosync.editor.ui.panels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.InpaintBatchPlanner
import cn.devcxl.photosync.editor.domain.InpaintCropSpec
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintTileEta
import cn.devcxl.photosync.editor.domain.InpaintWorkRegion
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.ui.InpaintBrushCanvasHost
import cn.devcxl.photosync.editor.ui.InpaintBrushMode
import cn.devcxl.photosync.editor.ui.InpaintBrushSize
import cn.devcxl.photosync.editor.ui.InpaintPanelHost
import cn.devcxl.photosync.editor.ui.InpaintStrokeProjection
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.widgets.InpaintBrushOverlayView
import cn.devcxl.photosync.editor.ui.widgets.InpaintPanelView
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Coordinates the inpainting brush overlay, its panel and the asynchronous removal run.
 *
 * ## Behaviour
 * - A two-finger touch discards the in-flight stroke (the overlay does that itself) and keeps every
 *   committed stroke; the controller only observes it and never confirms a removal implicitly.
 * - The brush is a **source-pixel diameter** (`8..1200 px`, default `64`) with three quick presets.
 *   Strokes keep persisting `radiusNorm`; the conversion happens here and follows the true source
 *   size once the host probes it, with the preview width as the documented approximation before.
 * - The panel shows a live workload estimate (tile count and seconds) that recomputes when the
 *   painted bbox or the fine/fast mode changes; it never blocks the confirmation button.
 * - Confirm runs the progressive executor. A partial run (cancel or a failed batch) has already
 *   committed earlier batches to the graph, so the controller drops only the strokes that are
 *   fully covered by committed tiles and keeps the rest for a retry. A full run clears everything.
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
    private val projectionProvider: () -> InpaintStrokeProjection? = { null },
    initialBrushDiameterPx: Int = InpaintBrushSize.MEDIUM_PRESET_PX,
    initialQualityMode: InpaintQualityMode = InpaintQualityMode.FINE,
    private val memoryTier: DeviceMemoryTier = DeviceMemoryTier.STANDARD,
    private val measuredTileMsProvider: () -> Long? = { null }
) {

    /**
     * Integration callbacks for the writer shell.
     */
    interface Host {
        /** Two fingers changed the preview zoom/pan. */
        fun onInpaintCanvasTransformChanged(transform: CanvasTransform)

        /** Shows the non-blocking inference bar. */
        fun onInpaintInferenceStarted()

        /** Moves the inference bar, `0..100`. */
        fun onInpaintInferenceProgress(percent: Int)

        /** Hides the inference bar; called once per run, including cancellation. */
        fun onInpaintInferenceFinished()

        /** The run ended in a recoverable failure; the surviving strokes are kept for a retry. */
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
         * @param mode Fine/fast grid density chosen in the panel.
         * @param onProgress Completion callback in `0..100`, already on the work dispatcher.
         * @param onRemainingCoverageChanged Called once when the run ends with unfinished work
         *   (cancellation or a failed batch), with the uncommitted tile cores in source pixels.
         *   It is **not** called on a fully successful run.
         */
        suspend fun inpaint(
            strokes: List<InpaintStroke>,
            mode: InpaintQualityMode,
            onProgress: (Int) -> Unit,
            onRemainingCoverageChanged: (List<InpaintPixelRect>) -> Unit
        ): Result<Unit>
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

    /** Current brush diameter in source pixels. */
    var brushDiameterPx: Int = initialBrushDiameterPx
        .coerceIn(InpaintBrushSize.MIN_DIAMETER_PX, InpaintBrushSize.MAX_DIAMETER_PX)
        private set

    /** Armed fine/fast quality mode. */
    var qualityMode: InpaintQualityMode = initialQualityMode
        private set

    /** Live workload estimate of the painted strokes, or `null` when nothing is painted. */
    var workload: InpaintTileEta? = null
        private set

    /**
     * Upright full-resolution source size once the host has probed it.
     *
     * Until then the overlay's preview size is used, so the brush diameter and the tile estimate
     * still work at the proxy's slightly conservative scale. Setting a new size re-evaluates the
     * brush conversion, the painted bbox and the workload.
     */
    var sourcePixelSize: Pair<Int, Int>? = null
        set(value) {
            field = value
            applyBrushDiameter()
            refreshPaintedBounds()
            refreshWorkload()
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

            override fun onInpaintBrushDiameterChanged(diameterPx: Int) =
                setBrushDiameterPx(diameterPx)

            override fun onInpaintQualityChanged(mode: InpaintQualityMode) =
                setQualityMode(mode)

            override fun onInpaintClearRequested() = clearStrokes()

            override fun onInpaintConfirmRequested() {
                confirmInpaint()
            }
        }
        panelView.setBrushMode(canvas.brushMode)
        applyBrushDiameter()
        panelView.setQualityMode(qualityMode)
        panelView.setWorkload(workload)
        panelView.setBusy(isInferring)
    }

    /** Switches the brush/eraser mode on both the canvas and the panel. */
    fun setBrushMode(mode: InpaintBrushMode) {
        canvas.brushMode = mode
        panel.setBrushMode(mode)
    }

    /** Sets the source-pixel brush diameter on both the canvas and the panel. */
    fun setBrushDiameterPx(diameterPx: Int) {
        brushDiameterPx = diameterPx
            .coerceIn(InpaintBrushSize.MIN_DIAMETER_PX, InpaintBrushSize.MAX_DIAMETER_PX)
        applyBrushDiameter()
    }

    /** Switches the fine/fast mode and recomputes the workload estimate immediately. */
    fun setQualityMode(mode: InpaintQualityMode) {
        if (qualityMode == mode) return
        qualityMode = mode
        panel.setQualityMode(mode)
        refreshWorkload()
    }

    /** Drops every committed stroke and the workload hint. */
    fun clearStrokes() {
        canvas.clearStrokes()
        lastVisibleStrokes = emptyList()
        clearPaintedBounds()
        refreshWorkload()
        host.onInpaintStrokesCleared()
    }

    /** Handles a two-finger gesture that preempted a single-finger stroke. */
    fun handleTwoFingerPreemption() {
        canvas.abortActiveStroke()
        // Committed strokes are intentionally kept: only the unfinished dab is discarded.
    }

    /** Mirrors the painted bbox into the workload estimate. */
    fun handleStrokesChanged(strokes: List<InpaintStroke>) {
        lastVisibleStrokes = strokes
        refreshPaintedBounds()
        refreshWorkload()
    }

    /**
     * Keeps only the strokes that still cover an uncommitted tile core.
     *
     * Called by the executor when a progressive run stopped early, so the user repaints only what
     * is actually missing. Strokes are filtered through the same projection the run used.
     */
    fun handleRemainingCoverageChanged(remainingCores: List<InpaintPixelRect>) {
        val (sourceWidth, sourceHeight) = currentSourceSize() ?: return
        if (lastVisibleStrokes.isEmpty()) return
        val projection = projectionProvider()
        val retained = lastVisibleStrokes.filter { stroke ->
            val projected = projection?.projectStrokes(listOf(stroke)) ?: listOf(stroke)
            projected.any { segment ->
                val bounds = segment.bounds(sourceWidth, sourceHeight)
                !bounds.isEmpty && remainingCores.any { core -> core.intersects(bounds) }
            }
        }
        if (retained.size == lastVisibleStrokes.size) return
        canvas.replaceStrokes(retained)
        lastVisibleStrokes = retained
        refreshPaintedBounds()
        refreshWorkload()
    }

    private fun applyBrushDiameter() {
        canvas.brushRadiusNorm = InpaintBrushSize.radiusNormForDiameter(
            diameterPx = brushDiameterPx,
            sourceWidthPx = brushScaleWidth()
        )
        panel.setBrushDiameterPx(brushDiameterPx)
    }

    private fun brushScaleWidth(): Int =
        sourcePixelSize?.first?.takeIf { it > 0 }
            ?: canvas.sourceWidth.takeIf { it > 0 }
            ?: InpaintBrushSize.PREVIEW_FALLBACK_WIDTH_PX

    private fun refreshPaintedBounds() {
        val (sourceWidth, sourceHeight) = currentSourceSize() ?: run {
            clearPaintedBounds()
            return
        }
        if (lastVisibleStrokes.isEmpty()) {
            clearPaintedBounds()
            return
        }
        val projection = projectionProvider()
        val projected = projection?.projectStrokes(lastVisibleStrokes) ?: lastVisibleStrokes
        val bounds = InpaintWorkRegion.boundsOf(projected, sourceWidth, sourceHeight)
        if (bounds == null) {
            clearPaintedBounds()
            return
        }
        paintedWidthPx = bounds.width
        paintedHeightPx = bounds.height
    }

    private fun refreshWorkload() {
        val size = currentSourceSize()
        if (lastVisibleStrokes.isEmpty() || size == null) {
            publishWorkload(null)
            return
        }
        val projection = projectionProvider()
        val projected = projection?.projectStrokes(lastVisibleStrokes) ?: lastVisibleStrokes
        val bounds = InpaintWorkRegion.boundsOf(projected, size.first, size.second)
        if (bounds == null) {
            publishWorkload(null)
            return
        }
        val spec = InpaintCropSpec.of(memoryTier, qualityMode)
        val tileCount = InpaintBatchPlanner.estimateTileCount(bounds, spec)
        val eta = InpaintTileEta.of(tileCount, memoryTier, measuredTileMsProvider())
        publishWorkload(eta)
    }

    private fun publishWorkload(eta: InpaintTileEta?) {
        workload = eta
        panel.setWorkload(eta)
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
        if (currentSourceSize() == null) {
            host.onInpaintFailed(IllegalStateException("Inpaint source size is not ready"))
            return false
        }
        isInferring = true
        panel.setBusy(true)
        host.onInpaintInferenceStarted()
        inferenceJob = scope.launch {
            var outcome: Result<Unit>? = null
            try {
                val strokes = canvas.strokeLayer.snapshot()
                currentCoroutineContext().ensureActive()
                outcome = executor.inpaint(
                    strokes = strokes,
                    mode = qualityMode,
                    onProgress = { percent ->
                        host.onInpaintInferenceProgress(percent.coerceIn(0, 100))
                    },
                    onRemainingCoverageChanged = { remainingCores ->
                        handleRemainingCoverageChanged(remainingCores)
                    }
                )
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

    /** Cancels the run in flight; the surviving strokes stay in place. */
    fun cancelInference() {
        inferenceJob?.cancel()
        inferenceJob = null
    }

    private fun finish(outcome: Result<Unit>?) {
        if (outcome == null) return
        outcome.fold(
            onSuccess = {
                canvas.clearStrokes()
                lastVisibleStrokes = emptyList()
                clearPaintedBounds()
                refreshWorkload()
            },
            onFailure = { error ->
                Timber.e(error, "Inpainting failed")
                host.onInpaintFailed(error)
            }
        )
    }
}
