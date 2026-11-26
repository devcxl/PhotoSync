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

import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer
import cn.devcxl.photosync.editor.domain.InpaintStrokePoint
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.panels.InpaintBrushController

/**
 * Seam under test: [InpaintBrushController] — the brush state machine around the removal run.
 *
 * Covers the three interaction promises of the design: two fingers discard only the unfinished
 * stroke, a >256 px bbox raises the amber advice without blocking, and cancelling the inference
 * leaves the strokes and the previous edit graph untouched.
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
    fun shouldShowTheAmberAdviceAndSuggestSplits_whenTheBboxExceeds256() {
        val controller = controller(NoopExecutor())
        val wide = InpaintStroke(
            erase = false,
            radiusNorm = 0.02f,
            points = listOf(InpaintStrokePoint(0.1f, 0.5f), InpaintStrokePoint(0.36f, 0.5f))
        )

        controller.handleStrokesChanged(listOf(wide))

        assertEquals(Triple(300, 40, 2), panel.warning)
        assertEquals(Triple(300, 40, true), host.lastBbox)
        assertEquals(300, controller.paintedWidthPx)

        controller.handleStrokesChanged(listOf(stroke()))

        assertNull(panel.warning)
        assertEquals(Triple(40, 40, false), host.lastBbox)
        assertEquals(1, panel.warningHiddenCount)
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
        assertTrue(host.patches.isEmpty())
        assertEquals(0, host.failures)
        assertFalse(panel.busyState)
        assertFalse(canvas.strokesCleared)
    }

    @Test
    fun shouldClearTheStrokesAndReportPatches_whenTheInferenceSucceeds() = runBlocking {
        val patch = InpaintPatchRef(
            bbox = InpaintBBox(0.1f, 0.1f, 0.2f, 0.2f),
            patchRelPath = "edits/1/inpaint_patch_deadbeef.png",
            maskRelPath = "edits/1/inpaint_mask_deadbeef.png",
            sha256 = "deadbeef"
        )
        val controller = controller(SuccessExecutor(patch))
        canvas.strokeLayer.add(stroke())

        assertTrue(controller.confirmInpaint())
        withTimeout(5_000) { while (host.patches.isEmpty()) delay(5) }

        assertEquals(listOf(patch), host.patches)
        assertEquals(100, host.inferenceProgress.last())
        assertEquals(0, host.inferenceProgress.first())
        assertEquals(1, host.inferenceFinished)
        assertEquals(1, canvas.clearCalls)
        assertTrue(canvas.strokeLayer.isEmpty)
        assertFalse(panel.busyState)
        assertEquals(0, controller.paintedWidthPx)
    }

    @Test
    fun shouldRejectConfirm_whenNothingIsPainted() {
        val controller = controller(NoopExecutor())

        assertFalse(controller.confirmInpaint())

        assertEquals(1, host.noStrokes)
        assertEquals(0, host.inferenceStarted)
    }

    @Test
    fun shouldMirrorModeAndBrushSize_whenTheyChange() {
        val controller = controller(NoopExecutor())

        controller.setBrushMode(InpaintBrushMode.ERASE)
        controller.setBrushRadiusNorm(0.05f)

        assertEquals(InpaintBrushMode.ERASE, canvas.brushMode)
        assertEquals(InpaintBrushMode.ERASE, panel.mode)
        assertEquals(0.05f, canvas.brushRadiusNorm, 1e-6f)
        assertEquals(0.05f, panel.radius, 1e-6f)
    }

    private suspend fun awaitIdle(controller: InpaintBrushController) {
        withTimeout(5_000) {
            while (controller.isInferring) delay(5)
        }
    }

    private fun controller(executor: InpaintBrushController.InpaintExecutor): InpaintBrushController =
        InpaintBrushController(
            host = host,
            canvas = canvas,
            panel = panel,
            scope = scope,
            executor = executor
        )

    private fun stroke(): InpaintStroke = InpaintStroke(
        erase = false,
        radiusNorm = 0.02f,
        points = listOf(InpaintStrokePoint(0.5f, 0.5f))
    )

    private class FakeCanvas : InpaintBrushCanvasHost {
        override var brushMode: InpaintBrushMode = InpaintBrushMode.PAINT
        override var brushRadiusNorm: Float = 0.02f
        override var sourceWidth: Int = 1000
        override var sourceHeight: Int = 800
        override val strokeLayer = InpaintStrokeLayer()
        var abortCalls = 0
        var clearCalls = 0
        var strokesCleared = false

        override fun abortActiveStroke() {
            abortCalls++
        }

        override fun clearStrokes() {
            clearCalls++
            strokesCleared = true
            strokeLayer.clear()
        }
    }

    private class FakePanel : InpaintPanelHost {
        var mode: InpaintBrushMode = InpaintBrushMode.PAINT
        var radius: Float = 0.02f
        var warning: Triple<Int, Int, Int>? = null
        var warningHiddenCount = 0
        var busyState = false

        override fun setBrushMode(mode: InpaintBrushMode) {
            this.mode = mode
        }

        override fun setBrushRadiusNorm(radiusNorm: Float) {
            radius = radiusNorm
        }

        override fun showOversizeWarning(widthPx: Int, heightPx: Int, suggestedSplits: Int) {
            warning = Triple(widthPx, heightPx, suggestedSplits)
        }

        override fun hideOversizeWarning() {
            warning = null
            warningHiddenCount++
        }

        override fun setBusy(busy: Boolean) {
            busyState = busy
        }
    }

    private class FakeHost : InpaintBrushController.Host {
        var lastBbox: Triple<Int, Int, Boolean>? = null
        var inferenceStarted = 0
        var inferenceFinished = 0
        val inferenceProgress = mutableListOf<Int>()
        val patches = mutableListOf<InpaintPatchRef>()
        var failures = 0
        var noStrokes = 0
        var cleared = 0

        override fun onInpaintCanvasTransformChanged(transform: CanvasTransform) = Unit

        override fun onInpaintBboxEstimated(widthPx: Int, heightPx: Int, isOversized: Boolean) {
            lastBbox = Triple(widthPx, heightPx, isOversized)
        }

        override fun onInpaintInferenceStarted() {
            inferenceStarted++
        }

        override fun onInpaintInferenceProgress(percent: Int) {
            inferenceProgress.add(percent)
        }

        override fun onInpaintInferenceFinished() {
            inferenceFinished++
        }

        override fun onInpaintPatchesReady(patches: List<InpaintPatchRef>) {
            this.patches.addAll(patches)
        }

        override fun onInpaintFailed(cause: Throwable) {
            failures++
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
            onProgress: (Int) -> Unit
        ): Result<List<InpaintPatchRef>> = Result.success(emptyList())
    }

    private class BlockingExecutor : InpaintBrushController.InpaintExecutor {
        val started = CompletableDeferred<Unit>()

        override suspend fun inpaint(
            strokes: List<InpaintStroke>,
            onProgress: (Int) -> Unit
        ): Result<List<InpaintPatchRef>> {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private class SuccessExecutor(
        private val patch: InpaintPatchRef
    ) : InpaintBrushController.InpaintExecutor {
        override suspend fun inpaint(
            strokes: List<InpaintStroke>,
            onProgress: (Int) -> Unit
        ): Result<List<InpaintPatchRef>> {
            onProgress(0)
            onProgress(70)
            onProgress(100)
            return Result.success(listOf(patch))
        }
    }
}
