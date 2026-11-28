package cn.devcxl.photosync.editor.cloud

import android.graphics.Bitmap
import android.graphics.Matrix

import androidx.exifinterface.media.ExifInterface

import kotlin.math.roundToInt

/**
 * Bitmap orientation and scaling primitives shared by the upload sanitizers.
 *
 * Kept apart from [CloudUploadSanitizer] so the EXIF/scale math is testable on its own and the
 * sanitizer stays a file-writing state machine.
 */
internal object UploadBitmapGeometry {

    /**
     * Smallest power-of-two `BitmapFactory` sample size whose decoded long edge is still at least
     * [maxLongEdge].
     *
     * Exact scaling happens after decode, so the sample never undershoots. Shared with the editor
     * preview loader, which uses the same "up to 2x overshoot" policy.
     */
    fun previewSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        if (maxLongEdge <= 0 || width <= 0 || height <= 0) return 1
        var sampleSize = 1
        val maxDim = maxOf(width, height)
        while (maxDim / (sampleSize * 2) >= maxLongEdge) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** Applies exactly one upright transform for [orientation], recycling the input when replaced. */
    fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (oriented !== source) source.recycle()
        return oriented
    }

    /** Scales [source] so its long edge fits [maxLongEdge], recycling it when replaced. */
    fun scaleToLongEdge(source: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxLongEdge) return source
        val scale = maxLongEdge.toDouble() / longEdge
        val targetWidth = (source.width * scale).roundToInt().coerceIn(1, maxLongEdge)
        val targetHeight = (source.height * scale).roundToInt().coerceIn(1, maxLongEdge)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    /**
     * Scales a caller-owned bitmap without ever recycling it.
     *
     * [scaleToLongEdge] recycles its input because the file path owns the decoded bitmap; an
     * in-memory frame handed in by the editor is still in use after the upload is staged.
     */
    fun scaledCopy(source: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxLongEdge) return source
        val scale = maxLongEdge.toDouble() / longEdge
        val targetWidth = (source.width * scale).roundToInt().coerceIn(1, maxLongEdge)
        val targetHeight = (source.height * scale).roundToInt().coerceIn(1, maxLongEdge)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}
