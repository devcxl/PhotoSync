package cn.devcxl.photosync.editor.export

import java.io.File

import android.content.Context
import android.graphics.Bitmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.domain.PortraitWarpComposer
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.render.EditorBitmapLoader
import cn.devcxl.photosync.editor.vision.MediaPipeDelegatePolicy
import cn.devcxl.photosync.editor.vision.MediaPipePortraitEngine
import cn.devcxl.photosync.editor.vision.ModelDownloadManager
import cn.devcxl.photosync.editor.vision.PortraitAnalysisEnvironment
import cn.devcxl.photosync.editor.vision.PortraitAnalysisKey
import cn.devcxl.photosync.editor.vision.PortraitAnalysisSnapshot
import cn.devcxl.photosync.editor.vision.PortraitAnalyzer
import cn.devcxl.photosync.editor.vision.PortraitModelIdentity
import cn.devcxl.photosync.editor.vision.PortraitPreviewCache
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Resolves the unified Pass A warp (`face + body + liquify`) and the Pass C `skinMask` for a graph
 * that carries portrait adjustments.
 *
 * Two entry points share the same analysis maths but different caching policies:
 * - [resolve] is the full-resolution [BakeExporter] path and never touches shared state.
 * - [resolvePreview] serves the main-screen background preview and caches the graph-free
 *   [PortraitAnalysisSnapshot] per source file (see [PortraitPreviewCache]).
 *
 * Both return `(liquifyMesh, null)` immediately when `graph.portrait.isIdentity()`; neither caches a
 * warp, because the warp is a function of the graph and the liquify mesh.
 */
internal object BakePortraitSupport {

    private const val ANALYSIS_MAX_EDGE_PX = 512
    private const val MODEL_DIRECTORY = "models"

    /**
     * Fingerprint of the proxy decode plus segmentation pipeline. Bump it when the analysis
     * resolution, the bundled assets or the mask post-processing change, so entries computed by an
     * older pipeline can never be reused.
     */
    private const val PREVIEW_ANALYSIS_PROFILE = "portrait-preview-analysis-v1"

    data class ResolvedPortrait(
        val warpField: WarpDisplacementField?,
        val skinMask: FloatArray?
    )

    fun resolve(
        context: Context,
        filesDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        liquifyMesh: LiquifyMesh?
    ): ResolvedPortrait {
        if (graph.portrait.isIdentity()) {
            return ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
        }
        val bitmap = decodeAnalysisBitmap(sourcePath, isRaw)
            ?: return ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
        val engine = MediaPipePortraitEngine(context, MediaPipeDelegatePolicy(context))
        try {
            val downloadedModel = modelFile(filesDir)
            if (downloadedModel.isFile) {
                engine.upgradeToMulticlass(downloadedModel)
            }
            val analysis = PortraitAnalyzer(engine).analyze(bitmap, graph.portrait.body)
                ?: return ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
            return composeResolvedPortrait(analysis, graph, liquifyMesh)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            engine.release()
        }
    }

    /**
     * Resolves the same portrait artefacts as [resolve] for the main-screen background preview,
     * reusing the per-source-file analysis cache.
     *
     * Only the graph-free inference output (faces, `skinMask`, `personMask`, model flavour) is
     * cached; the face / body meshes and their composition with [liquifyMesh] are recomputed from
     * [graph] on every call, so a slider drag or a liquify stroke is never served from the cache.
     *
     * Entries are invalidated by the source file identity (path, size, mtime, RAW flag), the
     * downloaded-model identity and the analysis environment (delegate, proxy size, pipeline
     * profile); a failed analysis is not stored, so the next preview retries, and a source without a
     * stable identity (missing or empty file) yields the liquify-only fallback without decoding.
     * When the photo, the model or the environment changed *while* the pass ran, the cache raises
     * [cn.devcxl.photosync.editor.vision.PortraitIdentityDriftException] instead of returning that
     * pass: the caller abandons the render (keeping the previously published preview) and retries
     * against the new bytes, because publishing the stale mask would edit a different frame. The
     * exception carries only which part moved, never a path.
     *
     * The cache serializes callers, so at most one preview analysis is alive at a time; a caller
     * cancelled while waiting leaves immediately, and one cancelled during the pass does not publish
     * its result. The blocking decode and MediaPipe pass run on [Dispatchers.IO], which this function
     * switches to before entering the cache. See [PortraitPreviewCache] for the memory-pressure hook.
     *
     * @throws cn.devcxl.photosync.editor.vision.PortraitIdentityDriftException when the source file,
     *   the downloaded model or the analysis environment changed during the pass; recoverable by
     *   retrying, and deliberately free of path or hash details.
     */
    suspend fun resolvePreview(
        context: Context,
        filesDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        liquifyMesh: LiquifyMesh?
    ): ResolvedPortrait {
        if (graph.portrait.isIdentity()) {
            return ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
        }
        val modelFile = modelFile(filesDir)
        val snapshot = withContext(Dispatchers.IO) {
            PortraitPreviewCache.analysis(
                // Derived under the analysis mutex right before the pass, and again right after it:
                // a file replaced or a model that finished downloading in between makes the result
                // unstable, and the cache must not keep it.
                keyOf = { previewAnalysisKey(context, sourcePath, isRaw, modelFile(filesDir)) },
                produce = { analyzeForPreview(context, modelFile, sourcePath, isRaw) }
            )
        } ?: return ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
        return composeResolvedPortrait(snapshot.toAnalysis(graph.portrait.body), graph, liquifyMesh)
    }

