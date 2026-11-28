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
 *
 * The long-edge caps default to the constants below and may be overridden with the preview
 * resolution the user selected; the same cap must reach the full preview and its drag proxy so the
 * canvas and the proxy never disagree about how much of the frame is sampled.
 */
internal object EditorBitmapLoader {

    /** Historical full-preview long edge; the code default when no setting overrides it. */
    internal const val EDITOR_PREVIEW_MAX_EDGE_PX = 2048

    /** Historical drag-proxy long edge. */
    internal const val PROXY_MAX_EDGE_PX = 1024

    /**
     * Loads an upright preview of [sourcePath].
     *
     * @param maxEdgePx Long-edge cap of the returned bitmap; defaults to
     *   [EDITOR_PREVIEW_MAX_EDGE_PX].
     */
    fun loadUprightPreviewBitmap(
        sourcePath: String,
        isRaw: Boolean,
        maxEdgePx: Int = EDITOR_PREVIEW_MAX_EDGE_PX
    ): Bitmap? {
        val file = File(sourcePath)
        if (!file.exists()) return null
        val cap = maxEdgePx.coerceAtLeast(1)
        return try {
            if (isRaw) {
                loadRawPreview(sourcePath, cap)
            } else {
                loadJpegUprightPreview(sourcePath, cap)
            }
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while loading editor preview bitmap")
            null
        } catch (e: RuntimeException) {
            Timber.e(e, "Failed to load editor preview bitmap")
            null
        }
    }

    /** Scales [source] down to [maxEdgePx], returning it unchanged when it already fits. */
    fun createProxyBitmap(
        source: Bitmap,
        maxEdgePx: Int = PROXY_MAX_EDGE_PX
    ): Bitmap {
        val cap = maxEdgePx.coerceAtLeast(1)
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= cap) return source
        val scale = cap.toFloat() / maxEdge.toFloat()
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun loadRawPreview(sourcePath: String, maxEdgePx: Int): Bitmap? {
        val thumb = RawWrapper.decodeThumbnailBitmap(sourcePath, maxEdgePx)
        if (thumb != null) return thumb
        val full = RawWrapper.decodeToBitmap(sourcePath) ?: return null
        val maxEdge = maxOf(full.width, full.height)
        if (maxEdge <= maxEdgePx) return full
        val scale = maxEdgePx.toFloat() / maxEdge.toFloat()
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

    private fun loadJpegUprightPreview(sourcePath: String, maxEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = UploadBitmapGeometry.previewSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            maxEdgePx
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
        val upright = applyExifOrientation(decoded, orientation)
        // `previewSampleSize` only picks a power-of-two sample factor, so the decoded long edge can
        // still sit between `maxEdgePx` and `2 * maxEdgePx`. A user-selected preview resolution is a
        // hard cap, not a hint, so finish the scale-down here.
        return scaleDownToMaxEdge(upright, maxEdgePx)
    }

    /** Scales [bitmap] so its long edge is exactly at most [maxEdgePx], recycling the input. */
    private fun scaleDownToMaxEdge(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdgePx) return bitmap
        val scale = maxEdgePx.toFloat() / longEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
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
