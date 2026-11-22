package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.Srgb8Packer
import cn.devcxl.photosync.editor.domain.ToneShaderMath
import cn.devcxl.photosync.editor.domain.ToneShaderParams
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Renders the screen-sized JPEG preview cache under `cacheDir/edits/<photoId>/` for the main
 * gallery and filmstrip.
 *
 * Applies the full Pass A (crop, rotate, straighten, perspective, liquify, lateral CA) + Pass B
 * (`BaseLook`, D65-anchored white balance, linear tone, display tone) contract so the cached
 * thumbnail matches the GL canvas.
 */
internal object PreviewCacheWriter {

    private const val PREVIEW_JPEG_QUALITY = 92
    private const val PREVIEW_MAX_EDGE_PX = 1280
    private const val CHANNELS_RGB = 3

    /**
     * Writes the preview cache for an 8-bit sRGB [source] bitmap.
     */
    fun writePreviewCache(
        source: Bitmap,
        graph: EditGraph,
        cacheDir: File,
        photoId: Long,
        liquifyMesh: LiquifyMesh? = null
    ): String? {
        val rendered = renderBitmapFrame(source, graph, liquifyMesh) ?: return null
        return compressAndWrite(rendered, cacheDir, photoId)
    }

    /**
     * Writes the preview cache for a 16-bit linear RAW [source] buffer.
     */
    fun writePreviewCache(
        source: LinearRawBuffer,
        graph: EditGraph,
        cacheDir: File,
        photoId: Long,
        liquifyMesh: LiquifyMesh? = null
    ): String? {
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val outputAspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
        val (outW, outH) = previewSize(outputAspect)
        val geometryActive = !graph.geometry.isIdentity() ||
            !graph.effects.ca.isIdentity() ||
            liquifyMesh != null
        val linearFrame = if (geometryActive) {
            val ca = graph.effects.ca
            GeometryCpuResampler.resampleLinear(
                source = source,
                inverseMatrix = GeometryMath.buildPassAInverseMatrix(graph.geometry, sourceAspect),
                outputWidth = outW,
                outputHeight = outH,
                caRedK = GeometryMath.lateralCaKFromSlider(ca.redCyan),
                caBlueK = GeometryMath.lateralCaKFromSlider(ca.blueYellow),
                liquifyMesh = liquifyMesh
            )
        } else {
            source.downscaledTo(PREVIEW_MAX_EDGE_PX)
        }
        val pixels = LinearRawPreviewRenderer.renderArgb(linearFrame, graph)
        val outBitmap = Bitmap.createBitmap(
            pixels,
            linearFrame.width,
            linearFrame.height,
            Bitmap.Config.ARGB_8888
        )
        return compressAndWrite(outBitmap, cacheDir, photoId)
    }

    private fun renderBitmapFrame(
        source: Bitmap,
        graph: EditGraph,
        liquifyMesh: LiquifyMesh?
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
            liquifyMesh = liquifyMesh
        )
        applyToneToDisplayPixels(resampled, ToneShaderParams.from(graph))
        return resampled
    }

    private fun applyToneToDisplayPixels(bitmap: Bitmap, params: ToneShaderParams) {
        if (params.isIdentity) return
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val linear = FloatArray(CHANNELS_RGB)
        val display = FloatArray(CHANNELS_RGB)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            linear[0] = ExposureShaderMath.decodeSrgb(((pixel ushr 16) and 0xFF) / 255.0f)
            linear[1] = ExposureShaderMath.decodeSrgb(((pixel ushr 8) and 0xFF) / 255.0f)
            linear[2] = ExposureShaderMath.decodeSrgb((pixel and 0xFF) / 255.0f)
            ToneShaderMath.apply(params, linear, display)
            pixels[index] = Srgb8Packer.packRgb(
                display[0],
                display[1],
                display[2],
                alphaBits = pixel
            )
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun compressAndWrite(rendered: Bitmap, cacheDir: File, photoId: Long): String? {
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) {
            rendered.recycle()
            return null
        }
        editDir.listFiles()?.forEach { it.delete() }

        val outFile = File(editDir, "preview_${System.currentTimeMillis()}.jpg")
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
