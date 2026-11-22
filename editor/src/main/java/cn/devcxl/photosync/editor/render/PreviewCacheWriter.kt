package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Renders a screen-sized JPEG preview cache under `cacheDir/edits/<photoId>/` for the main
 * gallery and filmstrip.
 */
internal object PreviewCacheWriter {

    private const val PREVIEW_JPEG_QUALITY = 92
    private const val MAX_CACHE_LONG_EDGE_PX = 2048

    /**
     * Writes the preview cache for an 8-bit sRGB [source] (JPEG input, or RAW on the fallback
     * path) with the exposure adjustment applied in linear light.
     */
    fun writePreviewCache(
        source: Bitmap,
        exposureEv: Float,
        cacheDir: File,
        photoId: Long
    ): String? {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        if (abs(exposureEv) >= 1e-4f) {
            val lut = ExposureShaderMath.buildExposureLut8(exposureEv)
            for (i in pixels.indices) {
                val packed = pixels[i]
                val alpha = packed and -0x1000000
                val red = lut[(packed ushr 16) and 0xFF]
                val green = lut[(packed ushr 8) and 0xFF]
                val blue = lut[packed and 0xFF]
                pixels[i] = alpha or (red shl 16) or (green shl 8) or blue
            }
        }
        return writeCachedPixels(pixels, width, height, cacheDir, photoId)
    }

    /**
     * Writes the preview cache for a 16-bit linear RAW [source], reproducing the base look and
     * exposure the user just saw on the GL canvas.
     */
    fun writePreviewCache(
        source: LinearRawBuffer,
        baseLook: BaseLook,
        exposureEv: Float,
        cacheDir: File,
        photoId: Long
    ): String? {
        val downscaled = downscale(source)
        val pixels = LinearRawPreviewRenderer.renderArgb(downscaled, baseLook, exposureEv)
        return writeCachedPixels(pixels, downscaled.width, downscaled.height, cacheDir, photoId)
    }

    private fun downscale(source: LinearRawBuffer): LinearRawBuffer =
        source.downscaledTo(MAX_CACHE_LONG_EDGE_PX)

    private fun writeCachedPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        cacheDir: File,
        photoId: Long
    ): String? {
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) return null
        editDir.listFiles()?.forEach { it.delete() }

        val outFile = File(editDir, "preview_${System.currentTimeMillis()}.jpg")
        val outBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            FileOutputStream(outFile).use { stream ->
                if (!outBitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, stream)) {
                    return null
                }
                stream.flush()
            }
            outFile.absolutePath
        } finally {
            outBitmap.recycle()
        }
    }
}
