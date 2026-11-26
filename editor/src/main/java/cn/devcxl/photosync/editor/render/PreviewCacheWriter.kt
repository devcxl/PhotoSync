package cn.devcxl.photosync.editor.render

import java.io.File
import kotlin.math.roundToInt

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.CurveLutBuilder
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LinearCloudLayer
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.SkinMaskMath
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.PassBColorMath
import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.PreviewDetailPass
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.Srgb8Packer
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.ToneShaderMath
import cn.devcxl.photosync.editor.domain.ToneShaderParams
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Renders the screen-sized JPEG preview cache under `cacheDir/edits/<photoId>/` for the main
 * gallery and filmstrip.
 *
 * Applies the full Pass A (crop, rotate, straighten, perspective, liquify, lateral CA) + Pass B
 * (`BaseLook`, D65-anchored white balance, linear tone, display tone) + Pass C (noise reduction,
 * dehaze, clarity/texture, sharpening) + Pass D (3D LUT, grain, post-crop vignette) contract so the
 * cached thumbnail matches the GL canvas.
 *
 * An [InpaintOverlay] is composed into a **private copy** of the decoded source before Pass A, so the
 * leased pixels the caller owns are never mutated and the repaired areas travel through exactly the
 * same geometry and colour stages as the rest of the frame.
 */
internal object PreviewCacheWriter {

    private const val PREVIEW_JPEG_QUALITY = 92
    private const val PREVIEW_MAX_EDGE_PX = 1280
    private const val CHANNELS_RGB = 3

    /** Writes the preview cache for an 8-bit sRGB [source] bitmap. */
    fun writePreviewCache(
        source: Bitmap,
        graph: EditGraph,
        cacheDir: File,
        photoId: Long,
        warpField: WarpDisplacementField? = null,
        lut: Lut3dSamples? = null,
        skinMask: FloatArray? = null,
        subject: SubjectComposite? = null,
        inpaint: InpaintOverlay? = null,
        cloudAssets: AiAssetStore? = null
    ): String? {
        val rendered =
            renderBitmapFrame(source, graph, warpField, lut, skinMask, subject, inpaint, cloudAssets)
            ?: return null
        return compressAndWrite(rendered, cacheDir, photoId)
    }

    /** Writes the preview cache for a 16-bit linear RAW [source] buffer. */
    fun writePreviewCache(
        source: LinearRawBuffer,
        graph: EditGraph,
        cacheDir: File,
        photoId: Long,
        warpField: WarpDisplacementField? = null,
        lut: Lut3dSamples? = null,
        skinMask: FloatArray? = null,
        subject: SubjectComposite? = null,
        inpaint: InpaintOverlay? = null,
        cloudAssets: AiAssetStore? = null
    ): String? {
        val rendered =
            renderLinearFrame(source, graph, warpField, lut, skinMask, subject, inpaint, cloudAssets)
            ?: return null
        return compressAndWrite(rendered, cacheDir, photoId)
    }

    /**
     * Writes the preview of an 8-bit sRGB [source] straight into [outDir], which the caller owns.
     *
     * Used by the `LiveTetherPreset` ingestor to stage a JPEG in a temporary directory before the
     * Room row (and therefore the final `edits/<photoId>/` directory name) exists.
     */
    fun writeBitmapPreviewTo(
        source: Bitmap,
        graph: EditGraph,
        outDir: File,
        warpField: WarpDisplacementField? = null,
        lut: Lut3dSamples? = null,
        skinMask: FloatArray? = null,
        subject: SubjectComposite? = null,
        inpaint: InpaintOverlay? = null,
        cloudAssets: AiAssetStore? = null
    ): String? {
        val rendered =
            renderBitmapFrame(source, graph, warpField, lut, skinMask, subject, inpaint, cloudAssets)
            ?: return null
        return compressAndWriteTo(rendered, outDir)
    }

    /** Writes the preview of a 16-bit linear [source] straight into [outDir]. */
    fun writeLinearPreviewTo(
        source: LinearRawBuffer,
        graph: EditGraph,
        outDir: File,
        warpField: WarpDisplacementField? = null,
        lut: Lut3dSamples? = null,
        skinMask: FloatArray? = null,
        subject: SubjectComposite? = null,
        inpaint: InpaintOverlay? = null,
        cloudAssets: AiAssetStore? = null
    ): String? {
        val rendered =
            renderLinearFrame(source, graph, warpField, lut, skinMask, subject, inpaint, cloudAssets)
            ?: return null
        return compressAndWriteTo(rendered, outDir)
    }

