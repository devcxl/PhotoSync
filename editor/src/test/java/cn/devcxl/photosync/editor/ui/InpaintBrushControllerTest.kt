package cn.devcxl.photosync.editor.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer
import cn.devcxl.photosync.editor.domain.InpaintStrokePoint
import cn.devcxl.photosync.editor.domain.InpaintTileEta
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.panels.InpaintBrushController
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Seam under test: [InpaintBrushController] — the brush state machine around the removal run.
 *
 * Covers the current interaction promises: two fingers discard only the unfinished stroke, the
 * brush is a source-pixel diameter with three quick presets, the fine/fast mode recomputes the
 * workload estimate, a >15 s estimate is a non-blocking note, cancelling the inference keeps the
 * strokes, and a partial run keeps only the strokes covering unfinished tiles.
 */
class InpaintBrushControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val canvas = FakeCanvas()
    private val panel = FakePanel()
    private val host = FakeHost()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun shouldAbortOnlyTheUnfinishedStroke_whenTwoFingersPreemptAPaintGesture() {
        canvas.strokeLayer.add(stroke())
        val controller = controller(NoopExecutor())

        controller.handleTwoFingerPreemption()

        assertEquals(1, canvas.abortCalls)
        assertEquals(1, canvas.strokeLayer.strokeCount)
    }

    @Test
    fun shouldEstimateAWorkloadInsteadOfAnOversizeWarning_whenTheStrokeIsWide() {
        val controller = controller(NoopExecutor())
        val wide = InpaintStroke(
            erase = false,
            radiusNorm = 0.05f,
            points = listOf(InpaintStrokePoint(0.02f, 0.5f), InpaintStrokePoint(0.98f, 0.5f))
        )

        controller.handleStrokesChanged(listOf(wide))

        assertEquals(2, panel.shownWorkload?.tileCount)
        assertEquals(2, controller.workload?.tileCount)
        assertEquals(1_000, controller.paintedWidthPx)
        assertFalse("a two-tile estimate must not raise the long-run note", controller.workload!!.isLongRunning)
    }

    @Test
    fun shouldConvertTheDiameterAgainstThePreviewWidth_whenTheSourceSizeIsUnknown() {
        val controller = controller(NoopExecutor())

        controller.setBrushDiameterPx(InpaintBrushSize.MEDIUM_PRESET_PX)

        assertEquals(64, controller.brushDiameterPx)
        assertEquals(0.032f, canvas.brushRadiusNorm, 1e-6f)
        assertEquals(64, panel.diameterPx)
        assertEquals(
            64,
            InpaintBrushSize.diameterForRadiusNorm(canvas.brushRadiusNorm, sourceWidthPx = 1000)
        )
    }

    @Test
    fun shouldRescaleTheBrush_whenTheTrueSourceSizeArrives() {
        val controller = controller(NoopExecutor())
        controller.setBrushDiameterPx(InpaintBrushSize.MEDIUM_PRESET_PX)

        controller.sourcePixelSize = 6000 to 4000

        assertEquals(64, controller.brushDiameterPx)
        assertEquals(64f / 12_000f, canvas.brushRadiusNorm, 1e-6f)
        assertEquals(64, panel.diameterPx)
        assertEquals(
            160,
            InpaintBrushSize.diameterForRadiusNorm(
                InpaintBrushSize.radiusNormForDiameter(160, sourceWidthPx = 6000),
                sourceWidthPx = 6000
            )
        )
    }

    @Test
    fun shouldApplyQuickPresetsAndClampTheRange_whenTheUserChangesTheDiameter() {
        val controller = controller(NoopExecutor())

        controller.setBrushDiameterPx(24)
        assertEquals(InpaintBrushSize.radiusNormForDiameter(24, 1000), canvas.brushRadiusNorm, 1e-6f)
        assertEquals(24, panel.diameterPx)

        controller.setBrushDiameterPx(InpaintBrushSize.MAX_DIAMETER_PX + 400)
        assertEquals(InpaintBrushSize.MAX_DIAMETER_PX, controller.brushDiameterPx)
        assertEquals(InpaintBrushSize.MAX_DIAMETER_PX, panel.diameterPx)

        controller.setBrushDiameterPx(0)
        assertEquals(InpaintBrushSize.MIN_DIAMETER_PX, controller.brushDiameterPx)
    }

    @Test
    fun shouldHalveTheTileCount_whenTheQualityModeSwitchesToFast() {
        canvas.sourceWidth = 6000
        canvas.sourceHeight = 4000
        val controller = controller(NoopExecutor(), measuredTileMs = { 100L })
        controller.sourcePixelSize = 6000 to 4000
        controller.handleStrokesChanged(listOf(fullFrameStroke()))

        assertEquals(96, controller.workload?.tileCount)
        assertFalse(controller.workload!!.isLongRunning)

        controller.setQualityMode(InpaintQualityMode.FAST)

        assertEquals(48, controller.workload?.tileCount)
        assertEquals(InpaintQualityMode.FAST, panel.armedQualityMode)
        assertEquals(4_800L, controller.workload?.estimatedTotalMs)
    }

    @Test
    fun shouldShowTheNonBlockingBatchNote_whenTheEstimateExceeds15Seconds() = runBlocking {
        canvas.sourceWidth = 6000
        canvas.sourceHeight = 4000
        val controller = controller(
            executor = NoopExecutor(),
            tier = DeviceMemoryTier.LOW,
            measuredTileMs = { null }
        )
        controller.sourcePixelSize = 6000 to 4000
        canvas.strokeLayer.add(fullFrameStroke())
        controller.handleStrokesChanged(listOf(fullFrameStroke()))

        assertTrue(controller.workload!!.isLongRunning)
        assertTrue("a long estimate must not block the confirmation", controller.confirmInpaint())
        awaitIdle(controller)
        assertNull(controller.workload)
        assertEquals(1, host.inferenceFinished)
    }

    @Test
    fun shouldKeepOnlyTheStrokesThatStillCoverUncommittedCores_whenTheRunStopsEarly() {
        val controller = controller(NoopExecutor())
        controller.handleStrokesChanged(listOf(leftStroke(), rightStroke()))

        controller.handleRemainingCoverageChanged(
            listOf(InpaintPixelRect(0, 0, 500, 800))
        )

        assertEquals(1, canvas.replaceCalls)
        assertEquals(1, canvas.strokeLayer.strokeCount)
        assertEquals(40, controller.paintedWidthPx)
        assertTrue(panel.shownWorkload != null)
    }

    @Test
    fun shouldDropEveryStroke_whenNoUncommittedCoreRemains() {
        val controller = controller(NoopExecutor())
        controller.handleStrokesChanged(listOf(stroke()))

        controller.handleRemainingCoverageChanged(emptyList())

        assertEquals(1, canvas.replaceCalls)
        assertTrue(canvas.strokeLayer.isEmpty)
        assertNull(controller.workload)
    }

    @Test
    fun shouldKeepTheStrokesAndHideTheBar_whenTheInferenceIsCancelled() = runBlocking {
        val executor = BlockingExecutor()
        val controller = controller(executor)
        canvas.strokeLayer.add(stroke())

        assertTrue(controller.confirmInpaint())
        withTimeout(5_000) { executor.started.await() }
        assertTrue(controller.isInferring)
        controller.cancelInference()
        awaitIdle(controller)

        assertFalse(controller.isInferring)
        assertEquals(1, canvas.strokeLayer.strokeCount)
        assertEquals(1, host.inferenceStarted)
        assertEquals(1, host.inferenceFinished)
        assertEquals(0, host.failures)
        assertFalse(panel.busyState)
        assertFalse(canvas.strokesCleared)
    }

    @Test
    fun shouldClearTheStrokes_whenTheInferenceSucceeds() = runBlocking {
        val controller = controller(SuccessExecutor())
        canvas.strokeLayer.add(stroke())

        assertTrue(controller.confirmInpaint())
        withTimeout(5_000) { while (controller.isInferring) delay(5) }

        assertEquals(100, host.inferenceProgress.last())
        assertEquals(0, host.inferenceProgress.first())
        assertEquals(1, host.inferenceFinished)
        assertEquals(1, canvas.clearCalls)
        assertTrue(canvas.strokeLayer.isEmpty)
        assertFalse(panel.busyState)
        assertEquals(0, controller.paintedWidthPx)
        assertNull(controller.workload)
    }

    @Test
    fun shouldKeepTheShrunkStrokesAndReportTheFailure_whenABatchFailsPartially() = runBlocking {
        val executor = PartialFailureExecutor()
        val controller = controller(executor)
        canvas.strokeLayer.add(leftStroke())
        canvas.strokeLayer.add(rightStroke())
        controller.handleStrokesChanged(listOf(leftStroke(), rightStroke()))

        assertTrue(controller.confirmInpaint())
        withTimeout(5_000) { while (controller.isInferring) delay(5) }

        assertEquals(1, host.failures)
        assertEquals(1, canvas.replaceCalls)
        assertEquals(1, canvas.strokeLayer.strokeCount)
        assertEquals(1, host.inferenceFinished)
        assertFalse(canvas.strokesCleared)
    }

    @Test
    fun shouldRejectConfirm_whenNothingIsPainted() {
        val controller = controller(NoopExecutor())

        assertFalse(controller.confirmInpaint())

        assertEquals(1, host.noStrokes)
        assertEquals(0, host.inferenceStarted)
    }

    @Test
    fun shouldMirrorModeAndDiameter_whenTheyChange() {
        val controller = controller(NoopExecutor())

        controller.setBrushMode(InpaintBrushMode.ERASE)
        controller.setBrushDiameterPx(160)

        assertEquals(InpaintBrushMode.ERASE, canvas.brushMode)
        assertEquals(InpaintBrushMode.ERASE, panel.mode)
        assertEquals(InpaintBrushSize.radiusNormForDiameter(160, 1000), canvas.brushRadiusNorm, 1e-6f)
        assertEquals(160, panel.diameterPx)
    }

    private suspend fun awaitIdle(controller: InpaintBrushController) {
        withTimeout(5_000) {
            while (controller.isInferring) delay(5)
        }
    }

    private fun controller(
        executor: InpaintBrushController.InpaintExecutor,
        tier: DeviceMemoryTier = DeviceMemoryTier.STANDARD,
        measuredTileMs: () -> Long? = { null }
    ): InpaintBrushController = InpaintBrushController(
        host = host,
        canvas = canvas,
        panel = panel,
        scope = scope,
        executor = executor,
        memoryTier = tier,
        measuredTileMsProvider = measuredTileMs
    )

    private fun stroke(): InpaintStroke = InpaintStroke(
        erase = false,
        radiusNorm = 0.02f,
        points = listOf(InpaintStrokePoint(0.5f, 0.5f))
    )

    private fun leftStroke(): InpaintStroke = InpaintStroke(
        erase = false,
        radiusNorm = 0.02f,
        points = listOf(InpaintStrokePoint(0.1f, 0.5f))
    )

    private fun rightStroke(): InpaintStroke = InpaintStroke(
        erase = false,
        radiusNorm = 0.02f,
        points = listOf(InpaintStrokePoint(0.8f, 0.5f))
    )

    private fun fullFrameStroke(): InpaintStroke = InpaintStroke(
        erase = false,
        radiusNorm = 0.005f,
        points = listOf(
            InpaintStrokePoint(0.02f, 0.02f),
            InpaintStrokePoint(0.98f, 0.02f),
            InpaintStrokePoint(0.98f, 0.98f),
            InpaintStrokePoint(0.02f, 0.98f)
        )
    )

    private class FakeCanvas : InpaintBrushCanvasHost {
        override var brushMode: InpaintBrushMode = InpaintBrushMode.PAINT
        override var brushRadiusNorm: Float = 0.02f
        override var sourceWidth: Int = 1000
        override var sourceHeight: Int = 800
        override val strokeLayer = InpaintStrokeLayer()
        var abortCalls = 0
        var clearCalls = 0
        var replaceCalls = 0
        var strokesCleared = false

        override fun abortActiveStroke() {
            abortCalls++
        }

        override fun clearStrokes() {
            clearCalls++
            strokesCleared = true
            strokeLayer.clear()
        }

        override fun replaceStrokes(strokes: List<InpaintStroke>) {
            replaceCalls++
            strokeLayer.clear()
            strokes.forEach(strokeLayer::add)
        }
    }

    private class FakePanel : InpaintPanelHost {
        var mode: InpaintBrushMode = InpaintBrushMode.PAINT
        var diameterPx: Int = InpaintBrushSize.MEDIUM_PRESET_PX
        var armedQualityMode: InpaintQualityMode = InpaintQualityMode.FINE
        var shownWorkload: InpaintTileEta? = null
        var busyState = false

        override fun setBrushMode(mode: InpaintBrushMode) {
            this.mode = mode
        }

        override fun setBrushDiameterPx(diameterPx: Int) {
            this.diameterPx = diameterPx
        }

        override fun setQualityMode(mode: InpaintQualityMode) {
            armedQualityMode = mode
        }

        override fun setWorkload(eta: InpaintTileEta?) {
            shownWorkload = eta
        }

        override fun setBusy(busy: Boolean) {
            busyState = busy
        }
    }

    private class FakeHost : InpaintBrushController.Host {
        var inferenceStarted = 0
        var inferenceFinished = 0
        val inferenceProgress = mutableListOf<Int>()
        var failures = 0
        var lastFailure: Throwable? = null
        var noStrokes = 0
        var cleared = 0

        override fun onInpaintCanvasTransformChanged(transform: CanvasTransform) = Unit

        override fun onInpaintInferenceStarted() {
            inferenceStarted++
        }

        override fun onInpaintInferenceProgress(percent: Int) {
            inferenceProgress.add(percent)
        }

        override fun onInpaintInferenceFinished() {
            inferenceFinished++
        }

        override fun onInpaintFailed(cause: Throwable) {
            failures++
            lastFailure = cause
        }

        override fun onInpaintNoStrokes() {
            noStrokes++
        }

        override fun onInpaintStrokesCleared() {
            cleared++
        }
    }

    private class NoopExecutor : InpaintBrushController.InpaintExecutor {
        override suspend fun inpaint(
            strokes: List<InpaintStroke>,
            mode: InpaintQualityMode,
            onProgress: (Int) -> Unit,
            onRemainingCoverageChanged: (List<InpaintPixelRect>) -> Unit
        ): Result<Unit> = Result.success(Unit)
    }

    private class BlockingExecutor : InpaintBrushController.InpaintExecutor {
        val started = CompletableDeferred<Unit>()

        override suspend fun inpaint(
            strokes: List<InpaintStroke>,
            mode: InpaintQualityMode,
            onProgress: (Int) -> Unit,
            onRemainingCoverageChanged: (List<InpaintPixelRect>) -> Unit
        ): Result<Unit> {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private class SuccessExecutor : InpaintBrushController.InpaintExecutor {
        override suspend fun inpaint(
            strokes: List<InpaintStroke>,
            mode: InpaintQualityMode,
            onProgress: (Int) -> Unit,
            onRemainingCoverageChanged: (List<InpaintPixelRect>) -> Unit
        ): Result<Unit> {
            onProgress(0)
            onProgress(70)
            onProgress(100)
            return Result.success(Unit)
        }
    }

    private class PartialFailureExecutor : InpaintBrushController.InpaintExecutor {
        override suspend fun inpaint(
            strokes: List<InpaintStroke>,
            mode: InpaintQualityMode,
            onProgress: (Int) -> Unit,
            onRemainingCoverageChanged: (List<InpaintPixelRect>) -> Unit
        ): Result<Unit> {
            onProgress(40)
            onRemainingCoverageChanged(listOf(InpaintPixelRect(0, 0, 500, 800)))
            return Result.failure(
                InpaintUserMessageException(
                    R.string.editor_inpaint_batch_failed,
                    listOf(1, 2, "test")
                )
            )
        }
    }
}
