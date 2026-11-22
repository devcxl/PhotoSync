package cn.devcxl.photosync.editor.ui.gesture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

import cn.devcxl.photosync.editor.domain.CropRect

/**
 * The nine drag targets of the interactive crop frame.
 */
enum class CropHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
    MOVE;

    internal val dragsU: Boolean
        get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_RIGHT ||
            this == BOTTOM_LEFT || this == LEFT || this == RIGHT

    internal val dragsV: Boolean
        get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_RIGHT ||
            this == BOTTOM_LEFT || this == TOP || this == BOTTOM
}

/**
 * Pure rect algebra behind [cn.devcxl.photosync.editor.ui.widgets.CropOverlayView].
 *
 * All spans are normalized to the displayed frame, so a "physical" aspect of `x` inside a frame of
 * aspect `a` corresponds to a normalized span ratio of `x / a`. Keeping the arithmetic here means
 * the touch handling stays a thin translation layer and the constraining rules stay unit tested.
 */
object CropHandleMath {

    /** Minimum normalized span a crop edge can be dragged to before it refuses to shrink further. */
    const val MIN_SPAN = 0.05f

    private const val COORDINATE_COUNT = 8
    private const val CORNER_COUNT = 4

    /**
     * Returns the physical (display) width/height ratio of [rect] inside a frame of [frameAspect].
     */
    fun physicalAspect(rect: CropRect, frameAspect: Float): Float {
        val width = (rect.right - rect.left).coerceAtLeast(1e-4f)
        val height = (rect.bottom - rect.top).coerceAtLeast(1e-4f)
        return frameAspect * width / height
    }

    /**
     * Returns the handle under ([u], [v]) within [radius] normalized units, preferring corners,
     * then edges, then a body drag, or `null` when the touch misses the crop frame entirely.
     */
    fun hitTest(rect: CropRect, u: Float, v: Float, radius: Float): CropHandle? {
        val nearLeft = abs(u - rect.left) <= radius
        val nearRight = abs(u - rect.right) <= radius
        val nearTop = abs(v - rect.top) <= radius
        val nearBottom = abs(v - rect.bottom) <= radius
        if (nearLeft && nearTop) return CropHandle.TOP_LEFT
        if (nearRight && nearTop) return CropHandle.TOP_RIGHT
        if (nearRight && nearBottom) return CropHandle.BOTTOM_RIGHT
        if (nearLeft && nearBottom) return CropHandle.BOTTOM_LEFT
        val withinU = u >= rect.left - radius && u <= rect.right + radius
        val withinV = v >= rect.top - radius && v <= rect.bottom + radius
        if (nearTop && withinU) return CropHandle.TOP
        if (nearBottom && withinU) return CropHandle.BOTTOM
        if (nearLeft && withinV) return CropHandle.LEFT
        if (nearRight && withinV) return CropHandle.RIGHT
        if (u > rect.left && u < rect.right && v > rect.top && v < rect.bottom) return CropHandle.MOVE
        return null
    }

    /**
     * Applies a drag of ([deltaU], [deltaV]) on [handle].
     *
     * @param lockedAspect Physical width/height ratio to preserve, or `null` for a free crop.
     * @param frameAspect Width/height ratio of the displayed frame.
     */
    fun drag(
        rect: CropRect,
        handle: CropHandle,
        deltaU: Float,
        deltaV: Float,
        lockedAspect: Float?,
        frameAspect: Float
    ): CropRect {
        if (handle == CropHandle.MOVE) return translate(rect, deltaU, deltaV)
        val anchor = anchorFor(rect, handle)
        val signU = sign(anchor.freeU - anchor.anchorU).takeIf { it != 0.0f } ?: 1.0f
        val signV = sign(anchor.freeV - anchor.anchorV).takeIf { it != 0.0f } ?: 1.0f
        val freeU = anchor.freeU.coerceOnSide(
            delta = if (handle.dragsU) deltaU else 0.0f,
            anchor = anchor.anchorU,
            sign = signU
        )
        val freeV = anchor.freeV.coerceOnSide(
            delta = if (handle.dragsV) deltaV else 0.0f,
            anchor = anchor.anchorV,
            sign = signV
        )
        var width = abs(freeU - anchor.anchorU)
        var height = abs(freeV - anchor.anchorV)
        if (lockedAspect != null && lockedAspect > 0.0f) {
            val fitted = fitLockedSpan(
                width = width,
                height = height,
                ratio = lockedAspect / frameAspect,
                signU = signU,
                signV = signV,
                anchorU = anchor.anchorU,
                anchorV = anchor.anchorV
            )
            width = fitted[0]
            height = fitted[1]
        }
        return buildRect(anchor.anchorU, anchor.anchorV, signU, signV, width, height)
    }

