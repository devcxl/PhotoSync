package cn.devcxl.photosync.editor.ui

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.SkyCustomAsset
import cn.devcxl.photosync.editor.domain.SkyCustomAssetStore

/**
 * Re-encodes a user-selected SAF sky image into the content-addressed custom store.
 *
 * The payload is bounded before decode, sampled down to [MAX_EDGE_PX] and re-encoded as JPEG so the
 * stored file carries no EXIF, no alpha and no multi-megabyte source overhead. Keeping the pipeline
 * out of the UI controller makes it testable without an Activity or an `AlertDialog`.
 */
internal object SkyImportPipeline {

    private const val MAX_IMPORT_EDGE_PX = 4096
    private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
    private const val BUFFER_BYTES = 16 * 1024
    private const val JPEG_QUALITY = 92

    /** Imports [input] into [store], or returns `null` for an unreadable/non-JPEG image. */
    fun import(input: InputStream, store: SkyCustomAssetStore): SkyCustomAsset? {
        val payload = try {
            readBounded(input, MAX_IMPORT_BYTES) ?: return null
        } catch (io: IOException) {
            Timber.e(io, "The selected sky could not be read")
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(payload, 0, payload.size, options) ?: return null
        return try {
            val scaled = scaleToMaxEdge(decoded, SkyCustomAssetStore.MAX_EDGE_PX)
            try {
                val jpeg = ByteArrayOutputStream().use { stream ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    stream.toByteArray()
                }
                store.importJpeg(jpeg, scaled.width, scaled.height)
            } finally {
                if (scaled !== decoded && !scaled.isRecycled) scaled.recycle()
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_IMPORT_EDGE_PX * 2) sample *= 2
        return sample
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                Timber.w("Rejected a sky import above the byte budget")
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
