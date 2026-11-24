package cn.devcxl.photosync.editor.ui

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.domain.PortraitWarpComposer
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Renders the screen-sized preview cache the gallery shows for an edited photo.
 *
 * Implemented by [cn.devcxl.photosync.editor.render.EditorPreviewSession]. The seam keeps
 * [EditorSaveCoordinator] free of Android bitmaps, so its failure and ordering rules are unit
 * testable on the JVM.
 */
internal fun interface EditorPreviewCacheRenderer {

    /**
     * Renders the preview cache for [graph].
     *
     * @param warpField Composed face + body + liquify grid, so the cache cannot disagree with the
     *   canvas about a warp.
     * @param skinMask Portrait analysis mask, or `null` when the portrait group is unused.
     * @return Absolute path of the written JPEG, or `null` when nothing could be written.
     */
    fun writePreviewCache(
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        skinMask: FloatArray?
    ): String?
}

/**
 * Everything one save reads, frozen on the main thread the moment the user presses Done.
 *
 * The live editor state is mutable and outlives any worker: [LiquifySession.mesh] is reassigned by
 * every stroke, undo and redo, the portrait analysis is swapped when the multiclass model finishes
 * downloading, and `EditorActivity.onDestroy` recycles the staged source bitmaps. A worker thread
 * reading those objects directly could therefore persist a grid captured in the middle of a stroke,
 * combine landmarks with a mask from a later analysis, or touch a recycled bitmap. This snapshot
 * removes all three from the race: the grid is a private copy, the analysis is the immutable object
 * that was current at capture time, and the source pixels stay alive through the lease this
 * snapshot owns until [close] returns it.
 *
 * @property graph The undo controller's current graph. Immutable by construction: the controller
 *   replaces it wholesale instead of mutating it.
 * @property liquifyMesh Private copy of the displacement grid, or `null` when the brush left nothing
 *   visible and the persisted reference must be cleared.
 * @property portrait The analysis current at capture time, or `null` when the Portrait tab was never
 *   opened; its masks are read-only after construction.
 * @property hasSource Whether the session still had source pixels staged at capture time.
 * @property releaseSourceLease Returns the pixels this snapshot borrowed from the session.
 */
internal class EditorSaveSnapshot(
    val graph: EditGraph,
    val liquifyMesh: LiquifyMesh?,
    val portrait: PortraitAnalysis?,
    val hasSource: Boolean,
    private val releaseSourceLease: () -> Unit = {}
) : AutoCloseable {

    private val leaseReturned = AtomicBoolean(false)

    /**
     * Returns the source lease taken when this snapshot was captured.
     *
     * Idempotent, because both owners close it: [EditorSaveCoordinator.persist] in its `finally`,
     * and [EditorFinishAction] when the save never starts (a dead scope, a throwing progress
     * callback). Exactly one of them releases the pixels.
     */
    override fun close() {
        if (leaseReturned.compareAndSet(false, true)) {
            releaseSourceLease()
        }
    }
}

/**
 * Counts the in-flight saves that are reading the editor's staged source pixels, so the activity's
 * teardown cannot recycle them under a running save.
 *
 * `EditorActivity.onDestroy` recycles the source bitmaps while a save may still be resampling them
 * on a worker thread, which on Android can take the process down inside native pixel access. The
 * lease turns "destroy whenever" into "destroy once the last reader is done": both
 * [acquire] and [releaseWhenFree] are called from the main thread, so the two can never interleave
 * and a release requested after a snapshot was taken is always deferred to that snapshot's
 * [release].
 *
 * Every lease is returned: [EditorSaveSnapshot.close] is idempotent and is called both by the save
 * that used the pixels and by [EditorFinishAction] on the paths where the save never runs, so a
 * lease cannot outlive the Done action that took it.
 */
internal class EditorSaveSourceLease {

    private val lock = Any()
    private var holders: Int = 0
    private var deferredRelease: (() -> Unit)? = null

    /** Starts reading the source pixels. Main thread only. */
    fun acquire() {
        synchronized(lock) {
            holders++
        }
    }

