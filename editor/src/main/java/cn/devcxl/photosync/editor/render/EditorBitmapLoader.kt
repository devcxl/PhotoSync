package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Loads and orientation-normalizes source images for the editor preview pipeline.
 *
 * LibRaw already applies camera orientation (`user_flip = -1`), so RAW bitmaps are not
 * rotated a second time. JPEG bitmaps decoded via [BitmapFactory] are rotated once according
 * to their EXIF `TAG_ORIENTATION` tag so all textures enter the GL pipeline upright.
 */
internal object EditorBitmapLoader {

    private const val EDITOR_PREVIEW_MAX_EDGE_PX = 2048

    fun loadUprightPreviewBitmap(sourcePath: String, isRaw: Boolean): Bitmap? {
        val file = File(sourcePath)
        if (!file.exists()) return null
        return try {
            if (isRaw) {
                loadRawPreview(sourcePath)
            } else {
                loadJpegUprightPreview(sourcePath)
            }
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while loading editor preview bitmap")
            null
        } catch (e: RuntimeException) {
            Timber.e(e, "Failed to load editor preview bitmap")
            null
        }
    }

    private fun loadRawPreview(sourcePath: String): Bitmap? {
        val thumb = RawWrapper.decodeThumbnailBitmap(sourcePath, EDITOR_PREVIEW_MAX_EDGE_PX)
        if (thumb != null) return thumb
        val full = RawWrapper.decodeToBitmap(sourcePath) ?: return null
        val maxEdge = maxOf(full.width, full.height)
        if (maxEdge <= EDITOR_PREVIEW_MAX_EDGE_PX) return full
        val scale = EDITOR_PREVIEW_MAX_EDGE_PX.toFloat() / maxEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            full,
            (full.width * scale).roundToInt().coerceAtLeast(1),
            (full.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== full) {
            full.recycle()
        }
        return scaled
    }

    private fun loadJpegUprightPreview(sourcePath: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sampleSize * 2) >= EDITOR_PREVIEW_MAX_EDGE_PX) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(sourcePath, opts) ?: return null
        val orientation = try {
            ExifInterface(sourcePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: IOException) {
            Timber.w(e, "Failed to read EXIF orientation; defaulting to normal")
            ExifInterface.ORIENTATION_NORMAL
        }
        return applyExifOrientation(decoded, orientation)
    }

    internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (transformed !== bitmap) {
            bitmap.recycle()
        }
        return transformed
    }
}
