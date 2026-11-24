package cn.devcxl.photosync.editor.export

import java.io.IOException

import android.graphics.BitmapFactory

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.ExifTagWriter
import cn.devcxl.photosync.wrapper.NativeRgb16
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Resolves the upright source dimensions a bake must plan from.
 *
 * RAW dimensions come from the LibRaw header (no demosaic), JPEG dimensions from
 * `BitmapFactory` bounds with the EXIF orientation folded in so a `Rotate 90` file plans as the
 * upright frame the decoder will actually produce.
 */
internal object BakeSourceProbe {

    /**
     * @return Upright `width to height`, or `null` when the header cannot be read.
     */
    fun dimensions(sourcePath: String, isRaw: Boolean): Pair<Int, Int>? {
        val raw = if (isRaw) NativeRgb16.probeDimensions(sourcePath) else null
        if (raw != null) return raw
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return if (isQuarterTurned(sourcePath)) {
            bounds.outHeight to bounds.outWidth
        } else {
            bounds.outWidth to bounds.outHeight
        }
    }

    private fun isQuarterTurned(sourcePath: String): Boolean {
        val orientation = try {
            ExifInterface(sourcePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (io: IOException) {
            Timber.w(io, "Failed to read the source orientation")
            ExifInterface.ORIENTATION_NORMAL
        }
        return orientation in QUARTER_TURNED_ORIENTATIONS
    }

    private val QUARTER_TURNED_ORIENTATIONS = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270
    )
}

/**
 * Writes the shooting EXIF of the original photo onto a freshly baked JPEG.
 *
 * Pixels are already upright in GPU space, so orientation is forced to `1` and the cropped
 * dimensions replace the source dimensions. RAW sources are mapped field by field through LibRaw,
 * with the capture timestamp decoded in the device's local timezone (LibRaw parses it with
 * `mktime()`), while JPEG sources copy a whitelisted tag set.
 */
internal object BakeExifWriter {

    /** Applies normalized shooting metadata to a baked image of [width] x [height]. */
    fun apply(
        sourcePath: String,
        isRaw: Boolean,
        width: Int,
        height: Int,
        target: ExifTagWriter
    ) {
        if (isRaw) {
            val metadata = try {
                RawWrapper.extractExifMetadata(sourcePath)
            } catch (e: RuntimeException) {
                Timber.w(e, "RAW metadata extraction failed; writing geometry only")
                null
            }
            if (metadata != null) {
                ExifCopier.applyFromRawMetadata(metadata, target, width, height)
                return
            }
        } else {
            ExifCopier.copyFromJpeg(sourcePath, target, width, height)
            return
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
    }
}
