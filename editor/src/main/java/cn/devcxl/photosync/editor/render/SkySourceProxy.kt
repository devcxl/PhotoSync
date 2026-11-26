package cn.devcxl.photosync.editor.render

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.vision.SubjectImage
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Decodes the `1024px` proxy of an [EditorSource] into the packed image the relight statistics read.
 */
internal object SkySourceProxy {

    private const val CHANNELS = 4
    private const val EIGHT_BIT = 255.0f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    fun of(source: EditorSource): SubjectImage? = when (source) {
        is EditorSource.BitmapSource -> fromBitmap(source.proxy)
        is EditorSource.LinearRawSource -> fromLinear(source.proxy)
    }

    private fun fromBitmap(bitmap: Bitmap): SubjectImage? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return SubjectImage(bitmap.width, bitmap.height, pixels)
    }

    private fun fromLinear(buffer: LinearRawBuffer): SubjectImage? {
        if (buffer.width <= 0 || buffer.height <= 0) return null
        val pixels = IntArray(buffer.width * buffer.height)
        val sample = FloatArray(CHANNELS)
        for (index in pixels.indices) {
            buffer.readPixel(index, sample)
            pixels[index] = (0xFF shl 24) or
                (encode(sample[0]) shl RED_SHIFT) or
                (encode(sample[1]) shl GREEN_SHIFT) or
                encode(sample[2])
        }
        return SubjectImage(buffer.width, buffer.height, pixels)
    }

    private fun encode(linear: Float): Int =
        (ExposureShaderMath.encodeSrgb(linear.coerceIn(0.0f, 1.0f)) * EIGHT_BIT + 0.5f)
            .toInt()
            .coerceIn(0, 255)
}
