package cn.devcxl.photosync.editor.render

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import cn.devcxl.photosync.editor.export.BakeConcurrencyGate

/**
 * Bounded concurrency for the CPU preview fallback.
 *
 * The CPU writer is memory hungry (a full preview bitmap plus grading buffers), so several gallery
 * pastes falling back at the same time must not multiply that peak. Raw and JPEG previews have their
 * own single-permit gate:
 * - a RAW CPU render additionally takes [BakeConcurrencyGate.rawBakeMutex], so it never competes with
 *   a full-resolution export bake for the memory budget;
 * - a JPEG CPU render is independent of that bake gate, so a long RAW bake cannot stall it.
 *
 * ## Lock order
 * A caller always acquires the source lease **before** a gate here, and inside a gate the RAW bake
 * mutex is the innermost lock: `lease → preview gate → rawBakeMutex`. Nothing takes a preview gate
 * while holding the bake mutex (only the export pipeline holds it, and it never renders previews), so
 * the order is acyclic — which matters because `RecentEditorSourceCache.acquire` takes the same bake
 * mutex for its own decode and coroutine mutexes are not reentrant.
 */
internal object PreviewCpuGate {

    /** Serialises RAW CPU preview renders. */
    private val rawPreviewMutex = Mutex()

    /** Serialises JPEG CPU preview renders; deliberately independent of the bake gate. */
    private val jpegPreviewMutex = Mutex()

    /**
     * Runs one CPU preview render.
     *
     * @param isRaw True when the source is a RAW decode, which additionally yields to a running bake.
     */
    suspend fun <T> render(isRaw: Boolean, block: suspend () -> T): T =
        if (isRaw) {
            rawPreviewMutex.withLock {
                BakeConcurrencyGate.rawBakeMutex.withLock { block() }
            }
        } else {
            jpegPreviewMutex.withLock { block() }
        }
}
