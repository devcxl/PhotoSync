package cn.devcxl.photosync.editor.export

import java.io.File

import android.content.Context
import android.graphics.Bitmap

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.PortraitWarpComposer
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.render.EditorBitmapLoader
import cn.devcxl.photosync.editor.vision.MediaPipeDelegatePolicy
import cn.devcxl.photosync.editor.vision.MediaPipePortraitEngine
import cn.devcxl.photosync.editor.vision.ModelDownloadManager
import cn.devcxl.photosync.editor.vision.PortraitAnalyzer
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Resolves the unified Pass A warp (`face + body + liquify`) and the Pass C `skinMask` for a full-
 * resolution [BakeExporter] export when the graph carries portrait adjustments.
 *
 * When `graph.portrait.isIdentity()`, returns `(liquifyMesh, null)` immediately without spinning up
 * MediaPipe.
 */
internal object BakePortraitSupport {

    private const val ANALYSIS_MAX_EDGE_PX = 512
    private const val MODEL_DIRECTORY = "models"

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
            val downloadedModel = ModelDownloadManager(File(filesDir, MODEL_DIRECTORY)).modelFile()
            if (downloadedModel.isFile) {
                engine.upgradeToMulticlass(downloadedModel)
            }
            val analysis = PortraitAnalyzer(engine).analyze(bitmap, graph.portrait.body)
                ?: return ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
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
            val skinMask = if (graph.portrait.skin.smooth != 0 || graph.detail.sharpen.amount != 0) {
                analysis.skinMask
            } else {
                null
            }
            return ResolvedPortrait(warpField = composedWarp, skinMask = skinMask)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            engine.release()
        }
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
