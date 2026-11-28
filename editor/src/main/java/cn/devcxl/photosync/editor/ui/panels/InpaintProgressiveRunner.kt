package cn.devcxl.photosync.editor.ui.panels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

import cn.devcxl.photosync.editor.domain.InpaintBatch
import cn.devcxl.photosync.editor.domain.InpaintBatches
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintTilePlan
import cn.devcxl.photosync.editor.vision.MiGanFailureReason
import cn.devcxl.photosync.editor.vision.MiGanInpaintException
import cn.devcxl.photosync.editor.vision.MiGanTileOutcome
import cn.devcxl.photosync.editor.vision.OrtSessionFailureReason
import cn.devcxl.photosync.editor.vision.OrtSessionUnavailableException

/**
 * One batch that finished and was appended to `EditGraph.inpaint` as a single undo step.
 *
 * @property batchIndex Zero-based batch order.
 * @property totalBatches Number of batches the run planned.
 * @property tiles Tiles the batch executed, in order.
 * @property patches Ordered patch references of [tiles].
 * @property averageTileMs Measured mean tile time of the batch; `0` when nothing was measured.
 */
internal data class InpaintBatchCommit(
    val batchIndex: Int,
    val totalBatches: Int,
    val tiles: List<InpaintTilePlan>,
    val patches: List<InpaintPatchRef>,
    val averageTileMs: Long
)

/** How a progressive run ended. */
internal sealed interface InpaintProgressiveOutcome {

    /** Every planned tile was executed and committed. */
    data class Completed(val commits: List<InpaintBatchCommit>) : InpaintProgressiveOutcome

    /**
     * A batch failed after [commits] earlier batches were already committed.
     *
     * @property failedBatch 1-based batch number that failed.
     * @property totalBatches Number of batches the run planned.
     * @property outOfMemory True when the failure was a memory exhaustion after the halving retry.
     */
    data class Failed(
        val commits: List<InpaintBatchCommit>,
        val failedBatch: Int,
        val totalBatches: Int,
        val cause: Throwable,
        val outOfMemory: Boolean
    ) : InpaintProgressiveOutcome
}

/**
 * Executes an [InpaintBatches] plan batch by batch, committing after each slice (spec §7.3/§7.4).
 *
 * ## Contract
 * - **One batch = one commit**: [onBatchCommitted] runs once per successful batch, never per tile,
 *   so the caller can append the patches and push exactly one undo transaction.
 * - **OOM halving**: a single `OutOfMemoryError` halves the batch once, clears the session through
 *   [onMemoryPressure] and re-runs the remaining tiles in the smaller chunks. A second memory
 *   failure fails the batch.
 * - **Other recoverable failures** clear the session and fail the batch; earlier commits survive.
 * - **Cancellation** rethrows [CancellationException]; committed batches are kept in
 *   [committedBatches], and the caller shrinks the strokes to the batches it did not finish.
 * - **Dispatcher**: every callback runs on the caller's dispatcher between suspending model calls,
 *   which is what lets the caller touch UI and undo state without posting.
 */
internal class InpaintProgressiveRunner(
    private val batches: InpaintBatches,
    private val executeBatch: suspend (
        tiles: List<InpaintTilePlan>,
        onTileProgress: (Int) -> Unit
    ) -> Result<List<MiGanTileOutcome>>,
    private val onBatchCommitted: (InpaintBatchCommit) -> Unit,
    private val onTileMeasured: (Long) -> Unit = {},
    private val onMemoryPressure: () -> Unit = {},
    private val onBatchStarted: (InpaintBatch, Int) -> Unit = { _, _ -> },
    private val onProgress: (Int) -> Unit = {}
) {

    private val commits = mutableListOf<InpaintBatchCommit>()

    /** Batches committed so far, in order; read after a cancellation or failure. */
    val committedBatches: List<InpaintBatchCommit>
        get() = commits.toList()

    /** Runs every batch; throws [CancellationException] when the caller cancels. */
    suspend fun run(): InpaintProgressiveOutcome {
        val totalTiles = batches.tileCount.coerceAtLeast(1)
        var committedTiles = 0
        for (batch in batches.batches) {
            currentCoroutineContext().ensureActive()
            onBatchStarted(batch, batches.count)
            val outcomes = ArrayList<MiGanTileOutcome>(batch.size)
            var chunkSize = batch.size
            var halved = false
            var position = 0
            while (position < batch.size) {
                currentCoroutineContext().ensureActive()
                val end = minOf(position + chunkSize, batch.size)
                val chunk = batch.tiles.subList(position, end)
                val chunkProgress = { local: Int ->
                    val absolute = (committedTiles + position + local).coerceAtMost(totalTiles)
                    onProgress(absolute * PERCENT_SCALE / totalTiles)
                }
                val result = try {
                    executeBatch(chunk, chunkProgress)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                result.fold(
                    onSuccess = { tileOutcomes ->
                        outcomes.addAll(tileOutcomes)
                        position = end
                    },
                    onFailure = { error ->
                        if (isMemoryFailure(error) && !halved && chunk.size > 1) {
                            onMemoryPressure()
                            halved = true
                            chunkSize = maxOf(1, batch.size / 2)
                        } else {
                            clearSessionIfRecoverable(error)
                            return InpaintProgressiveOutcome.Failed(
                                commits = commits.toList(),
                                failedBatch = batch.index + 1,
                                totalBatches = batches.count,
                                cause = error,
                                outOfMemory = isMemoryFailure(error)
                            )
                        }
                    }
                )
            }
            val commit = InpaintBatchCommit(
                batchIndex = batch.index,
                totalBatches = batches.count,
                tiles = batch.tiles,
                patches = outcomes.map { it.patch },
                averageTileMs = averageTileMs(outcomes)
            )
            commits.add(commit)
            onBatchCommitted(commit)
            if (commit.averageTileMs > 0L) onTileMeasured(commit.averageTileMs)
            committedTiles += batch.size
            onProgress(committedTiles * PERCENT_SCALE / totalTiles)
        }
        return InpaintProgressiveOutcome.Completed(commits.toList())
    }

    private fun averageTileMs(outcomes: List<MiGanTileOutcome>): Long {
        if (outcomes.isEmpty()) return 0L
        val total = outcomes.sumOf { it.elapsedMs.coerceAtLeast(0L) }
        return total / outcomes.size
    }

    /** A missing model is not a session problem; every other recoverable failure clears it. */
    private fun clearSessionIfRecoverable(error: Throwable) {
        val reason = (error as? MiGanInpaintException)?.reason
        if (reason == MiGanFailureReason.MODEL_MISSING) return
        onMemoryPressure()
    }

    private fun isMemoryFailure(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is OutOfMemoryError) return true
            if (current is OrtSessionUnavailableException &&
                current.reason == OrtSessionFailureReason.OUT_OF_MEMORY
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private companion object {
        const val PERCENT_SCALE = 100
    }
}
