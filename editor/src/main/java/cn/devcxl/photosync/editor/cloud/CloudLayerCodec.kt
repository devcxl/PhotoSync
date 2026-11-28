package cn.devcxl.photosync.editor.cloud

import java.io.ByteArrayOutputStream

import android.graphics.Bitmap
import android.graphics.BitmapFactory

import timber.log.Timber

/**
 * Decodes the pixels a provider returned and re-encodes them for the content-addressed store.
 *
 * The provider response is untrusted: dimensions are read with `inJustDecodeBounds` first, so a
 * decompression bomb is rejected before any pixel buffer is allocated, and the decoded bitmap is
 * always opaque enough for a PNG that may carry alpha from a background replacement.
 */
internal class CloudLayerCodec {

    /**
     * Decodes [bytes] into an `ARGB_8888` bitmap.
     *
     * @return The bitmap owned by the caller, or `null` when the payload is not a decodable image or
     *   exceeds [MAX_EDGE] / [MAX_PIXELS].
     */
    fun decodeImage(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            Timber.w("Cloud result rejected: payload is not a decodable image")
            return null
        }
        if (width > MAX_EDGE || height > MAX_EDGE || width.toLong() * height > MAX_PIXELS) {
            Timber.w("Cloud result rejected: decoded extent exceeds the layer budget")
            return null
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (bitmap == null) Timber.w("Cloud result rejected: pixel decode failed")
        return bitmap
    }

    /**
     * Encodes [bitmap] as a metadata-free PNG.
     *
     * `Bitmap.compress` writes only pixel chunks, so no provider EXIF or text chunk survives the
     * round trip into `filesDir/edits/<photoId>/`.
     *
     * @return The PNG bytes, or `null` when the encoder failed.
     */
    fun encodePng(bitmap: Bitmap): ByteArray? {
        if (bitmap.isRecycled) return null
        val output = ByteArrayOutputStream()
        return try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)) {
                Timber.w("Cloud result could not be encoded as PNG")
                null
            } else {
                output.toByteArray()
            }
        } finally {
            output.reset()
        }
    }

    private companion object {
        /** Largest accepted edge of one returned layer. */
        const val MAX_EDGE = 4096

        /** Largest accepted pixel count; keeps one decoded layer inside a preview budget. */
        const val MAX_PIXELS = 16L * 1024L * 1024L

        const val PNG_QUALITY = 100
    }
}
