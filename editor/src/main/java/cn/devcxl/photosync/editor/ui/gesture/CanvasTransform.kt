package cn.devcxl.photosync.editor.ui.gesture

import kotlin.math.abs

/**
 * Immutable zoom/pan state of the preview canvas.
 *
 * Two fingers always own the canvas, so a photographer can zoom into a corner without the geometry
 * handles stealing the gesture. Scale is clamped to [MIN_SCALE]..[MAX_SCALE] and the offset is
 * clamped so the image can never be pushed completely off screen.
 */
data class CanvasTransform(
    val scale: Float = 1.0f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f
) {

    val isIdentity: Boolean
        get() = abs(scale - 1.0f) < 1e-4f && abs(offsetX) < 1e-4f && abs(offsetY) < 1e-4f

    /**
     * Applies a pinch gesture.
     *
     * @param scaleFactor Relative scale change of this gesture step.
     * @param focusX Focus point in normalized canvas coordinates, `0..1`.
     * @param focusY Focus point in normalized canvas coordinates, `0..1`.
     */
    fun pinch(scaleFactor: Float, focusX: Float, focusY: Float): CanvasTransform {
        if (!scaleFactor.isFinite() || scaleFactor <= 0.0f) return this
        val targetScale = (scale * scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val appliedFactor = targetScale / scale
        // Keep the pinch focus anchored: the content point under the fingers must not slide.
        val newOffsetX = (offsetX + (focusX - 0.5f) * 2.0f) * appliedFactor -
            (focusX - 0.5f) * 2.0f
        val newOffsetY = (offsetY + (focusY - 0.5f) * 2.0f) * appliedFactor -
            (focusY - 0.5f) * 2.0f
        return CanvasTransform(targetScale, newOffsetX, newOffsetY).clamped()
    }

    /**
     * Applies a two-finger pan in normalized canvas coordinates.
     */
    fun pan(deltaX: Float, deltaY: Float): CanvasTransform {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return this
        return copy(offsetX = offsetX + deltaX * 2.0f, offsetY = offsetY + deltaY * 2.0f).clamped()
    }

    /**
     * Returns to the fit-to-screen transform.
     */
    fun reset(): CanvasTransform = CanvasTransform()

    /**
     * Clamps the offset so the scaled image always covers the viewport centre.
     */
    fun clamped(): CanvasTransform {
        val limit = ((scale - 1.0f) / 2.0f).coerceAtLeast(0.0f) * MAX_OFFSET_SHARE
        return copy(
            scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
            offsetX = offsetX.coerceIn(-limit, limit),
            offsetY = offsetY.coerceIn(-limit, limit)
        )
    }

    companion object {
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 6.0f

        /**
         * Share of the overflow that the offset may consume, leaving a small margin of image
         * visible at every zoom level.
         */
        private const val MAX_OFFSET_SHARE = 1.0f
    }
}