    /** Stops reading the source pixels and runs a release that was requested meanwhile. */
    fun release() {
        val runnable = synchronized(lock) {
            if (holders > 0) holders--
            if (holders == 0) deferredRelease.also { deferredRelease = null } else null
        }
        runnable?.invoke()
    }

    /**
     * Runs [release] now, or as soon as the last reader has released the lease.
     *
     * A second request replaces a pending one; the editor only ever asks once, from `onDestroy`.
     */
    fun releaseWhenFree(release: () -> Unit) {
        val runNow = synchronized(lock) {
            if (holders == 0) {
                true
            } else {
                deferredRelease = release
                false
            }
        }
        if (runNow) release()
    }
}

/**
 * Persists a finished edit: renders the screen-sized preview cache, writes the liquify displacement
 * grid, then stores the [EditGraph] through the host `EditStore`, which drops the record when the
 * graph is an identity edit.
 *
 * ## Threading
 * [captureSnapshot] must run on the main thread; [persist] does all of its work on
 * [Dispatchers.Default] (the CPU render) and [Dispatchers.IO] (the files and the database), so a
 * caller may await it from the UI coroutine without blocking a frame.
 *
 * ## Failure rules
 * Everything this class writes is additive: a preview or a payload file is created under a new name
 * and no existing file is ever overwritten or deleted. A save therefore either commits a record that
 * fully describes the edit, or leaves the previous record's row and payloads untouched — the one
 * exception is a cancellation that lands inside the store's own transaction, which decides on its
 * own whether the row lands (see [persist]).
 * - a non-identity edit whose preview cache cannot be rendered is **not** committed, because a row
 *   without a preview path would make the gallery and filmstrip show stale pixels as if the edit
 *   had been saved;
 * - the preview is rendered before any payload file is written, so a render failure cannot touch
 *   what the previous record references;
 * - the displacement grid is written under a **new, content-addressed** name (whole SHA-256), so
 *   neither a mesh failure nor a database failure nor a cancellation can overwrite or delete the
 *   payload the previous record verifies;
 * - a mesh write that fails aborts before the database row is touched;
 * - cancellation is never converted into `false`: it propagates so the caller can tell "the user
 *   left the editor" apart from "saving failed".
 *
 * ## Retired payloads are kept
 * A save never deletes the payload it supersedes, nor one written by another writer (the tethered
 * darkroom, a concurrent editor, a preset apply) — retiring a payload safely needs a single owner of
 * the photo's payloads plus coordination with any in-flight export, which does not exist yet. The
 * cost is one ≈65 KiB file per liquify edit that reaches this save path; see [LiquifyMeshStore] for
 * the retention rule and the conditions under which a future collector may reclaim them.
 */
