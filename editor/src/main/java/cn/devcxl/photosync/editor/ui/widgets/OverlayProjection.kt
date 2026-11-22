package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.hypot
import kotlin.math.min

import android.graphics.RectF

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.ui.gesture.CanvasTransform
import cn.devcxl.photosync.editor.ui.gesture.CropHandle
import cn.devcxl.photosync.editor.ui.gesture.CropHandleMath

/**
 * Maps normalized UV coordinates onto the preview canvas of [CropOverlayView].
 *
 * The GL pass letterboxes the frame into the viewport and then applies the two-finger zoom/pan, so
 * the overlay has to reproduce that projection exactly: a handle drawn at UV `(u, v)` must sit on
 * the pixel the shader put there.
 */
internal class OverlayProjection {

    /** Pixel rect the rendered frame currently occupies. */
    val imageRect: RectF = RectF()

    /**
     * Recomputes [imageRect].
     *
     * @return True when the rect is non-empty and hit testing is meaningful.
     */
    fun update(
        viewWidth: Int,
        viewHeight: Int,
        frameAspect: Float,
        transform: CanvasTransform
    ): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0) {
            imageRect.setEmpty()
            return false
        }
        val width = viewWidth.toFloat()
        val height = viewHeight.toFloat()
        val viewAspect = width / height
        val fitWidthFraction = if (frameAspect > viewAspect) 1.0f else frameAspect / viewAspect
        val fitHeightFraction = if (frameAspect > viewAspect) viewAspect / frameAspect else 1.0f
        val imageWidth = width * fitWidthFraction * transform.scale
        val imageHeight = height * fitHeightFraction * transform.scale
        val centerX = width / 2.0f + transform.offsetX * width / 2.0f
        val centerY = height / 2.0f - transform.offsetY * height / 2.0f
        imageRect.set(
            centerX - imageWidth / 2.0f,
            centerY - imageHeight / 2.0f,
            centerX + imageWidth / 2.0f,
            centerY + imageHeight / 2.0f
        )
        return !imageRect.isEmpty
    }

    /**
     * Projects normalized UV back into view pixels.
     */
    fun xOf(u: Float): Float = imageRect.left + u * imageRect.width()

    /**
     * Projects normalized UV back into view pixels.
     */
    fun yOf(v: Float): Float = imageRect.top + v * imageRect.height()

    /**
     * Converts a crop rectangle into view pixels.
     */
    fun rectToPixels(rect: CropRect): RectF = RectF(
        xOf(rect.left),
        yOf(rect.top),
        xOf(rect.right),
        yOf(rect.bottom)
    )

    /**
     * Converts a horizontal touch coordinate into normalized UV.
     */
    fun uOf(x: Float): Float = (x - imageRect.left) / imageRect.width()

    /**
     * Converts a vertical touch coordinate into normalized UV.
     */
    fun vOf(y: Float): Float = (y - imageRect.top) / imageRect.height()

    /**
     * Returns the crop handle under a touch, or `null` when the touch misses the crop frame.
     */
    fun hitTestCrop(cropRect: CropRect, x: Float, y: Float, radiusPx: Float): CropHandle? {
        if (imageRect.isEmpty) return null
        val radiusUv = radiusPx / min(imageRect.width(), imageRect.height())
        return CropHandleMath.hitTest(cropRect, uOf(x), vOf(y), radiusUv)
    }

    /**
     * Returns the index of the perspective pin under a touch, or `-1` when none is close enough.
     */
    fun hitTestPin(quad: FloatArray, x: Float, y: Float, radiusPx: Float): Int {
        if (imageRect.isEmpty) return -1
        var closest = -1
        var closestDistance = radiusPx
        for (corner in 0 until quad.size / 2) {
            val distance = hypot(x - xOf(quad[corner * 2]), y - yOf(quad[corner * 2 + 1]))
            if (distance <= closestDistance) {
                closestDistance = distance
                closest = corner
            }
        }
        return closest
    }

    /**
     * Applies a pixel-space drag to one crop handle.
     */
    fun dragCrop(
        cropRect: CropRect,
        handle: CropHandle,
        stepX: Float,
        stepY: Float,
        lockedAspect: Float?,
        frameAspect: Float
    ): CropRect? {
        if (imageRect.isEmpty) return null
        return CropHandleMath.drag(
            rect = cropRect,
            handle = handle,
            deltaU = stepX / imageRect.width(),
            deltaV = stepY / imageRect.height(),
            lockedAspect = lockedAspect,
            frameAspect = frameAspect
        )
    }

    /**
     * Applies a pixel-space drag to one perspective pin.
     */
    fun dragPin(quad: FloatArray, cornerIndex: Int, stepX: Float, stepY: Float): FloatArray? {
        if (imageRect.isEmpty) return null
        return CropHandleMath.dragPerspectiveCorner(
            perspective = quad,
            cornerIndex = cornerIndex,
            deltaU = stepX / imageRect.width(),
            deltaV = stepY / imageRect.height()
        )
    }
}