    private fun renderLinearFrame(
        source: LinearRawBuffer,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        lut: Lut3dSamples?,
        skinMask: FloatArray?,
        subject: SubjectComposite?,
        inpaint: InpaintOverlay?,
        cloudAssets: AiAssetStore?
    ): Bitmap? {
        // A composed half-float copy is JVM-owned, so dropping the reference after this frame is the
        // whole cleanup; the caller's buffer is never mutated.
        val frame = inpaint?.applyToLinearCopy(source) ?: source
        return renderLinearFrameSource(frame, graph, warpField, lut, skinMask, subject, cloudAssets)
    }

    private fun renderLinearFrameSource(
        source: LinearRawBuffer,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        lut: Lut3dSamples?,
        skinMask: FloatArray?,
        subject: SubjectComposite?,
        cloudAssets: AiAssetStore?
    ): Bitmap? {
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val outputAspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
        val (outW, outH) = previewSize(outputAspect)
        val geometryActive = graph.requiresPassAGeometry(hasWarp = warpField?.isZero() == false)
        val linearFrame = if (geometryActive) {
            val ca = graph.effects.ca
            GeometryCpuResampler.resampleLinear(
                source = source,
                inverseMatrix = GeometryMath.buildPassAInverseMatrix(graph.geometry, sourceAspect),
                outputWidth = outW,
                outputHeight = outH,
                caRedK = GeometryMath.lateralCaKFromSlider(ca.redCyan),
                caBlueK = GeometryMath.lateralCaKFromSlider(ca.blueYellow),
                warpField = warpField
            )
        } else {
            source.downscaledTo(PREVIEW_MAX_EDGE_PX)
        }
        val pixels = LinearRawPreviewRenderer.renderArgb(
            source = linearFrame,
            graph = graph,
            lut = lut,
            fullResolutionShortEdge = minOf(source.width, source.height),
            skinMask = skinMask?.takeIf { it.size == SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE },
            beforeEffects = subject?.let { composite ->
                { buffer, width, height ->
                    SubjectCpuComposer.applyForeground(
                        pixels = buffer,
                        width = width,
                        height = height,
                        graph = graph,
                        composite = composite,
                        inverseMatrix = GeometryMath.buildPassAInverseMatrix(
                            graph.geometry,
                            sourceAspect
                        ),
                        warpField = warpField
                    ).copyInto(buffer)
                }
            },
            afterEffects = checkerboardEffects(graph, subject),
            subjectWeight = resolveSubjectWeight(
                subject,
                graph,
                linearFrame.width,
                linearFrame.height,
                warpField
            ),
            cloud = cloudPreviewLayer(cloudAssets, graph, linearFrame.width, linearFrame.height)
        )
        return Bitmap.createBitmap(
            pixels,
            linearFrame.width,
            linearFrame.height,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun renderBitmapFrame(
        source: Bitmap,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        lut: Lut3dSamples?,
        skinMask: FloatArray?,
        subject: SubjectComposite?,
        inpaint: InpaintOverlay?,
        cloudAssets: AiAssetStore?
    ): Bitmap? {
        val composed = inpaint?.applyToBitmapCopy(source)
        val frame = composed ?: source
        return try {
            renderBitmapFrameSource(frame, graph, warpField, lut, skinMask, subject, cloudAssets)
        } finally {
            // The composed copy is private to this frame; the caller keeps its immutable source.
            if (composed != null) composed.recycle()
        }
    }

    private fun renderBitmapFrameSource(
        source: Bitmap,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        lut: Lut3dSamples?,
        skinMask: FloatArray?,
        subject: SubjectComposite?,
        cloudAssets: AiAssetStore?
    ): Bitmap {
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val outputAspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
        val (width, height) = previewSize(outputAspect)
        val ca = graph.effects.ca
        val geometry = GeometryMath.buildPassAInverseMatrix(graph.geometry, sourceAspect)
        val pixels = GeometryCpuResampler.resamplePixels(
            source = source,
            inverseMatrix = geometry,
            outputWidth = width,
            outputHeight = height,
            caRedK = GeometryMath.lateralCaKFromSlider(ca.redCyan),
            caBlueK = GeometryMath.lateralCaKFromSlider(ca.blueYellow),
            warpField = warpField
        )
        // The cloud layer is mounted right here: Pass A resampled the frame and Pass B/C/D have not
        // run yet, which is the same seam the GL Pass B shader blends at.
        CloudPreviewBlend.blendIntoArgb(cloudAssets, graph, pixels, width, height)
        val graded = gradeDisplayPixels(
            pixels = pixels,
            width = width,
            height = height,
            graph = graph,
            fullResolutionShortEdge = minOf(source.width, source.height),
            lut = lut,
            skinMask = skinMask,
            subject = subject,
            inverseMatrix = geometry,
            warpField = warpField,
            subjectWeight = resolveSubjectWeight(subject, graph, width, height, warpField, geometry)
        )
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(graded, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Runs Pass B + Pass C + Pass D on an ARGB [pixels] buffer without requiring an Android
     * `Bitmap`, keeping the grading chain fast, mutable-safe, and directly JVM-testable.
     */
    internal fun gradeDisplayPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        graph: EditGraph,
        fullResolutionShortEdge: Int,
        lut: Lut3dSamples? = null,
        skinMask: FloatArray? = null,
        subject: SubjectComposite? = null,
        inverseMatrix: Mat3 =
            Mat3.identity(),
        warpField: WarpDisplacementField? = null,
        subjectWeight: FloatArray? = null
    ): IntArray {
        applyPassBToPixels(pixels, width, height, graph, subjectWeight)
        val afterPassC = applyPassCToPixels(
            pixels = pixels,
            width = width,
            height = height,
            graph = graph,
            fullResolutionShortEdge = fullResolutionShortEdge,
            skinMask = skinMask,
            subjectWeight = subjectWeight
        )
        val afterSubject = SubjectCpuComposer.applyForeground(
            pixels = afterPassC,
            width = width,
            height = height,
            graph = graph,
            composite = subject,
            inverseMatrix = inverseMatrix,
            warpField = warpField
        )
        applyPassDEffectsToPixels(afterSubject, width, height, graph, lut)
        if (subject != null && graph.subject.bgMode == SubjectBackgroundMode.TRANSPARENT) {
            SubjectCpuComposer.drawCheckerboard(afterSubject, width, height)
        }
        return afterSubject
    }

    /** Pass-D presentation stage: the checkerboard must never be graded by LUT/grain/vignette. */
    private fun checkerboardEffects(
        graph: EditGraph,
        subject: SubjectComposite?
    ): ((IntArray, Int, Int) -> Unit)? {
        if (subject == null || graph.subject.bgMode != SubjectBackgroundMode.TRANSPARENT) return null
        return { pixels, width, height -> SubjectCpuComposer.drawCheckerboard(pixels, width, height) }
    }

    private fun applyPassCToPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        graph: EditGraph,
        fullResolutionShortEdge: Int,
        skinMask: FloatArray?,
        subjectWeight: FloatArray?
    ): IntArray {
        if (SpatialDetailParams.from(graph).isZeroDrawCallShortCircuit && skinMask == null) {
            return pixels
        }
        return PreviewDetailPass.apply(
            pixels,
            width,
            height,
            graph,
            fullResolutionShortEdge = fullResolutionShortEdge,
            skinMask = skinMask,
            subjectWeight = subjectWeight
        )
    }

    private fun applyPassDEffectsToPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        graph: EditGraph,
        lut: Lut3dSamples?
    ) {
        val params = PassDEffectsParams.from(graph, width.toFloat() / height.toFloat().coerceAtLeast(1.0f))
        if (params.isIdentity) return
        PassDEffectsMath.applyDisplayEffects(pixels, width, height, params, lut)
    }

    private fun applyPassBToPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        graph: EditGraph,
        subjectWeight: FloatArray?
    ) {
        val toneParams = ToneShaderParams.from(graph)
        val colorParams = ColorGradingParams.from(graph)
        val subject = graph.subject.takeIf { it.hasGradingAdjustments() && subjectWeight != null }
        if (toneParams.isIdentity && colorParams.isIdentity && subject == null) return
        val curveLut = if (colorParams.hasCurve) CurveLutBuilder.buildFloatLut(graph.color.curve) else null
        val lumaMap = if (colorParams.defringe > 0.0f) buildLumaMap(pixels) else null
        val linear = FloatArray(CHANNELS_RGB)
        val display = FloatArray(CHANNELS_RGB)
        val scratch = PassBColorMath.Scratch()
        for (index in pixels.indices) {
            val pixel = pixels[index]
            linear[0] = ExposureShaderMath.decodeSrgb(((pixel ushr 16) and 0xFF) / 255.0f)
            linear[1] = ExposureShaderMath.decodeSrgb(((pixel ushr 8) and 0xFF) / 255.0f)
            linear[2] = ExposureShaderMath.decodeSrgb((pixel and 0xFF) / 255.0f)
            val grad = lumaMap?.let { Srgb8Packer.neighborGradient(it, width, height, index) } ?: 0.0f
            val weight = subjectWeight?.getOrNull(index) ?: 0.0f
            PassBColorMath.apply(
                toneParams, colorParams, curveLut, linear, display, grad, scratch, subject, weight
            )
            pixels[index] = Srgb8Packer.packRgb(display[0], display[1], display[2], alphaBits = pixel)
        }
    }

