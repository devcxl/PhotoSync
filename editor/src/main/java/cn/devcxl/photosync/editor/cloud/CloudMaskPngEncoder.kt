package cn.devcxl.photosync.editor.cloud

import java.io.File
import java.io.FileOutputStream

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Editable-mask conversion used by [CloudUploadSanitizer]'s BYOK upload path.
 *
 * A painted overlay travels as an alpha mask: painted pixels become editable (transparent) and the
 * rest stays preserved (opaque). The PNG written here contains only the alpha channel produced from
 * pixels, so no source metadata or text chunk can travel with it.
 */
internal object CloudMaskPngEncoder {

    /**
     * Converts [mask] into an editable mask bitmap at [width] x [height].
     *
     * Painted white strokes (coverage 1) become fully transparent (`alpha = 0`, editable), while
     * untouched black or fully transparent pixels become opaque (`alpha = 255`, preserved). RGB is
     * black; only the alpha channel carries the mask. Anti-aliased strokes keep their soft edges
     * because the source alpha is folded into the coverage.
     *
     * @param mask Painted overlay in any resolution.
     * @param width Target width, normally the sanitized upload width.
     * @param height Target height, normally the sanitized upload height.
     * @return A new `ARGB_8888` bitmap owned by the caller.
     */
    fun createEditableMaskBitmap(mask: Bitmap, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0) { "Mask dimensions must be positive" }
        val scaled = if (mask.width == width && mask.height == height) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, width, height, true)
        }
        try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            for (index in pixels.indices) {
                val color = pixels[index]
                val alpha = Color.alpha(color)
                val luminance = colorLuminance(color)
                val coverage = luminance * alpha / 255
                pixels[index] = Color.argb(255 - coverage, 0, 0, 0)
            }
            val editable = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            editable.setPixels(pixels, 0, width, 0, 0, width, height)
            return editable
        } finally {
            if (scaled !== mask) scaled.recycle()
        }
    }

    /**
     * Writes an editable PNG of [mask] at [width] x [height] to [outputFile].
     *
     * @return [outputFile] after a successful encode.
     * @throws CloudSanitizationException when the PNG cannot be encoded; the partial file is
     *   deleted before the exception escapes.
     */
    fun writePng(mask: Bitmap, width: Int, height: Int, outputFile: File): File {
        val editable = createEditableMaskBitmap(mask, width, height)
        var written = false
        try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                if (!editable.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw CloudSanitizationException("Mask encoding failed")
                }
            }
            written = true
            return outputFile
        } finally {
            editable.recycle()
            if (!written) outputFile.delete()
        }
    }

    private fun colorLuminance(color: Int): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return (red * 299 + green * 587 + blue * 114) / 1000
    }
}
