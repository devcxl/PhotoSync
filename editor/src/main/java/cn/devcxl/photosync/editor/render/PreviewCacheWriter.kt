package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.CurveLutBuilder
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.SkinMaskMath
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassBColorMath
import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.PreviewDetailPass
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.Srgb8Packer
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
        skinMask: FloatArray? = null
    ): String? {
        val rendered = renderBitmapFrame(source, graph, warpField, lut, skinMask) ?: return null
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
        skinMask: FloatArray? = null
    ): String? {
        val rendered = renderLinearFrame(source, graph, warpField, lut, skinMask) ?: return null
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
        skinMask: FloatArray? = null
    ): String? {
        val rendered = renderBitmapFrame(source, graph, warpField, lut, skinMask) ?: return null
        return compressAndWriteTo(rendered, outDir)
    }

    /** Writes the preview of a 16-bit linear [source] straight into [outDir]. */
    fun writeLinearPreviewTo(
        source: LinearRawBuffer,
        graph: EditGraph,
        outDir: File,
        warpField: WarpDisplacementField? = null,
        lut: Lut3dSamples? = null,
        skinMask: FloatArray? = null
    ): String? {
        val rendered = renderLinearFrame(source, graph, warpField, lut, skinMask) ?: return null
        return compressAndWriteTo(rendered, outDir)
    }

    private fun renderLinearFrame(
        source: LinearRawBuffer,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        lut: Lut3dSamples?,
        skinMask: FloatArray?
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
            skinMask = skinMask?.takeIf { it.size == SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE }
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
        skinMask: FloatArray?
    ): Bitmap? {
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val outputAspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
        val (width, height) = previewSize(outputAspect)
        val ca = graph.effects.ca
        val geometry = GeometryMath.buildPassAInverseMatrix(graph.geometry, sourceAspect)
        val resampled = GeometryCpuResampler.resample(
            source = source,
            inverseMatrix = geometry,
            outputWidth = width,
            outputHeight = height,
            caRedK = GeometryMath.lateralCaKFromSlider(ca.redCyan),
            caBlueK = GeometryMath.lateralCaKFromSlider(ca.blueYellow),
            warpField = warpField
        )
        applyPassBToDisplayPixels(resampled, graph)
        applyPassC(resampled, graph, minOf(source.width, source.height), skinMask)
        applyPassDEffects(resampled, graph, lut)
        return resampled
    }

    private fun applyPassC(
        bitmap: Bitmap,
        graph: EditGraph,
        fullResolutionShortEdge: Int,
        skinMask: FloatArray?
    ) {
        if (SpatialDetailParams.from(graph).isZeroDrawCallShortCircuit &&
            skinMask == null
        ) {
            return
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val detailed = PreviewDetailPass.apply(
            pixels,
            width,
            height,
            graph,
            fullResolutionShortEdge = fullResolutionShortEdge,
            skinMask = skinMask
        )
        bitmap.setPixels(detailed, 0, width, 0, 0, width, height)
    }

    private fun applyPassDEffects(bitmap: Bitmap, graph: EditGraph, lut: Lut3dSamples?) {
        val params = PassDEffectsParams.from(graph, bitmap.width.toFloat() / bitmap.height.toFloat())
        if (params.isIdentity) return
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        PassDEffectsMath.applyDisplayEffects(pixels, width, height, params, lut)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun applyPassBToDisplayPixels(bitmap: Bitmap, graph: EditGraph) {
        val toneParams = ToneShaderParams.from(graph)
        val colorParams = ColorGradingParams.from(graph)
        if (toneParams.isIdentity && colorParams.isIdentity) return
        val curveLut = if (colorParams.hasCurve) CurveLutBuilder.buildFloatLut(graph.color.curve) else null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
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
            PassBColorMath.apply(toneParams, colorParams, curveLut, linear, display, grad, scratch)
            pixels[index] = Srgb8Packer.packRgb(display[0], display[1], display[2], alphaBits = pixel)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
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
        if (!editDir.exists() && !editDir.mkdirs()) {
            rendered.recycle()
            return null
        }
        editDir.listFiles()?.forEach { it.delete() }
        return compressAndWriteTo(rendered, editDir)
    }

    /**
     * Compresses [rendered] into `preview_<ts>.jpg` inside [outDir] and returns its absolute path.
     *
     * The bitmap is recycled on every path, including failures.
     */
    private fun compressAndWriteTo(rendered: Bitmap, outDir: File): String? {
        if (!outDir.exists() && !outDir.mkdirs()) {
            rendered.recycle()
            return null
        }
        val outFile = File(outDir, "preview_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(outFile).use { stream ->
                if (!rendered.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, stream)) {
                    return null
                }
                stream.flush()
            }
            outFile.absolutePath
        } finally {
            rendered.recycle()
        }
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
