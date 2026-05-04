package cn.devcxl.photosync.wrapper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

object RawWrapper {
    private const val TAG = "RawWrapper"
    init {
        System.loadLibrary("raw_wrapper")
    }

    external fun version(): String
    external fun decodeThumbnail(path: String): ByteArray?
    external fun decodeToRGB(path: String): ByteArray?

    /**
     * 解码缩略图为 Bitmap。先尝试嵌入式 JPEG，失败则尝试 BITMAP 格式。
     */
    fun decodeThumbnailBitmap(path: String): Bitmap? {
        val data = decodeThumbnail(path)
        if (data == null) {
            Log.w(TAG, "decodeThumbnail returned null for $path")
            return null
        }
        if (data.size < 8) {
            Log.w(TAG, "decodeThumbnail returned too-small data (${data.size} bytes) for $path")
            return null
        }
        val isJpeg = data[0].toInt() and 0xFF == 0xFF && data[1].toInt() and 0xFF == 0xD8
        Log.d(TAG, "decodeThumbnail returned ${data.size}B, isJpeg=$isJpeg, path=$path")
        if (isJpeg) {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bmp == null) Log.w(TAG, "BitmapFactory failed to decode JPEG thumbnail for $path")
            return bmp
        }
        return rgbResultToBitmap(data, null)
    }

    /**
     * 完整解码 RAW -> 8bit RGB Bitmap。失败返回 null。
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
        val pixelBytes = width.toLong() * height * 3L
        if (8 + pixelBytes != data.size.toLong()) return null
        if (pixelBytes > Int.MAX_VALUE) return null

        val bmp = if (reuse != null && reuse.width == width && reuse.height == height && reuse.config == Bitmap.Config.ARGB_8888) {
            reuse.eraseColor(0); reuse
        } else {
            try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return null }
        }

        val pixels = IntArray(width * height)
        var i = 8
        var p = 0
        val d = data
        while (p < pixels.size) {
            val r = d[i++].toInt() and 0xFF
            val g = d[i++].toInt() and 0xFF
            val b = d[i++].toInt() and 0xFF
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
