package cn.devcxl.photosync.wrapper

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object RawWrapper {
    init {
        System.loadLibrary("raw_wrapper")
    }

    external fun version(): String
    external fun decodeThumbnail(path: String): ByteArray?
    external fun decodeToRGB(path: String): ByteArray?

    /**
     * 解码嵌入 JPEG 缩略图并转成 Bitmap；失败返回 null。
     */
    fun decodeThumbnailBitmap(path: String): Bitmap? {
        val jpg = decodeThumbnail(path) ?: return null
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
    }


    /**
     * 完整解码 RAW -> 8bit RGB，返回 Bitmap (ARGB_8888，Alpha 固定 0xFF)。失败返回 null。
     */
    fun decodeToBitmap(path: String, reuse: Bitmap? = null): Bitmap? {
        val data = decodeToRGB(path) ?: return null
        return rgbResultToBitmap(data, reuse)
    }



    fun rgbResultToBitmap(data: ByteArray, reuse: Bitmap? = null): Bitmap? {
        if (data.size < 8) return null
        val width = leInt(data, 0)
        val height = leInt(data, 4)
        if (width <= 0 || height <= 0) return null
        val pixelBytes = width * height * 3L
        if (pixelBytes + 8L != data.size.toLong()) return null
        if (pixelBytes > Int.MAX_VALUE) return null
        val bmp = if (reuse != null && reuse.width == width && reuse.height == height && reuse.config == Bitmap.Config.ARGB_8888) {
            reuse.eraseColor(0); reuse
        } else {
            try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return null }
        }
        val pixels = IntArray(width * height)
        var i = 8
        var p = 0
        while (p < pixels.size) {
            val r = data[i++].toInt() and 0xFF
            val g = data[i++].toInt() and 0xFF
            val b = data[i++].toInt() and 0xFF
            pixels[p++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun leInt(arr: ByteArray, off: Int): Int {
        return (arr[off].toInt() and 0xFF) or
                ((arr[off + 1].toInt() and 0xFF) shl 8) or
                ((arr[off + 2].toInt() and 0xFF) shl 16) or
                ((arr[off + 3].toInt() and 0xFF) shl 24)
    }
}