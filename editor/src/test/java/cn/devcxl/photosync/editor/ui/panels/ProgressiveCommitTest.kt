package cn.devcxl.photosync.editor.ui.panels

import java.util.concurrent.atomic.AtomicInteger

import ai.onnxruntime.OrtException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.InpaintBatchPlanner
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.domain.InpaintProgressiveCommit
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokePoint
import cn.devcxl.photosync.editor.domain.InpaintTilePlan
import cn.devcxl.photosync.editor.vision.MiGanFailureReason
import cn.devcxl.photosync.editor.vision.MiGanInpaintException
import cn.devcxl.photosync.editor.vision.MiGanTileOutcome
import cn.devcxl.photosync.editor.vision.OrtSessionFailureReason
import cn.devcxl.photosync.editor.vision.OrtSessionUnavailableException

/**
 * Seam under test: [InpaintProgressiveRunner] — the budgeted batch loop behind one confirmation.
 *
 * Every promise of spec §7.3/§7.4 is pinned here with a fake inference call: one commit per batch
 * (one undo step), completed batches survive cancellation and later failures, a single OOM halves
 * the batch once, a second OOM fails after keeping the earlier commits, an `OrtException` keeps the
 * earlier commits, and the measured per-tile time is written back.
 */
class ProgressiveCommitTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun shouldCommitOncePerBatchAndReportProgress_whenTheRunSucceeds() = runBlocking {
        val batches = InpaintBatchPlanner.of(tiles(5), batchTiles = 2)
        val commits = mutableListOf<InpaintBatchCommit>()
        val observedSizes = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val started = mutableListOf<Pair<Int, Int>>()
        val runner = runner(
            batches = batches,
            execute = { chunk, onTile ->
                observedSizes += chunk.size
                chunk.indices.forEach { index -> onTile(index + 1) }
                Result.success(chunk.map { outcome(it, elapsedMs = 1_000L) })
            },
            onCommit = commits::add,
            onBatchStarted = { batch, total -> started.add(batch.index + 1 to total) },
            onProgress = progress::add
        )

        val result = runner.run()

