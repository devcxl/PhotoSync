package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException

import android.graphics.Bitmap

import timber.log.Timber

import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.SkinMaskMath
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * One headless run of the physical `Pass A -> Pass B -> Pass C -> Pass D` preview chain.
 *
 * Derives the frame geometry and uniforms from [OffscreenPreviewRequest] and hands them to
 * [OffscreenPreviewPasses], which runs the same shader programs as the canvas and the export bake.
 * Sharing programs is not proof of identical pixels: every stage is validated by its own result, and
 * the GPU output is only published when the whole graph could be reproduced.
 *
 * ## Contract
 * - Runs on one thread inside a headless
 *   [cn.devcxl.photosync.editor.export.SurfacelessEglContext].
 * - Renders into an FBO of at most [OffscreenPreviewFrames.MAX_EDGE_PX] and reads it back top-down,
 *   then publishes one uniquely named JPEG through [PreviewFileWriter]. An existing preview is
 *   never deleted or overwritten.
 * - Returns `null` — never a partially processed frame — when any part of the requested graph cannot
 *   be reproduced: a failed shader, an un-uploadable source/warp/skin/curve/LUT texture, a referenced
 *   3D LUT without samples, a partially executed Pass C, a missing Pre-pass, an incomplete target, a
 *   read-back error, or a cancelled/lost context.
 *
 * A portrait warp or skin mask that the caller could not produce at all (`null`) is rendered exactly
 * as the CPU path renders it — both simply omit that stage — while a *referenced* 3D LUT without
 * samples is refused, because a LUT-less frame is a different look rather than a lower-precision one.
 *
 * Single use: create one per render and [close] it on the same thread, because every GL object
 * belongs to the caller's EGL context.
 */
