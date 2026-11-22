package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.GeometryMath

/**
 * Renders the screen-sized JPEG preview cache under `cacheDir/edits/<photoId>/` for the main
 * gallery and filmstrip.
 *
 * The rendered frame follows the full Pass A + Pass B preview contract: cropped, rotated,
 * straightened, perspective-corrected, laterally CA-corrected and exposure-graded, so the cached
 * thumbnail matches what the editor showed before the user tapped "Done".
 */
internal object PreviewCacheWriter {

    private const val PREVIEW_JPEG_QUALITY = 92
    private const val PREVIEW_MAX_EDGE_PX = 1280

    /**
     * Writes a preview cache for [graph] and returns its absolute path, or `null` on I/O failure.
     *
     * @param source Upright full-preview bitmap.
     * @param graph Final edit graph of the editing session.
     * @param cacheDir Application cache directory.
     * @param photoId Photo whose preview directory receives the file.
     */
    fun writePreviewCache(
        source: Bitmap,
        graph: EditGraph,
        cacheDir: File,
        photoId: Long
    ): String? {
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) return null
        editDir.listFiles()?.forEach { it.delete() }

        val rendered = renderFrame(source, graph) ?: return null
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

    private fun renderFrame(source: Bitmap, graph: EditGraph): Bitmap? {
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
            caBlueK = GeometryMath.lateralCaKFromSlider(ca.blueYellow)
        )
        if (abs(graph.tone.exposure) >= 1e-4f) {
            applyExposure(resampled, graph.tone.exposure)
        }
        return resampled
    }

    private fun previewSize(outputAspect: Float): Pair<Int, Int> {
        val aspect = outputAspect.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        return if (aspect >= 1.0f) {
            PREVIEW_MAX_EDGE_PX to (PREVIEW_MAX_EDGE_PX / aspect).roundToInt().coerceAtLeast(1)
        } else {
            (PREVIEW_MAX_EDGE_PX * aspect).roundToInt().coerceAtLeast(1) to PREVIEW_MAX_EDGE_PX
        }
    }

    private fun applyExposure(bitmap: Bitmap, exposureEv: Float) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val lut = ExposureShaderMath.buildExposureLut8(exposureEv)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = pixel and -0x1000000
            val red = lut[(pixel ushr 16) and 0xFF]
            val green = lut[(pixel ushr 8) and 0xFF]
            val blue = lut[pixel and 0xFF]
            pixels[index] = alpha or (red shl 16) or (green shl 8) or blue
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
