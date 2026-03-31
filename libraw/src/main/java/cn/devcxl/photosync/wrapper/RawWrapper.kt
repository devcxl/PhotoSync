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



    /**
     * 将 decodeToRGB 返回的字节数组转换为 ARGB_8888 Bitmap。
     *
     * RGB 数据布局: [w:4][h:4][R,G,B,R,G,B,...]
     * 每像素 3 字节紧凑存储（无 padding），直接循环展开转换。
     */
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
        var i = 8  // data offset after width+height header
        var p = 0
        val len = pixels.size
        // Unrolled-friendly loop with local refs for JIT optimization
        val d = data
        while (p < len) {
            // R, G, B as 0..255 → pack into ARGB (A=0xFF)
            // Little-endian read: data[i]=R, data[i+1]=G, data[i+2]=B
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