internal class OffscreenPreviewChain(
    private val uploader: PreviewSourceUploader = PreviewSourceUploader()
) : AutoCloseable {

    private val passes = OffscreenPreviewPasses()

    /**
     * Compiles every program and probes the driver on the current thread.
     *
     * @param maxTextureSize `GL_MAX_TEXTURE_SIZE` of the current context.
     * @return True only when the complete Pass A/B/C/D chain can run here.
     */
    fun init(maxTextureSize: Int): Boolean = passes.init(maxTextureSize)

    /**
     * Renders [request] and writes one new preview JPEG into its output directory.
     *
     * A requested [OffscreenPreviewRequest.inpaint] overlay is composed into a private copy for the
     * duration of this call, so the leased source the caller handed over is never mutated and the
     * copy is reclaimed before this method returns.
     *
     * @param guard Aborts the render when the caller went away or the context stopped being current.
     * @return The written file, or `null` when this context cannot reproduce the graph exactly.
     */
    fun render(
        request: OffscreenPreviewRequest,
        guard: PreviewRenderGuard = PreviewRenderGuard.NONE
    ): File? {
        val composed = request.inpaint?.let { EditorSourceComposer.compose(request.source, it) }
        return try {
            renderSource(composed ?: request.source, request, guard)
        } finally {
            composed?.release()
        }
    }

    private fun renderSource(
        source: EditorSource,
        request: OffscreenPreviewRequest,
        guard: PreviewRenderGuard
    ): File? {
        val graph = request.graph
        val (outWidth, outHeight) = OffscreenPreviewFrames.outputSize(graph, source.width, source.height)
        if (outWidth <= 0 || outHeight <= 0) {
            previewUnavailable("degenerate preview size")
            return null
        }
        if (maxOf(source.width, source.height) > passes.maxTextureSize) {
            previewUnavailable("the source exceeds GL_MAX_TEXTURE_SIZE")
            return null
        }
        if (!PreviewGraphAssets.isComplete(graph, request.lut)) {
            // Never publish a frame that silently misses a LUT the graph references.
            previewUnavailable("the referenced 3D LUT has no samples")
            return null
        }
        if (!checkpoint(guard)) return null

        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val outputAspect = OffscreenPreviewFrames.outputAspect(graph, source.width, source.height)
        val toneParams = ToneShaderParams.from(graph)
        val colorParams = ColorGradingParams.from(graph)
        val spatialParams = SpatialDetailParams.from(graph)
        val skinMask = request.skinMask
            ?.takeIf { it.size == SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE }
        val aberration = graph.effects.ca
        val caRedK = GeometryMath.lateralCaKFromSlider(aberration.redCyan)
        val caBlueK = GeometryMath.lateralCaKFromSlider(aberration.blueYellow)
        val inverseMatrix = GeometryMath.buildPassAInverseMatrix(graph.geometry, sourceAspect)
            .toColumnMajorFloatArray()
        val linearSource = source.isLinear
        val autoBrightGain = (source as? EditorSource.LinearRawSource)?.autoBrightGain ?: 1.0f

        passes.resetErrorState()
        val fullTexture = uploader.upload(source, fullFrame = true) ?: return null
        var proxyTexture = 0
        try {
            val cloud = loadCloudLayer(graph, request, outWidth, outHeight)
            val staged = passes.stageGraph(
                warpField = request.warpField,
                skinMask = skinMask,
                curve = graph.color.curve,
                lut = request.lut,
                subject = request.subject,
                cloud = cloud
            )
            if (!staged || !checkpoint(guard)) return null

            // The Pre-pass must run before the main frame: it renders a 1024px proxy of the same
            // graph through the shared Pass A target, so doing it afterwards would overwrite the
            // frame that Pass B is about to grade.
            if (spatialParams.needsPrepass) {
                proxyTexture = uploader.upload(source, fullFrame = false) ?: return null
                val analysed = passes.preparePrepass(outputAspect) { target ->
                    passes.drawPassA(
                        width = target.width,
                        height = target.height,
                        sourceTextureId = proxyTexture,
                        sourceAspect = sourceAspect,
                        inverseMatrix = inverseMatrix,
                        caRedK = caRedK,
                        caBlueK = caBlueK,
                        linearSource = linearSource
                    ) && passes.drawPrepassPassB(
                        target = target,
                        linearSource = linearSource,
                        autoBrightGain = autoBrightGain,
                        graph = graph,
                        toneParams = toneParams,
                        colorParams = colorParams,
                        subjectUvMatrix = inverseMatrix
                    )
                }
                if (!analysed || !checkpoint(guard)) return null
            }

            val passA = passes.drawPassA(
                width = outWidth,
                height = outHeight,
                sourceTextureId = fullTexture,
                sourceAspect = sourceAspect,
                inverseMatrix = inverseMatrix,
                caRedK = caRedK,
                caBlueK = caBlueK,
                linearSource = linearSource
            )
            if (!passA || !checkpoint(guard)) return null

            val passB = passes.drawMainPassB(
                width = outWidth,
                height = outHeight,
                linearSource = linearSource,
                autoBrightGain = autoBrightGain,
                graph = graph,
                toneParams = toneParams,
                colorParams = colorParams,
                subjectUvMatrix = inverseMatrix
            )
            if (!passB || !checkpoint(guard)) return null

            val passCTexture = passes.runSpatialDetail(
                width = outWidth,
                height = outHeight,
                spatialParams = spatialParams,
                fullResolutionShortEdge = minOf(source.width, source.height),
                graph = graph,
                subjectUvMatrix = inverseMatrix
            ) ?: return null
            if (!checkpoint(guard)) return null

            val effectsParams = PassDEffectsParams.from(graph, outWidth.toFloat() / outHeight.toFloat())
                .withLutSize(passes.lutSampleSize)
            val subjectPlan = SubjectRenderContract.plan(
                graph = graph,
                hasMask = request.subject != null && passes.isSubjectActive
            )
            val passD = passes.drawPassD(
                width = outWidth,
                height = outHeight,
                inputTextureId = if (passCTexture != 0) passCTexture else passes.passBTextureId,
                effectsParams = effectsParams,
                subjectMode = subjectPlan?.shaderMode ?: 0,
                subjectColorRgb = subjectPlan?.solidColorRgb ?: 0xFFFFFF,
                subjectUvMatrix = inverseMatrix,
                subjectCheckerboard = true
            )
            if (!passD || !checkpoint(guard)) return null

            val argb = passes.readFrame(outWidth, outHeight) ?: return null
            // Last chance to abandon before a published file exists.
            if (!checkpoint(guard)) return null
            return encode(argb, outWidth, outHeight, request.outDir)
        } finally {
            EditorTextureUploader.releaseTexture(fullTexture)
            EditorTextureUploader.releaseTexture(proxyTexture)
        }
    }

    /** Releases every GL object of this render. Must run with the context still current. */
    override fun close() = passes.close()

    /**
     * Decodes and digest-verifies the graph's Post-Pass A cloud layer at the output frame size.
     *
     * Returns `null` — the frame is then published without the layer — when the graph has no layer,
     * the asset store was not supplied, or the digest no longer matches the current framing. The
     * preview therefore never pastes a layer generated for another crop.
     */
    private fun loadCloudLayer(
        graph: EditGraph,
        request: OffscreenPreviewRequest,
        width: Int,
        height: Int
    ): CloudLayerPixels? {
        val layer = graph.cloudLayer ?: return null
        val assets = request.cloudAssets ?: return null
        if (!CloudGeometryDigest.matches(layer, graph.geometry)) {
            Timber.w("Offscreen preview skips the cloud layer: geometry digest mismatch")
            return null
        }
        val blender = CloudLayerBlender(assets)
        return when (val loaded = blender.prepare(layer, graph.geometry, width, height)) {
            is CloudLayerLoad.Ready -> loaded.pixels
            is CloudLayerLoad.NotApplied -> {
                Timber.w("Offscreen preview skips the cloud layer: %s", loaded.status)
                null
            }
        }
    }

    private fun checkpoint(guard: PreviewRenderGuard): Boolean {
        if (guard.isStillValid()) return true
        previewUnavailable("the render was abandoned before publishing")
        return false
    }

    private fun encode(argb: IntArray, width: Int, height: Int, outDir: File): File? {
        val bitmap = try {
            Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "Offscreen preview: OOM while packaging the read-back frame")
            return null
        }
        return try {
            try {
                PreviewFileWriter.write(outDir) { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, stream)
                }?.let(::File)
            } catch (io: IOException) {
                // A full or evicted cache directory is a preview failure, not a crash.
                Timber.e(io, "Offscreen preview: the preview file could not be written")
                null
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val PREVIEW_JPEG_QUALITY = 92
    }
}
