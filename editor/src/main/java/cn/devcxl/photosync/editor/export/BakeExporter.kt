package cn.devcxl.photosync.editor.export

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

import android.app.ActivityManager
import android.content.Context

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.GallerySink
import cn.devcxl.photosync.editor.domain.ActivePhotoEditRegistry
import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.DeviceGlBudget
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditValidator
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.SourceDimensions
import cn.devcxl.photosync.editor.domain.SourceResidencyPlan
import cn.devcxl.photosync.editor.domain.SourceResidencyPlanner
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.render.InpaintOverlay
import cn.devcxl.photosync.editor.render.SubjectCompositeLoader
import cn.devcxl.photosync.editor.render.SubjectRenderContract
import cn.devcxl.photosync.editor.render.SubjectRenderPlan

/**
 * Process-wide concurrency gate for the full-resolution bake pipeline.
 *
 * A RAW bake holds a decoded `RGB16` source plus band FBOs; two of them at once would double the
 * peak memory and can push a 50MP export past the 512MB budget. JPEG bakes are independent and are
 * not serialized.
 */
object BakeConcurrencyGate {
    /** Serializes RAW bakes; JPEG bakes never acquire it. */
    val rawBakeMutex = Mutex()
}

/**
 * Full-resolution non-destructive bake: 1024px Pre-pass, three-tier source residency, column-band
 * streaming render and either a `libjpeg-turbo` baseline encode or a lossless RGBA8 PNG encode
 * written straight into a [GallerySink].
 *
 * The GPU work runs on a headless [SurfacelessEglContext] so a bake never needs a visible window and
 * can run while the user keeps browsing. Peak memory stays inside 512MB because only one row strip
 * (`cropWidth x 512`) and one band FBO (`<= 2048 x 640`) exist at a time; the PNG path keeps the
 * encoder itself at `O(image width)`.
 */
