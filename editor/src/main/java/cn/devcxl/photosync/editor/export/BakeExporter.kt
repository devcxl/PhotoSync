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
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.GallerySink
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
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.wrapper.JpegStreamWriter

/**
 * Full-resolution non-destructive bake: 1024px Pre-pass, three-tier source residency, column-band
 * streaming render and a `libjpeg-turbo` baseline encode written straight into a [GallerySink].
 *
 * The GPU work runs on a headless [SurfacelessEglContext] so a bake never needs a visible window and
 * can run while the user keeps browsing. Peak memory stays inside 512MB because only one row strip
 * (`cropWidth x 512`) and one band FBO (`<= 2048 x 640`) exist at a time.
 */
class BakeExporter(
    private val context: Context,
    private val gallerySink: GallerySink,
    private val filesDir: File = context.filesDir
) {

    /**
     * Bakes [request] into the system gallery.
     *
     * Cancellation is observed once per column band; a cancelled bake aborts the encoder and lets
     * the sink delete its pending entry.
     */
    suspend fun bake(request: BakeRequest): BakeResult = withContext(Dispatchers.Default) {
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
            sourceLongEdge = sourceLongEdge
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
            val frame = BakeFrameFactory.create(graph, source, outSize.first, outSize.second)
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
        val uri = gallerySink.save(
            displayName = request.displayName,
            mime = GallerySink.JPEG_MIME,
            write = { output ->
                JpegStreamWriter(output, outWidth, outHeight).use { jpeg ->
                    BakeBandEncoder(
                        chain = chain,
                        source = source,
                        sink = JpegStripSink(jpeg),
                        frameTemplate = frame,
                        sourceToPostCropInverse = BakeFrameFactory.inverseMatrix(graph, source),
                        cropWidth = outWidth,
                        cropHeight = outHeight
                    ).encode(isActive)
                    jpeg.finish()
                }
                encoded = true
            },
            writeExif = { writer ->
                BakeExifWriter.apply(
                    sourcePath = request.sourcePath,
                    isRaw = request.isRaw,
                    width = outWidth,
                    height = outHeight,
                    target = writer
                )
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
