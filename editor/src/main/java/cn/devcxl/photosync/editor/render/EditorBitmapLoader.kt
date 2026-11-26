package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

import cn.devcxl.photosync.editor.cloud.UploadBitmapGeometry
import cn.devcxl.photosync.editor.domain.GeometryMath
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
    private const val PROXY_MAX_EDGE_PX = 1024

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

    /** Scales [source] down to a 1024px drag proxy, returning it unchanged when it already fits. */
    fun createProxyBitmap(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= PROXY_MAX_EDGE_PX) return source
        val scale = PROXY_MAX_EDGE_PX.toFloat() / maxEdge.toFloat()
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
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

        val sampleSize = UploadBitmapGeometry.previewSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            EDITOR_PREVIEW_MAX_EDGE_PX
        )
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

    /**
     * Applies exactly one upright transform for [orientation].
     *
     * The rotation/mirror combination comes from [GeometryMath.discreteOpsForExifOrientation], the
     * same table the interactive rotate/flip sliders use, so the upload path and the editor can
     * never disagree about which way "upright" points.
     */
    internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val ops = GeometryMath.discreteOpsForExifOrientation(orientation)
        if (ops.quarterTurns == 0 && !ops.flipH && !ops.flipV) return bitmap
        val matrix = Matrix().apply {
            if (ops.quarterTurns != 0) postRotate(ops.quarterTurns * DEGREES_PER_QUARTER_TURN)
            if (ops.flipH) postScale(-1.0f, 1.0f)
            if (ops.flipV) postScale(1.0f, -1.0f)
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

    private const val DEGREES_PER_QUARTER_TURN = 90.0f
}