class BakeExporter(
    private val context: Context,
    private val gallerySink: GallerySink,
    private val filesDir: File = context.filesDir
) {

    /**
     * Bakes [request] into the system gallery.
     *
     * Cancellation is observed once per column band (and once per PNG row); a cancelled bake aborts
     * the encoder and lets the sink delete its pending entry. RAW requests additionally wait for
     * [BakeConcurrencyGate.rawBakeMutex] so at most one RAW bake occupies memory at a time.
     *
     * When [BakeRequest.photoId] is set, the id stays registered in
     * [cn.devcxl.photosync.editor.domain.ActivePhotoEditRegistry] for the whole bake, so the offline
     * AI asset sweep skips the photo directory this bake is still reading.
     */
    suspend fun bake(request: BakeRequest): BakeResult {
        val photoId = request.photoId
        if (photoId != null) ActivePhotoEditRegistry.acquire(photoId)
        try {
            if (!request.isRaw) return runBake(request)
            return BakeConcurrencyGate.rawBakeMutex.withLock { runBake(request) }
        } finally {
            if (photoId != null) ActivePhotoEditRegistry.release(photoId)
        }
    }

    private suspend fun runBake(request: BakeRequest): BakeResult = withContext(Dispatchers.Default) {
        val coroutine = currentCoroutineContext()
        try {
            bakeBlocking(request, coroutine)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "Bake ran out of memory")
            BakeResult.Failure("out of memory", oom)
        } catch (io: IOException) {
            Timber.e(io, "Bake I/O failed")
            BakeResult.Failure("export failed", io)
        } catch (error: RuntimeException) {
            Timber.e(error, "Bake failed")
            BakeResult.Failure("export failed", error)
        }
    }

    private fun bakeBlocking(request: BakeRequest, coroutine: CoroutineContext): BakeResult {
        if (!File(request.sourcePath).isFile) {
            return BakeResult.Failure("source file missing")
        }
        val dimensions = BakeSourceProbe.dimensions(request.sourcePath, request.isRaw)
            ?: return BakeResult.Failure("source could not be decoded")
        val egl = SurfacelessEglContext.create()
            ?: return BakeResult.Failure("no offscreen GL context")
        try {
            val budget = DeviceGlBudget(
                glMaxTextureSize = egl.glMaxTextureSize,
                totalRamBytes = totalRamBytes()
            )
            val plan = SourceResidencyPlanner.plan(
                SourceDimensions(dimensions.first, dimensions.second),
                budget
            )
            Timber.i("Bake residency=%s %dx%d maxTex=%d", plan.tier, dimensions.first,
                dimensions.second, egl.glMaxTextureSize)
            val chain = BakePassChain()
            if (!chain.init()) {
                return BakeResult.Failure("this GPU cannot render the full export chain")
            }
            try {
                val sourceLongEdge = maxOf(dimensions.first, dimensions.second)
                return renderAndPublish(request, chain, plan, sourceLongEdge, coroutine)
            } finally {
                chain.close()
            }
        } finally {
            egl.close()
        }
    }

    private fun renderAndPublish(
        request: BakeRequest,
        chain: BakePassChain,
        plan: SourceResidencyPlan,
        sourceLongEdge: Int,
        coroutine: CoroutineContext
    ): BakeResult {
        val graph = EditValidator.sanitize(request.graph)
        val source = BakeSourceTextures.open(
            sourcePath = request.sourcePath,
            isRaw = request.isRaw,
            plan = plan,
            sourceLongEdge = sourceLongEdge,
            inpaint = InpaintOverlay.of(filesDir, graph)
        ) ?: return BakeResult.Failure("source textures could not be created")
        val prepass = BakePrepass(chain, source)
        try {
            val outSize = ColumnBandTiling.outputSize(source.width, source.height, graph.geometry)
            val portrait = BakePortraitSupport.resolve(
                context = context,
                filesDir = filesDir,
                sourcePath = request.sourcePath,
                isRaw = request.isRaw,
                graph = graph,
                liquifyMesh = loadLiquifyMesh(graph)
            )
            chain.stageGraph(
                warpField = portrait.warpField,
                skinMask = portrait.skinMask,
                graphCurve = graph.color.curve,
                lut = loadLut(graph)
            )
            val maxWarpUv = maxAbsWarpUv(portrait.warpField)
            val frame = BakeFrameFactory.create(graph, source, outSize.first, outSize.second, maxWarpUv)
            val subject = resolveSubject(request, graph, portrait.warpField, source)
                ?: return BakeResult.Failure("subject mask unavailable")
            chain.setSubject(subject.support, subject.plan)
            prepass.prepare(frame, outSize.first, outSize.second)
            return publish(request, graph, chain, source, frame, outSize, coroutine)
        } finally {
            prepass.close()
            source.close()
        }
    }

    private fun publish(
        request: BakeRequest,
        graph: EditGraph,
        chain: BakePassChain,
        source: BakeSourceTextures,
        frame: BakeFrame,
        outSize: Pair<Int, Int>,
        coroutine: CoroutineContext
    ): BakeResult {
        var encoded = false
        val (outWidth, outHeight) = outSize
        val isActive = { coroutine.isActive }
        val format = request.outputFormat
        val uri = gallerySink.save(
            displayName = request.displayName,
            mime = BakeOutputNaming.mimeType(format),
            write = { output ->
                BakeStripWriter.write(
                    output = output,
                    request = request,
                    graph = graph,
                    chain = chain,
                    source = source,
                    frame = frame,
                    outWidth = outWidth,
                    outHeight = outHeight,
                    isActive = isActive
                )
                encoded = true
            },
            writeExif = if (format == BakeOutputFormat.JPEG) {
                { writer ->
                    BakeExifWriter.apply(
                        sourcePath = request.sourcePath,
                        isRaw = request.isRaw,
                        width = outWidth,
                        height = outHeight,
                        target = writer
                    )
                }
            } else {
                // The PNG path embeds its eXIf chunk during the stream; rewriting the gallery
                // entry afterwards would copy the whole file through a FUSE descriptor.
                null
            }
        )
        if (!isActive()) {
            throw CancellationException("Bake cancelled before publishing")
        }
        if (!encoded) {
            return BakeResult.Failure("gallery write failed")
        }
        return BakeResult.Success(uri, outWidth, outHeight, request.displayName)
    }

    /**
     * Resolves the bake's subject support and Pass D plan.
     *
     * @return `null` only when the graph asks for a cut-out but its mask cannot be loaded; an
     *   inactive subject returns a pair of `null`s so the normal bake proceeds unchanged.
     */
    private fun resolveSubject(
        request: BakeRequest,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        source: BakeSourceTextures
    ): ResolvedBakeSubject? {
        if (graph.subject.bgMode == SubjectBackgroundMode.ORIGINAL) {
            return ResolvedBakeSubject(null, null)
        }
        val mask = SubjectCompositeLoader.loadMask(filesDir, graph) ?: return null
        val initial = SubjectCompositeLoader.resolveForFile(
            graph = graph,
            sourcePath = request.sourcePath,
            isRaw = request.isRaw,
            filesDir = filesDir
        )
        val plan = SubjectRenderContract.plan(graph, hasMask = true)
            ?: return ResolvedBakeSubject(null, null)
        return ResolvedBakeSubject(
            support = BakeSubjectSupport(
                graph = graph,
                mask = mask,
                initial = initial,
                warpField = warpField,
                inverseMatrix = BakeFrameFactory.inverseMatrix(graph, source)
            ),
            plan = plan
        )
    }

    private data class ResolvedBakeSubject(
        val support: BakeSubjectSupport?,
        val plan: SubjectRenderPlan?
    )

    private fun maxAbsWarpUv(field: WarpDisplacementField?): Float {
        if (field == null || field.isZero()) return 0.0f
        var maxDisp = 0.0f
        val count = field.gridSize * field.gridSize * 2
        for (i in 0 until count) {
            val v = kotlin.math.abs(field.displacementRaw(i))
            if (v > maxDisp) maxDisp = v
        }
        return maxDisp
    }

    private fun loadLiquifyMesh(graph: EditGraph): LiquifyMesh? {
        val reference = graph.geometry.liquify ?: return null
        return LiquifyMeshStore(filesDir).read(reference)
    }

    private fun loadLut(graph: EditGraph): Lut3dSamples? {
        val reference = graph.color.lut ?: return null
        return CubeLutStore(filesDir).loadSamples(reference.id).getOrNull()
    }

    private fun totalRamBytes(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        return info.totalMem
    }
}
