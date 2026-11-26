package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

import android.content.Context
import android.os.SystemClock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.export.BakeConcurrencyGate
import cn.devcxl.photosync.editor.export.BakePortraitSupport
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.vision.PortraitPreviewCache
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Renders the screen-sized preview JPEG of a graph that no editor session ever opened — used both
 * by `LiveTetherPreset` ingest and by quick-preset / paste actions from the main gallery.
 *
 * Sources come from [RecentEditorSourceCache], which serializes RAW decodes on
 * [BakeConcurrencyGate.rawBakeMutex] so parallel syncs or gallery pastes never decode multiple full
 * RAW buffers concurrently. Portrait analysis comes from the cached, graph-free
 * [BakePortraitSupport.resolvePreview]. The render itself runs on the offscreen GPU chain and falls
 * back to the CPU writer; every failure is logged and yields `null` so a half-bound edit row is never
 * written.
 */
object BackgroundPreviewRenderer {

    /** Diagnostic route labels for the timing log; they never contain a file path. */
    private const val ROUTE_NONE = "none"
    private const val ROUTE_GPU = "gpu"
    private const val ROUTE_CPU = "cpu"
    private const val ROUTE_DISCARDED = "discarded"

    /**
     * Renders [graph] applied to the photo at [sourcePath] into `cacheDir/edits/<photoId>/`.
     *
     * @return The absolute path of the written JPEG, or `null` when nothing could be rendered.
     */
    suspend fun render(
        context: Context,
        filesDir: File,
        cacheDir: File,
        photoId: Long,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): String? {
        if (photoId <= 0L || sourcePath.isBlank() || graph.isIdentity()) return null
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) {
            Timber.e("Preview cache directory could not be created for photoId=%d", photoId)
            return null
        }
        // Never delete an existing preview up front: the writer emits a unique file name, so a
        // failed render leaves the previously published preview intact. Stale files are left to the
        // system cache eviction or an explicit edit; nothing prunes them automatically yet.
        return renderToDir(context, filesDir, editDir, sourcePath, isRaw, graph)?.absolutePath
    }

    /**
     * Renders [graph] into [outDir] without a photo id, so an ingestor can stage the JPEG in a temp
     * directory before it knows the id Room will assign.
     *
     * ## Source ownership
     * The source is leased from [RecentEditorSourceCache] and the lease is **handed to the GPU job**,
     * which keeps the pixels alive until it is finished — so returning early (timeout, cancellation)
     * can never let the worker sample a recycled bitmap. The CPU fallback acquires its own lease.
     *
     * ## Lock order
     * The lease is always taken before `BakeConcurrencyGate.rawBakeMutex`: `acquire` takes that gate
     * for its own RAW decode and coroutine mutexes are not reentrant. The only other gate acquisition
     * is the RAW portrait analysis in [resolvePortrait], which happens after the lease exists and
     * never wraps it.
     *
     * ## Source identity
     * The decoded pixels, the portrait analysis and the written cache must describe the same file
     * version. The lease exposes the exact [EditorSourceKey] the cache read **before** decoding it, and
     * that key is re-checked after the analysis and before publishing; the CPU fallback re-leases and
     * must produce the same key. A change abandons this output (`null`) and leaves the previously
     * published preview untouched.
     *
     * ## Failure policy
     * The preparation (portrait analysis, LUT load) is expected to fail on real devices: an expected
     * failure — OOM, I/O error, MediaPipe `RuntimeException` — is logged and yields `null` while
     * releasing the not-yet-transferred lease, exactly like the CPU-only renderer did. Cancellation
     * keeps propagating. A graph that references a 3D LUT whose samples could not be loaded is
     * refused **before** either renderer runs, so a look-changing gap is never committed.
     *
     * ## CPU fallback concurrency
     * CPU renders run through [PreviewCpuGate]: one RAW and one JPEG at a time, and a RAW render
     * additionally waits for a running export bake. The gate is entered only after the lease, which
     * keeps the order `lease → cpu gate → rawBakeMutex` acyclic.
     *
     * @return The staged JPEG, or `null` when the source cannot be decoded or written.
     */
    suspend fun renderToDir(
        context: Context,
        filesDir: File,
        outDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        subjectMaskOverride: SubjectMask? = null,
        skyMaskOverride: SkyMask? = null
    ): File? {
        if (sourcePath.isBlank() || graph.isIdentity()) return null
        if (!outDir.isDirectory && !outDir.mkdirs()) {
            Timber.e("Background preview staging directory could not be created")
            return null
        }
        // A cancelled coroutine makes `withContext` discard the value its block produced, so the file
        // this render just wrote would be lost — delete it instead of leaving an orphan next to the
        // real cache.
        val produced = AtomicReference<File?>(null)
        return try {
            withContext(Dispatchers.IO) {
                val rendered = renderInDir(
                    context, filesDir, outDir, sourcePath, isRaw, graph,
                    subjectMaskOverride, skyMaskOverride
                )
                produced.set(rendered)
                rendered
            }
        } catch (cancelled: CancellationException) {
            produced.get()?.delete()
            throw cancelled
        }
    }

    private suspend fun renderInDir(
        context: Context,
        filesDir: File,
        outDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        subjectMaskOverride: SubjectMask?,
        skyMaskOverride: SkyMask?
    ): File? {
        val startedAt = SystemClock.elapsedRealtime()
        var acquireMs = 0L
        var prepareMs = 0L
        var gpuMs = 0L
        var cpuMs = 0L
        var route = ROUTE_NONE
        try {
            val acquireStartedAt = SystemClock.elapsedRealtime()
            val lease = RecentEditorSourceCache.shared.acquire(
                sourcePath = sourcePath,
                isRaw = isRaw,
                preferLinear = true
            )
            acquireMs = SystemClock.elapsedRealtime() - acquireStartedAt
            if (lease == null) {
                Timber.w("Background preview source could not be decoded")
                return null
            }
            // The lease carries the exact file identity the cache read before decoding. The portrait
            // analysis below re-reads the same file, so a replacement in between would pair a new
            // mask with old pixels; the key, not a fresh stat, is what makes that detectable.
            val version = lease.key
            if (version == null) {
                lease.close()
                Timber.w("Background preview: the leased source has no file identity; abandoning")
                return null
            }
            // Preparation owns the lease until the GPU dispatch takes it over.
            val prepStartedAt = SystemClock.elapsedRealtime()
            val request = PreviewFailurePolicy.prepareOrRelease(lease) {
                buildRequest(
                    context, filesDir, outDir, sourcePath, isRaw, graph, lease.source,
                    subjectMaskOverride, skyMaskOverride
                )
            }
            prepareMs = SystemClock.elapsedRealtime() - prepStartedAt
            if (request == null) return null
            if (!PreviewGraphAssets.isComplete(graph, request.lut)) {
                // Publishing without the LUT would commit a preview that shows a different look.
                Timber.e("The referenced 3D LUT is unavailable; keeping the previous preview")
                lease.close()
                return null
            }
            if (sourceChanged(version)) {
                lease.close()
                return null
            }

            val gpuStartedAt = SystemClock.elapsedRealtime()
            val accelerated = OffscreenPreviewRenderer.render(request, lease)
            gpuMs = SystemClock.elapsedRealtime() - gpuStartedAt
            if (accelerated != null) {
                if (sourceChanged(version)) {
                    accelerated.delete()
                    route = ROUTE_DISCARDED
                    return null
                }
                route = ROUTE_GPU
                return publishUnlessCancelled(accelerated)
            }

            // The job owns — and has already closed — the first lease, so the CPU path needs its own
            // reference. Its key must prove it decoded the same version we analysed and are about to
            // publish.
            route = ROUTE_CPU
            val cpuLease = RecentEditorSourceCache.shared.acquire(
                sourcePath = sourcePath,
                isRaw = isRaw,
                preferLinear = true
            )
            if (cpuLease == null) {
                Timber.w("Background preview source could not be re-leased for the CPU renderer")
                return null
            }
            return try {
                if (cpuLease.key != version) {
                    Timber.w("Background preview: the source changed before the CPU fallback")
                    return null
                }
                val cpuStartedAt = SystemClock.elapsedRealtime()
                // Bounded: one CPU preview at a time, and a RAW preview yields to a running bake.
                val rendered = PreviewCpuGate.render(isRaw) {
                    renderOnCpu(outDir, request, cpuLease.source)
                }
                cpuMs = SystemClock.elapsedRealtime() - cpuStartedAt
                if (rendered != null && sourceChanged(version)) {
                    rendered.delete()
                    route = ROUTE_DISCARDED
                    return null
                }
                rendered?.let { publishUnlessCancelled(it) }
            } finally {
                cpuLease.close()
            }
        } finally {
            Timber.i(
                "Background preview timing: acquire=%dms prepare=%dms gpu=%dms cpu=%dms total=%dms " +
                    "route=%s",
                acquireMs,
                prepareMs,
                gpuMs,
                cpuMs,
                SystemClock.elapsedRealtime() - startedAt,
                route
            )
        }
    }

    /**
     * Warms the portrait analysis for [sourcePath] without rendering a preview.
     *
     * Called when the preset strip opens: the source is leased and left in
     * [RecentEditorSourceCache] so the render triggered by the preset tap hits the cache, and the
     * MediaPipe pass is stored in [PortraitPreviewCache] for that render to reuse.
     *
     * @return True when an analysis is available; a failure is a normal cache miss, not an error.
     */
    suspend fun prewarmPortrait(
        context: Context,
        filesDir: File,
        sourcePath: String,
        isRaw: Boolean
    ): Boolean {
        if (sourcePath.isBlank()) return false
        return try {
            withContext(Dispatchers.IO) {
                val lease = RecentEditorSourceCache.shared.acquire(
                    sourcePath = sourcePath,
                    isRaw = isRaw,
                    preferLinear = true
                ) ?: return@withContext false
                try {
                    BakePortraitSupport.prewarmPreview(
                        context = context,
                        filesDir = filesDir,
                        sourcePath = sourcePath,
                        isRaw = isRaw,
                        source = lease.source
                    )
                } finally {
                    lease.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            Timber.w(error, "Portrait prewarm failed; the preset tap will analyse on demand")
            false
        }
    }

    /**
     * Stops the offscreen GPU worker and completes queued renders with `null`.
     *
     * The worker also exits on its own after an idle period, so this is the explicit teardown hook
     * for `Application.onTerminate`; a later render lazily starts a new worker.
     */
    fun shutdown() {
        OffscreenPreviewRenderer.shutdown()
    }

    /**
     * Releases the offscreen GPU worker and the cached portrait analyses on a low-memory signal.
     *
     * Nothing GPU-side outlives a single render — the EGL context, the FBOs and the source textures
     * are created and destroyed per preview — so the GPU side only stops the worker thread and
     * completes queued renders with `null`; their callers fall back to the CPU writer. The portrait
     * cache drops its inference snapshots through [PortraitPreviewCache.clear]. Both recover lazily
     * on the next preview, and the GPU path stays enabled.
     */
    fun onMemoryPressure() {
        OffscreenPreviewRenderer.onMemoryPressure()
        PortraitPreviewCache.clear()
        BakePortraitSupport.releasePreviewEngine()
    }

    /**
     * Deletes a freshly written file when the caller was cancelled meanwhile.
     *
     * The render is a blocking GPU/CPU pass and cannot observe cancellation itself; publishing after
     * the user backed out is not allowed, so the new file (uniquely named, never a previously
     * published one) is removed and the cancellation propagates to the caller's commit gate.
     */
    private suspend fun publishUnlessCancelled(rendered: File): File {
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
            rendered.delete()
            throw CancellationException("Background preview render was cancelled")
        }
        return rendered
    }

    /**
     * True when [sourcePath] no longer matches the identity [key] was created from.
     *
     * A changed file means the pixels, the portrait analysis and the published cache would describe
     * different versions, so the caller abandons the output instead of pairing them.
     */
    private fun sourceChanged(key: EditorSourceKey): Boolean {
        val file = File(key.sourcePath)
        val unchanged = file.isFile &&
            file.length() == key.fileSizeBytes &&
            file.lastModified() == key.lastModifiedMillis
        if (!unchanged) {
            Timber.w(
                "Background preview abandoned: the source file changed from the version it was " +
                    "decoded from"
            )
        }
        return !unchanged
    }

    /** Resolves the portrait artefacts and the LUT once, so the GPU and CPU paths share them. */
    private suspend fun buildRequest(
        context: Context,
        filesDir: File,
        outDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        source: EditorSource,
        subjectMaskOverride: SubjectMask?,
        skyMaskOverride: SkyMask?
    ): OffscreenPreviewRequest {
        val portrait = resolvePortrait(context, filesDir, sourcePath, isRaw, graph, source)
        val inpaint = InpaintOverlay.of(filesDir, graph)
        val subject = subjectMaskOverride?.let { SubjectCompositeLoader.fromSource(source, it) }
            ?: SubjectCompositeLoader.resolve(graph, source, filesDir, inpaint)
        val (outWidth, outHeight) = OffscreenPreviewFrames.outputSize(graph, source.width, source.height)
        val sky = if (graph.sky.isIdentity()) {
            null
        } else {
            SkyPreparer.prepare(
                source = source,
                graph = graph,
                filesDir = filesDir,
                outputWidth = outWidth,
                outputHeight = outHeight,
                maskOverride = skyMaskOverride
            )
        }
        return OffscreenPreviewRequest(
            source = source,
            graph = graph,
            warpField = portrait.warpField,
            skinMask = portrait.skinMask,
            lut = loadLut(filesDir, graph),
            outDir = outDir,
            subject = subject,
            inpaint = inpaint,
            cloudAssets = AiAssetStore(filesDir),
            sky = sky
        )
    }

    /** Runs the CPU reference renderer; every expected failure is logged and yields `null`. */
    private fun renderOnCpu(
        outDir: File,
        request: OffscreenPreviewRequest,
        source: EditorSource
    ): File? = try {
        val written = when (source) {
            is EditorSource.LinearRawSource -> PreviewCacheWriter.writeLinearPreviewTo(
                source = source.full,
                graph = request.graph,
                outDir = outDir,
                warpField = request.warpField,
                lut = request.lut,
                skinMask = request.skinMask,
                subject = request.subject,
                inpaint = request.inpaint,
                cloudAssets = request.cloudAssets,
                sky = request.sky
            )
            is EditorSource.BitmapSource -> PreviewCacheWriter.writeBitmapPreviewTo(
                source = source.full,
                graph = request.graph,
                outDir = outDir,
                warpField = request.warpField,
                lut = request.lut,
                skinMask = request.skinMask,
                subject = request.subject,
                inpaint = request.inpaint,
                cloudAssets = request.cloudAssets,
                sky = request.sky
            )
        }
        written?.let(::File)
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "OOM while rendering background preview")
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        Timber.e(io, "I/O failed while rendering background preview")
        null
    } catch (error: RuntimeException) {
        Timber.e(error, "Background preview render failed")
        null
    }

    /**
     * Resolves the portrait warp and skin mask for a background preview.
     *
     * Uses [BakePortraitSupport.resolvePreview], which caches the graph-free MediaPipe inference per
     * source file and recomputes the face/body meshes from [graph] on every call.
     *
     * A RAW analysis decodes its own 512px linear buffer, so only that stage holds
     * [BakeConcurrencyGate.rawBakeMutex] — never the source lease, which `acquire` gates itself.
     * Cancellation propagates out of the mutex wait and out of the analysis cache.
     */
    private suspend fun resolvePortrait(
        context: Context,
        filesDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        source: EditorSource
    ): BakePortraitSupport.ResolvedPortrait {
        val liquifyMesh = loadLiquifyMesh(filesDir, graph.geometry.liquify)
        if (graph.portrait.isIdentity()) {
            return BakePortraitSupport.ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
        }
        val resolve: suspend () -> BakePortraitSupport.ResolvedPortrait = {
            BakePortraitSupport.resolvePreview(
                context = context,
                filesDir = filesDir,
                sourcePath = sourcePath,
                isRaw = isRaw,
                graph = graph,
                liquifyMesh = liquifyMesh,
                source = source
            )
        }
        return if (isRaw) {
            BakeConcurrencyGate.rawBakeMutex.withLock { resolve() }
        } else {
            resolve()
        }
    }

    private fun loadLiquifyMesh(filesDir: File, reference: LiquifyRef?): LiquifyMesh? =
        reference?.let { LiquifyMeshStore(filesDir).read(it) }

    private fun loadLut(filesDir: File, graph: EditGraph): Lut3dSamples? {
        val reference = graph.color.lut ?: return null
        val loaded = CubeLutStore(filesDir).loadSamples(reference.id)
        if (loaded.isFailure) {
            Timber.w(loaded.exceptionOrNull(), "3D LUT %s unavailable for background preview", reference.id)
        }
        return loaded.getOrNull()
    }
}
