package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect

import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.domain.InpaintWorkRegion
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Reads the painted window of the original file at its native resolution.
 *
 * The editor preview is capped at 2048 px, but an inpainting patch lives in the pre-crop original
 * space and must keep true 128 px context margins and `<= 256 px` 1:1 crops on a 24-60 MP photo.
 * This reader therefore never upscales the preview:
 * - JPEG: [BitmapRegionDecoder] decodes only the mapped physical rectangle, then the EXIF
 *   orientation is applied once so the window matches the upright source frame;
 * - RAW: `RawWrapper.decodeToBitmap` opens the full demosaic (LibRaw already applies the camera
 *   orientation) and the window is copied out before the temporary bitmap is recycled.
 *
 * Patches already committed to `EditGraph.inpaint` are blended into the window before MI-GAN sees
 * it, so two erases on overlapping areas compose exactly like the canvas preview.
 */
internal class InpaintSourceReader(
    private val compositorFactory: (File) -> InpaintSourceCompositor = { filesDir ->
        InpaintSourceCompositor(AiAssetStore(filesDir))
    }
) {

    private val dimensionsCache = ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * Returns the upright full-resolution size of [sourcePath].
     *
     * RAW files require a full decode because LibRaw exposes no header-only dimension query on the
     * Kotlin bridge; the result is cached per file identity so the amber advice costs nothing after
     * the first query.
     */
    suspend fun probeDimensions(sourcePath: String, isRaw: Boolean): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            val key = dimensionKey(sourcePath)
            dimensionsCache[key] ?: probeAndCache(key, sourcePath, isRaw)
        }

    private fun probeAndCache(key: String, sourcePath: String, isRaw: Boolean): Pair<Int, Int>? {
        val dimensions = if (isRaw) {
            probeRawDimensions(sourcePath)
        } else {
            probeJpegDimensions(sourcePath)
        }
        if (dimensions != null) dimensionsCache[key] = dimensions
        return dimensions
    }

    private fun dimensionKey(sourcePath: String): String {
        val file = File(sourcePath)
        return "${file.absolutePath}|${file.lastModified()}|${file.length()}"
    }

    /**
     * Decodes [region] of [sourcePath] at 1:1 and blends [patches] into it.
     *
     * @param region Window in upright pre-crop source pixels.
     * @param patches Patches of the current graph, normalized to the full source.
     * @param filesDir Directory that owns the AI asset store.
     * @return Window pixels (`ARGB_8888`, row-major) or `null` when the file cannot be read.
     */
    suspend fun readRegion(
        sourcePath: String,
        isRaw: Boolean,
        region: InpaintWorkRegion,
        patches: List<InpaintPatchRef>,
        filesDir: File
    ): IntArray? = withContext(Dispatchers.IO) {
        val pixels = try {
            if (isRaw) readRawWindow(sourcePath, region) else readJpegWindow(sourcePath, region)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while reading a %dx%d inpaint window", region.width, region.height)
            null
        } catch (error: IOException) {
            Timber.e(error, "The inpaint window could not be decoded")
            null
        } catch (error: RuntimeException) {
            Timber.e(error, "The inpaint window could not be decoded")
            null
        } ?: return@withContext null
        if (patches.isNotEmpty()) {
            compositorFactory(filesDir).applyToArgbRegion(
                pixels = pixels,
                width = region.width,
                height = region.height,
                patches = patches,
                originX = region.originX,
                originY = region.originY,
                sourceWidth = region.sourceWidth,
                sourceHeight = region.sourceHeight
            )
        }
        pixels
    }

    private fun probeJpegDimensions(sourcePath: String): Pair<Int, Int>? {
        val physical = jpegBounds(sourcePath) ?: return null
        return if (GeometryMath.swapsOrientationAxes(readOrientation(sourcePath))) {
            physical.second to physical.first
        } else {
            physical
        }
    }

    private fun probeRawDimensions(sourcePath: String): Pair<Int, Int>? {
        val bitmap = try {
            RawWrapper.decodeToBitmap(sourcePath)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while probing the RAW dimensions")
            null
        } ?: return null
        return try {
            bitmap.width to bitmap.height
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun readJpegWindow(sourcePath: String, region: InpaintWorkRegion): IntArray? {
        val orientation = readOrientation(sourcePath)
        val physical = jpegBounds(sourcePath) ?: return null
        val uprightWidth = if (GeometryMath.swapsOrientationAxes(orientation)) {
            physical.second
        } else {
            physical.first
        }
        val uprightHeight = if (GeometryMath.swapsOrientationAxes(orientation)) {
            physical.first
        } else {
            physical.second
        }
        if (uprightWidth != region.sourceWidth || uprightHeight != region.sourceHeight) {
            Timber.w("EXIF upright size changed; refusing to misplace an inpaint window")
            return null
        }
        val upright = decodeJpegUprightRegion(sourcePath, orientation, region, physical)
            ?: return null
        return try {
            copyWindow(upright, region)
        } finally {
            if (!upright.isRecycled) upright.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeJpegUprightRegion(
        sourcePath: String,
        orientation: Int,
        region: InpaintWorkRegion,
        physical: Pair<Int, Int>
    ): Bitmap? {
        val physicalRect = uprightRectToPhysical(
            region = region,
            orientation = orientation,
            physicalWidth = physical.first,
            physicalHeight = physical.second
        ) ?: return null
        val decoder = BitmapRegionDecoder.newInstance(sourcePath, false) ?: return null
        val decoded = try {
            decoder.decodeRegion(physicalRect, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } finally {
            decoder.recycle()
        } ?: return null
        return try {
            EditorBitmapLoader.applyExifOrientation(decoded, orientation)
        } catch (error: RuntimeException) {
            if (!decoded.isRecycled) decoded.recycle()
            throw error
        }
    }

    private fun copyWindow(bitmap: Bitmap, region: InpaintWorkRegion): IntArray? {
        if (bitmap.width != region.width || bitmap.height != region.height) {
            Timber.w(
                "Inpaint window decoded as %dx%d instead of %dx%d",
                bitmap.width,
                bitmap.height,
                region.width,
                region.height
            )
            return null
        }
        return IntArray(region.width * region.height).also { pixels ->
            bitmap.getPixels(pixels, 0, region.width, 0, 0, region.width, region.height)
        }
    }

    private fun jpegBounds(sourcePath: String): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }

    private fun readRawWindow(sourcePath: String, region: InpaintWorkRegion): IntArray? {
        val bitmap = RawWrapper.decodeToBitmap(sourcePath) ?: return null
        return try {
            if (bitmap.width != region.sourceWidth || bitmap.height != region.sourceHeight) {
                Timber.w("RAW dimensions differ from the probed size; skipping the inpaint read")
                null
            } else {
                IntArray(region.width * region.height).also { pixels ->
                    bitmap.getPixels(
                        pixels,
                        0,
                        region.width,
                        region.originX,
                        region.originY,
                        region.width,
                        region.height
                    )
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Maps an upright window onto the physical pixel rectangle [BitmapRegionDecoder] understands.
     *
     * Four corners are enough: EXIF orientations are axis-aligned quarter turns plus mirrors, so a
     * rectangle maps to a rectangle.
     */
    private fun uprightRectToPhysical(
        region: InpaintWorkRegion,
        orientation: Int,
        physicalWidth: Int,
        physicalHeight: Int
    ): Rect? {
        val ops = GeometryMath.discreteOpsForExifOrientation(orientation)
        val u0 = region.originX.toFloat() / region.sourceWidth
        val v0 = region.originY.toFloat() / region.sourceHeight
        val u1 = (region.originX + region.width).toFloat() / region.sourceWidth
        val v1 = (region.originY + region.height).toFloat() / region.sourceHeight
        val corners = listOf(
            GeometryMath.discreteFrameToSourceUv(ops.quarterTurns, ops.flipH, ops.flipV, u0, v0),
            GeometryMath.discreteFrameToSourceUv(ops.quarterTurns, ops.flipH, ops.flipV, u1, v0),
            GeometryMath.discreteFrameToSourceUv(ops.quarterTurns, ops.flipH, ops.flipV, u1, v1),
            GeometryMath.discreteFrameToSourceUv(ops.quarterTurns, ops.flipH, ops.flipV, u0, v1)
        )
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (corner in corners) {
            val x = corner.u * physicalWidth
            val y = corner.v * physicalHeight
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        // EXIF transforms are axis-aligned quarter turns, so every edge is integer-aligned; rounding
        // instead of floor/ceil absorbs the float error of `u * physicalWidth`.
        val rect = Rect(
            left.roundToInt().coerceIn(0, physicalWidth),
            top.roundToInt().coerceIn(0, physicalHeight),
            right.roundToInt().coerceIn(0, physicalWidth),
            bottom.roundToInt().coerceIn(0, physicalHeight)
        )
        return rect.takeUnless { it.isEmpty }
    }

    private fun readOrientation(sourcePath: String): Int = try {
        ExifInterface(sourcePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (error: IOException) {
        Timber.w(error, "Failed to read EXIF orientation for the inpaint window")
        ExifInterface.ORIENTATION_NORMAL
    }
}