internal class EditorSaveCoordinator(
    private val photoId: Long,
    private val meshStore: LiquifyMeshStore,
    private val previewRenderer: EditorPreviewCacheRenderer,
    private val editStore: () -> EditStore? = { EditorHostRegistry.editStore },
    private val sourceLease: EditorSaveSourceLease = EditorSaveSourceLease()
) {

    /**
     * Freezes the session's live state into an [EditorSaveSnapshot]. Main thread only.
     *
     * The returned snapshot owns a lease on the staged source pixels: the activity's teardown may
     * request the release from now on, but the bitmaps are recycled only after [EditorSaveSnapshot.close]
     * — which [persist] guarantees, and which the caller must also do when it never persists the
     * snapshot.
     *
     * @param graph The undo controller's current graph.
     * @param liquify The session whose displacement grid must be copied.
     * @param portrait The analysis the Portrait tab is currently showing, if it ran.
     * @param hasSource Whether the session has source pixels staged; a save cannot render a preview
     *   without them.
     */
    fun captureSnapshot(
        graph: EditGraph,
        liquify: LiquifySession,
        portrait: PortraitAnalysis?,
        hasSource: Boolean
    ): EditorSaveSnapshot {
        val snapshot = EditorSaveSnapshot(
            graph = graph,
            liquifyMesh = if (liquify.hasMesh) liquify.mesh.copy() else null,
            portrait = portrait,
            hasSource = hasSource,
            releaseSourceLease = sourceLease::release
        )
        sourceLease.acquire()
        return snapshot
    }

    /**
     * Releases the session's source pixels, deferred until every in-flight save has finished
     * rendering from them. Call from the activity's `onDestroy`.
     */
    fun releasePreviewSource(release: () -> Unit) {
        sourceLease.releaseWhenFree(release)
    }

    /**
     * Persists [snapshot] and returns the source lease it borrowed.
     *
     * @return `true` only when the save finished and the store accepted the record. `false` means
     *   nothing was reported as saved: either a step failed before the store was called, or the
     *   store itself failed. The caller must keep the editor open with its edit intact in both
     *   cases.
     * @throws CancellationException when the calling coroutine is cancelled. A cancellation that
     *   lands inside the store's own transaction may still have committed the row — the store gives
     *   no cancelled-or-committed answer — so the caller must report neither success nor failure,
     *   which is exactly what rethrowing achieves.
     */
    suspend fun persist(snapshot: EditorSaveSnapshot): Boolean {
        try {
            return persistOrThrow(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (io: IOException) {
            Timber.e(io, "Editor save for photoId=%d failed while writing", photoId)
            return false
        } catch (error: RuntimeException) {
            Timber.e(error, "Editor save for photoId=%d failed", photoId)
            return false
        } finally {
            snapshot.close()
        }
    }

    private suspend fun persistOrThrow(snapshot: EditorSaveSnapshot): Boolean {
        val graph = snapshot.graph
        // The live graph's liquify reference is bookkeeping, not an edit: what decides identity is
        // whether a displacement grid survives the save.
        val liquifyCleared = snapshot.liquifyMesh == null
        val identityAfterSave = liquifyCleared &&
            graph.copy(geometry = graph.geometry.copy(liquify = null)).isIdentity()

        // The preview goes first: it only adds a uniquely named cache file, so a failed render can
        // abort the save before a payload is added or a previous payload is retired.
        val previewPath = if (identityAfterSave) {
            null
        } else {
            val rendered = withContext(Dispatchers.Default) { renderPreviewCache(snapshot) }
            if (rendered == null) {
                Timber.e(
                    "Preview cache could not be rendered for photoId=%d; keeping the previous record",
                    photoId
                )
                return false
            }
            rendered
        }

        val liquifyRef = withContext(Dispatchers.IO) { writeLiquifyMesh(snapshot.liquifyMesh) }
        if (!liquifyCleared && liquifyRef == null) {
            Timber.e("Liquify displacement mesh could not be persisted for photoId=%d", photoId)
            return false
        }
        val finalGraph = graph.copy(geometry = graph.geometry.copy(liquify = liquifyRef))
        val store = editStore()
        if (store == null) {
            Timber.e("EditorHostRegistry.editStore is null; edit cannot be persisted")
            return false
        }
        // Commit point: a cancellation that raced the render must not reach the store.
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            store.saveEditForPhoto(
                photoId = photoId,
                graph = finalGraph,
                previewCachePath = previewPath
            )
        }
        return true
    }

    /**
     * Renders the preview cache with the exact `uWarpTex` and `skinMask` the canvas showed, so the
     * gallery thumbnail cannot disagree with the editor about a face or body warp.
     */
    private fun renderPreviewCache(snapshot: EditorSaveSnapshot): String? {
        if (!snapshot.hasSource) {
            Timber.e(
                "No source pixels are staged for photoId=%d; refusing to save an edit without a preview",
                photoId
            )
            return null
        }
        val analysis = snapshot.portrait
        val warpField = PortraitWarpComposer.compose(
            analysis?.faceWarp(snapshot.graph.portrait.reshape),
            analysis?.bodyWarp(snapshot.graph.portrait.body),
            snapshot.liquifyMesh
        )
        return previewRenderer.writePreviewCache(snapshot.graph, warpField, analysis?.skinMask)
    }

    /**
     * Writes [mesh] as a new content-addressed payload.
     *
     * An absent grid writes nothing and deletes nothing: the previous record may still reference the
     * payload it had, and no other writer's file is ever touched.
     *
     * @return The reference to record in `EditGraph.geometry.liquify`, or `null` for an empty grid.
     */
    private fun writeLiquifyMesh(mesh: LiquifyMesh?): LiquifyRef? =
        mesh?.let { meshStore.write(photoId, it) }
}