    /**
     * Builds the cache key from everything that can change the inference output, or `null` when the
     * source has no stable identity (missing file) and therefore must not be cached.
     */
    private fun previewAnalysisKey(
        context: Context,
        sourcePath: String,
        isRaw: Boolean,
        modelFile: File
    ): PortraitAnalysisKey? {
        if (sourcePath.isBlank()) return null
        val source = File(sourcePath)
        if (!source.isFile) return null
        val size = source.length()
        if (size <= 0L) return null
        return PortraitAnalysisKey(
            sourcePath = sourcePath,
            sourceSizeBytes = size,
            sourceLastModifiedMillis = source.lastModified(),
            isRaw = isRaw,
            model = PortraitModelIdentity.of(modelFile),
            environment = PortraitAnalysisEnvironment(
                analysisMaxEdgePx = ANALYSIS_MAX_EDGE_PX,
                delegate = MediaPipeDelegatePolicy(context).preferredDelegate().name,
                pipelineProfile = PREVIEW_ANALYSIS_PROFILE
            )
        )
    }

    /**
     * Runs the expensive pass for a cache miss.
     *
     * [modelFile] is re-checked here on purpose: a download that finishes between the key snapshot
     * and this pass is used immediately, and the identity check the cache runs right after the pass
     * then sees the new model and refuses to store the result — never a wrong entry.
     */
    private fun analyzeForPreview(
        context: Context,
        modelFile: File,
        sourcePath: String,
        isRaw: Boolean
    ): PortraitAnalysisSnapshot? {
        val bitmap = decodeAnalysisBitmap(sourcePath, isRaw) ?: return null
        val engine = MediaPipePortraitEngine(context, MediaPipeDelegatePolicy(context))
        try {
            if (modelFile.isFile) {
                engine.upgradeToMulticlass(modelFile)
            }
            // Guides are re-derived per call from the cached masks, so the cached pass itself only
            // needs the neutral body values.
            val analysis = PortraitAnalyzer(engine).analyze(bitmap) ?: return null
            return PortraitAnalysisSnapshot.from(analysis)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            engine.release()
        }
    }

    /** Resolves the optional downloaded model path without triggering a download. */
    private fun modelFile(filesDir: File): File =
        ModelDownloadManager(File(filesDir, MODEL_DIRECTORY)).modelFile()

    /**
     * Turns one graph-free [analysis] into the artefacts [graph] asks for.
     *
     * Every warp is rebuilt here on purpose: face meshes, body meshes and the liquify composition
     * are pure functions of the graph, and caching them would serve stale geometry. Internal rather
     * than private so the preview-resolution unit test exercises this exact composition.
     */
    internal fun composeResolvedPortrait(
        analysis: PortraitAnalysis,
        graph: EditGraph,
        liquifyMesh: LiquifyMesh?
    ): ResolvedPortrait {
        val faceWarp = if (graph.portrait.reshape.isIdentity()) {
            null
        } else {
            analysis.faceWarp(graph.portrait.reshape)
        }
        val bodyWarp = if (graph.portrait.body.isIdentity()) {
            null
        } else {
            analysis.bodyWarp(graph.portrait.body)
        }
        val composedWarp = PortraitWarpComposer.compose(faceWarp, bodyWarp, liquifyMesh)
        val skinMask = if (
            graph.portrait.skin.smooth != 0 ||
            graph.portrait.skin.evenTone != 0 ||
            graph.detail.sharpen.amount != 0
        ) {
            analysis.skinMask
        } else {
            null
        }
        return ResolvedPortrait(warpField = composedWarp, skinMask = skinMask)
    }

    private fun decodeAnalysisBitmap(sourcePath: String, isRaw: Boolean): Bitmap? {
        if (isRaw) {
            val linear = RawWrapper.decodeToLinearRgba16F(sourcePath, ANALYSIS_MAX_EDGE_PX)
            if (linear != null) {
                return Bitmap.createBitmap(
                    LinearRawPreviewRenderer.renderArgb(
                        source = linear,
                        graph = EditGraph(),
                        fullResolutionShortEdge = ANALYSIS_MAX_EDGE_PX
                    ),
                    linear.width,
                    linear.height,
                    Bitmap.Config.ARGB_8888
                )
            }
            return RawWrapper.decodeThumbnailBitmap(sourcePath, ANALYSIS_MAX_EDGE_PX)
        }
        val loaded = EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw = false)
            ?: return null
        val shortEdge = minOf(loaded.width, loaded.height).coerceAtLeast(1)
        val scale = (ANALYSIS_MAX_EDGE_PX.toFloat() / shortEdge).coerceAtMost(1.0f)
        val w = (loaded.width * scale).toInt().coerceAtLeast(1)
        val h = (loaded.height * scale).toInt().coerceAtLeast(1)
        if (w == loaded.width && h == loaded.height) return loaded
        val scaled = Bitmap.createScaledBitmap(loaded, w, h, true)
        if (scaled !== loaded) loaded.recycle()
        return scaled
    }
}