        assertTrue(result is InpaintProgressiveOutcome.Completed)
        assertEquals(listOf(2, 2, 1), observedSizes)
        assertEquals(3, commits.size)
        assertEquals(listOf(2, 2, 1), commits.map { it.patches.size })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), started)
        assertEquals(100, progress.last())
    }

    @Test
    fun shouldHalveTheBatchAndRetryOnce_whenTheFirstChunkRunsOutOfMemory() = runBlocking {
        val batches = InpaintBatchPlanner.of(tiles(4), batchTiles = 4)
        val observedSizes = mutableListOf<Int>()
        val commits = mutableListOf<InpaintBatchCommit>()
        val pressure = AtomicInteger(0)
        var failures = 0
        val runner = runner(
            batches = batches,
            execute = { chunk, _ ->
                observedSizes += chunk.size
                if (observedSizes.size == 1) {
                    failures++
                    Result.failure(outOfMemory())
                } else {
                    Result.success(chunk.map { outcome(it, elapsedMs = 1_200L) })
                }
            },
            onCommit = commits::add,
            onMemoryPressure = { pressure.incrementAndGet() }
        )

        val result = runner.run()

        assertTrue(result is InpaintProgressiveOutcome.Completed)
        assertEquals(1, failures)
        assertEquals(listOf(4, 2, 2), observedSizes)
        assertEquals(1, commits.size)
        assertEquals(4, commits.single().patches.size)
        assertEquals(1, pressure.get())
    }

    @Test
    fun shouldKeepTheEarlierCommit_whenTheHalvedRetryStillRunsOutOfMemory() = runBlocking {
        val batches = InpaintBatchPlanner.of(tiles(4), batchTiles = 2)
        val commits = mutableListOf<InpaintBatchCommit>()
        val pressure = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val runner = runner(
            batches = batches,
            execute = { chunk, _ ->
                val call = calls.incrementAndGet()
                when {
                    call == 1 -> Result.success(chunk.map { outcome(it, 1_000L) })
                    else -> Result.failure(outOfMemory())
                }
            },
            onCommit = commits::add,
            onMemoryPressure = { pressure.incrementAndGet() }
        )

        val result = runner.run()

        assertTrue(result is InpaintProgressiveOutcome.Failed)
        val failure = result as InpaintProgressiveOutcome.Failed
        assertEquals(1, failure.commits.size)
        assertEquals(2, failure.failedBatch)
        assertEquals(2, failure.totalBatches)
        assertTrue(failure.outOfMemory)
        assertEquals(2, pressure.get())
    }

    @Test
    fun shouldKeepTheEarlierCommit_whenAnOrtExceptionHitsALaterBatch() = runBlocking {
        val batches = InpaintBatchPlanner.of(tiles(4), batchTiles = 2)
        val commits = mutableListOf<InpaintBatchCommit>()
        val pressure = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val runner = runner(
            batches = batches,
            execute = { chunk, _ ->
                if (calls.incrementAndGet() == 1) {
                    Result.success(chunk.map { outcome(it, 800L) })
                } else {
                    Result.failure(
                        MiGanInpaintException(
                            MiGanFailureReason.RUNTIME_ERROR,
                            "onnx failed",
                            OrtException("Ort failure")
                        )
                    )
                }
            },
            onCommit = commits::add,
            onMemoryPressure = { pressure.incrementAndGet() }
        )

        val result = runner.run()

        assertTrue(result is InpaintProgressiveOutcome.Failed)
        val failure = result as InpaintProgressiveOutcome.Failed
        assertEquals(1, failure.commits.size)
        assertFalse(failure.outOfMemory)
        assertEquals(1, pressure.get())
    }

    @Test
    fun shouldClearTheSessionAndFail_whenTheFailureIsARecoverableRuntimeError() = runBlocking {
        val pressure = AtomicInteger(0)
        val runner = runner(
            batches = InpaintBatchPlanner.of(tiles(2), batchTiles = 2),
            execute = { _, _ -> Result.failure(OrtSessionUnavailableException(OrtSessionFailureReason.RUNTIME_ERROR)) },
            onCommit = {},
            onMemoryPressure = { pressure.incrementAndGet() }
        )

        val result = runner.run()

        assertTrue(result is InpaintProgressiveOutcome.Failed)
        assertEquals(0, (result as InpaintProgressiveOutcome.Failed).commits.size)
        assertEquals(1, pressure.get())
    }

    @Test
    fun shouldWriteBackTheAverageTileTime_whenABatchCommits() = runBlocking {
        val measured = mutableListOf<Long>()
        val runner = runner(
            batches = InpaintBatchPlanner.of(tiles(2), batchTiles = 2),
            execute = { chunk, _ ->
                Result.success(
                    listOf(
                        outcome(chunk[0], elapsedMs = 800L),
                        outcome(chunk[1], elapsedMs = 1_200L)
                    )
                )
            },
            onCommit = {},
            onMeasured = measured::add
        )

        runner.run()

        assertEquals(listOf(1_000L), measured)
    }

    @Test
    fun shouldKeepOnlyTheStrokesOverUncommittedCores_whenTheRunStopsEarly() {
        val left = strokeAt(0.1f, 0.5f)
        val right = strokeAt(0.8f, 0.5f)
        val strokes = listOf(left, right)

        val retained = InpaintProgressiveCommit.retainUncommitted(
            strokes = strokes,
            remainingCores = listOf(InpaintPixelRect(0, 0, 500, 800)),
            sourceWidth = 1000,
            sourceHeight = 800
        )

        assertEquals(listOf(left), retained)
        assertTrue(
            "a fully committed plan must leave no stroke behind",
            InpaintProgressiveCommit.retainUncommitted(
                strokes, emptyList(), 1000, 800
            ).isEmpty()
        )
    }

    @Test
    fun shouldKeepTheCommittedBatch_whenTheRunIsCancelled() = runBlocking {
        val batches = InpaintBatchPlanner.of(tiles(4), batchTiles = 2)
        val secondBatchStarted = CompletableDeferred<Unit>()
        val commits = mutableListOf<InpaintBatchCommit>()
        val runner = runner(
            batches = batches,
            execute = { chunk, _ ->
                if (commits.isEmpty()) {
                    Result.success(chunk.map { outcome(it, 1_000L) })
                } else {
                    secondBatchStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            },
            onCommit = commits::add
        )
        val job = scope.launch { runner.run() }
        withTimeout(5_000) { secondBatchStarted.await() }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, runner.committedBatches.size)
        assertEquals(2, runner.committedBatches.single().patches.size)
    }

    private fun runner(
        batches: cn.devcxl.photosync.editor.domain.InpaintBatches,
        execute: suspend (List<InpaintTilePlan>, (Int) -> Unit) -> Result<List<MiGanTileOutcome>>,
        onCommit: (InpaintBatchCommit) -> Unit,
        onMeasured: (Long) -> Unit = {},
        onMemoryPressure: () -> Unit = {},
        onBatchStarted: (cn.devcxl.photosync.editor.domain.InpaintBatch, Int) -> Unit = { _, _ -> },
        onProgress: (Int) -> Unit = {}
    ): InpaintProgressiveRunner = InpaintProgressiveRunner(
        batches = batches,
        executeBatch = execute,
        onBatchCommitted = onCommit,
        onTileMeasured = onMeasured,
        onMemoryPressure = onMemoryPressure,
        onBatchStarted = onBatchStarted,
        onProgress = onProgress
    )

    private fun tiles(count: Int): List<InpaintTilePlan> = (0 until count).map { index ->
        val core = InpaintPixelRect(index * 100, 0, index * 100 + 80, 80)
        InpaintTilePlan(index = index, coreRect = core, cropRect = core.expandedBy(10))
    }

    private fun strokeAt(u: Float, v: Float): InpaintStroke = InpaintStroke(
        erase = false,
        radiusNorm = 0.02f,
        points = listOf(InpaintStrokePoint(u, v))
    )

    private fun outcome(tile: InpaintTilePlan, elapsedMs: Long): MiGanTileOutcome = MiGanTileOutcome(
        tile = tile,
        patch = InpaintPatchRef(
            bbox = InpaintBBox(0.1f, 0.1f, 0.2f, 0.2f),
            patchRelPath = "edits/1/inpaint_patch_${tile.index}_deadbeef.png",
            maskRelPath = "edits/1/inpaint_mask_${tile.index}_deadbeef.png",
            sha256 = "deadbeef"
        ),
        elapsedMs = elapsedMs
    )

    private fun outOfMemory(): MiGanInpaintException = MiGanInpaintException(
        reason = MiGanFailureReason.RUNTIME_ERROR,
        message = "out of memory",
        cause = OutOfMemoryError("test")
    )
}