    /**
     * Returns the largest centered rectangle of [targetAspect] that fits inside the frame, used by
     * the `原比例 / 1:1 / 4:3 / 16:9` aspect chips.
     */
    fun centredWithAspect(targetAspect: Float, frameAspect: Float): CropRect {
        val safeFrame = frameAspect.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        val safeTarget = targetAspect.takeIf { it.isFinite() && it > 0.0f } ?: safeFrame
        val ratio = safeTarget / safeFrame
        var width = 1.0f
        var height = width / ratio
        if (height > 1.0f) {
            height = 1.0f
            width = height * ratio
        }
        val left = 0.5f - width / 2.0f
        val top = 0.5f - height / 2.0f
        return CropRect(left, top, left + width, top + height)
    }

    /**
     * Moves one perspective corner pin by ([deltaU], [deltaV]) and keeps it inside the source frame.
     *
     * @return A new eight-element quad; the input array is not modified.
     */
    fun dragPerspectiveCorner(
        perspective: FloatArray,
        cornerIndex: Int,
        deltaU: Float,
        deltaV: Float
    ): FloatArray {
        val updated = perspective.copyOf()
        if (updated.size != COORDINATE_COUNT || cornerIndex !in 0 until CORNER_COUNT) return updated
        updated[cornerIndex * 2] = (updated[cornerIndex * 2] + deltaU).coerceIn(0.0f, 1.0f)
        updated[cornerIndex * 2 + 1] = (updated[cornerIndex * 2 + 1] + deltaV).coerceIn(0.0f, 1.0f)
        return updated
    }

    private class HandleAnchor(
        val anchorU: Float,
        val anchorV: Float,
        val freeU: Float,
        val freeV: Float
    )

    private fun anchorFor(rect: CropRect, handle: CropHandle): HandleAnchor = when (handle) {
        CropHandle.TOP_LEFT -> HandleAnchor(rect.right, rect.bottom, rect.left, rect.top)
        CropHandle.TOP_RIGHT -> HandleAnchor(rect.left, rect.bottom, rect.right, rect.top)
        CropHandle.BOTTOM_RIGHT -> HandleAnchor(rect.left, rect.top, rect.right, rect.bottom)
        CropHandle.BOTTOM_LEFT -> HandleAnchor(rect.right, rect.top, rect.left, rect.bottom)
        CropHandle.TOP -> HandleAnchor(rect.left, rect.bottom, rect.right, rect.top)
        CropHandle.BOTTOM -> HandleAnchor(rect.left, rect.top, rect.right, rect.bottom)
        CropHandle.LEFT -> HandleAnchor(rect.right, rect.top, rect.left, rect.bottom)
        CropHandle.RIGHT -> HandleAnchor(rect.left, rect.top, rect.right, rect.bottom)
        CropHandle.MOVE -> HandleAnchor(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun Float.coerceOnSide(delta: Float, anchor: Float, sign: Float): Float {
        val moved = this + delta
        return if (sign < 0.0f) {
            moved.coerceIn(0.0f, (anchor - MIN_SPAN).coerceAtLeast(0.0f))
        } else {
            moved.coerceIn((anchor + MIN_SPAN).coerceAtMost(1.0f), 1.0f)
        }
    }

    private fun fitLockedSpan(
        width: Float,
        height: Float,
        ratio: Float,
        signU: Float,
        signV: Float,
        anchorU: Float,
        anchorV: Float
    ): FloatArray {
        val maxWidth = if (signU < 0.0f) anchorU else 1.0f - anchorU
        val maxHeight = if (signV < 0.0f) anchorV else 1.0f - anchorV
        var fittedWidth = min(width, maxWidth)
        var fittedHeight = fittedWidth / ratio
        if (fittedHeight > maxHeight) {
            fittedHeight = maxHeight
            fittedWidth = fittedHeight * ratio
        }
        if (fittedHeight < MIN_SPAN) {
            fittedHeight = MIN_SPAN
            fittedWidth = fittedHeight * ratio
        }
        if (fittedWidth < MIN_SPAN) {
            fittedWidth = MIN_SPAN
            fittedHeight = fittedWidth / ratio
        }
        return floatArrayOf(fittedWidth, fittedHeight)
    }

    private fun buildRect(
        anchorU: Float,
        anchorV: Float,
        signU: Float,
        signV: Float,
        width: Float,
        height: Float
    ): CropRect {
        val otherU = anchorU + signU * width
        val otherV = anchorV + signV * height
        return CropRect(
            left = min(anchorU, otherU).coerceIn(0.0f, 1.0f),
            top = min(anchorV, otherV).coerceIn(0.0f, 1.0f),
            right = max(anchorU, otherU).coerceIn(0.0f, 1.0f),
            bottom = max(anchorV, otherV).coerceIn(0.0f, 1.0f)
        )
    }

    private fun translate(rect: CropRect, deltaU: Float, deltaV: Float): CropRect {
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        val left = (rect.left + deltaU).coerceIn(0.0f, (1.0f - width).coerceAtLeast(0.0f))
        val top = (rect.top + deltaV).coerceIn(0.0f, (1.0f - height).coerceAtLeast(0.0f))
        return CropRect(left, top, left + width, top + height)
    }
}
