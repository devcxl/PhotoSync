package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.ExposureShaderMath

/**
 * Renders a screen-sized JPEG preview cache under `cacheDir/edits/<photoId>/` for the main
 * gallery and filmstrip.
 */
internal object PreviewCacheWriter {

    private const val PREVIEW_JPEG_QUALITY = 92

    fun writePreviewCache(
        source: Bitmap,
        exposureEv: Float,
        cacheDir: File,
        photoId: Long
    ): String? {
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) return null
        editDir.listFiles()?.forEach { it.delete() }

        val outFile = File(editDir, "preview_${System.currentTimeMillis()}.jpg")
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        if (abs(exposureEv) >= 1e-4f) {
            val lut = ExposureShaderMath.buildExposureLut8(exposureEv)
            for (i in pixels.indices) {
                val p = pixels[i]
                val a = p and -0x1000000
                val r = lut[(p ushr 16) and 0xFF]
                val g = lut[(p ushr 8) and 0xFF]
                val b = lut[p and 0xFF]
                pixels[i] = a or (r shl 16) or (g shl 8) or b
            }
        }

        val outBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        return try {
            FileOutputStream(outFile).use { stream ->
                if (!outBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        PREVIEW_JPEG_QUALITY,
                        stream
                    )
                ) {
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