    private fun buildLumaMap(pixels: IntArray): FloatArray = FloatArray(pixels.size) { i ->
        val p = pixels[i]
        ToneShaderMath.luminance(
            ((p ushr 16) and 0xFF) / 255.0f,
            ((p ushr 8) and 0xFF) / 255.0f,
            (p and 0xFF) / 255.0f
        )
    }

    private fun compressAndWrite(rendered: Bitmap, cacheDir: File, photoId: Long): String? {
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.isDirectory && !editDir.mkdirs() && !editDir.isDirectory) {
            rendered.recycle()
            return null
        }
        return compressAndWriteTo(rendered, editDir)
    }

    /**
     * Writes a uniquely named preview into [outDir] and returns its absolute path.
     *
     * The bitmap is recycled on every path, including failures.
     */
    private fun compressAndWriteTo(rendered: Bitmap, outDir: File): String? {
        if (!outDir.isDirectory && !outDir.mkdirs() && !outDir.isDirectory) {
            rendered.recycle()
            return null
        }
        return try {
            PreviewFileWriter.write(outDir) { stream ->
                rendered.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, stream)
            }
        } finally {
            rendered.recycle()
        }
    }

    private fun cloudPreviewLayer(
        assets: AiAssetStore?,
        graph: EditGraph,
        width: Int,
        height: Int
    ): LinearCloudLayer? {
        if (graph.cloudLayer == null || assets == null) return null
        return CloudPreviewBlend.prepareLinear(assets, graph, width, height)
    }

    /**
     * Builds the frame-aligned subject alpha when the graph needs it, or `null` for the neutral
     * zero-cost path.
     */    private fun resolveSubjectWeight(
        subject: SubjectComposite?,
        graph: EditGraph,
        width: Int,
        height: Int,
        warpField: WarpDisplacementField?,
        inverseMatrix: Mat3? = null
    ): FloatArray? {
        if (subject == null) return null
        if (!graph.subject.hasGradingAdjustments() && !graph.subject.hasBackgroundBlur()) return null
        val matrix = inverseMatrix ?: GeometryMath.buildPassAInverseMatrix(
            graph.geometry,
            width.toFloat() / height.toFloat()
        )
        return SubjectWeightPlane.compute(subject, width, height, matrix, warpField)
    }

    private fun previewSize(outputAspect: Float): Pair<Int, Int> {
        val aspect = outputAspect.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        return if (aspect >= 1.0f) {
            PREVIEW_MAX_EDGE_PX to (PREVIEW_MAX_EDGE_PX / aspect).roundToInt().coerceAtLeast(1)
        } else {
            (PREVIEW_MAX_EDGE_PX * aspect).roundToInt().coerceAtLeast(1) to PREVIEW_MAX_EDGE_PX
        }
    }
}
