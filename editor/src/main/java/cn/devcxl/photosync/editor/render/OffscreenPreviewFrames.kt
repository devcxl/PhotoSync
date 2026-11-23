package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer

import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath

/**
 * Geometry and pixel-packing math of the offscreen preview render.
 *
 * The numbers here are the contract between the GPU cache writer and the CPU one
 * ([PreviewCacheWriter]): both publish a `1280px`-longest-edge frame of the same post-`Pass A`
 * aspect, so a gallery thumbnail cannot change size when the GPU path takes over.
 *
 * Pure JVM code so the size and read-back conversions are unit-testable without a device.
 */
internal object OffscreenPreviewFrames {

    /** Longest edge of a preview cache frame; mirrors [PreviewCacheWriter]'s preview size. */
    const val MAX_EDGE_PX = 1280

    private const val OPAQUE_ALPHA = 0xFF shl 24
    private const val BYTE_MASK = 0xFF
    private const val BYTES_PER_RGBA8_PIXEL = 4

    /**
     * Post-crop aspect ratio of [graph] applied to a `sourceWidth x sourceHeight` frame.
     *
     * Falls back to `1.0` for degenerate geometry so a broken graph can never produce a zero-sized
     * attachment.
     */
    fun outputAspect(graph: EditGraph, sourceWidth: Int, sourceHeight: Int): Float {
        val safeWidth = sourceWidth.coerceAtLeast(1)
        val safeHeight = sourceHeight.coerceAtLeast(1)
        val sourceAspect = safeWidth.toFloat() / safeHeight.toFloat()
        val aspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
        return if (aspect.isFinite() && aspect > 0.0f) aspect else 1.0f
    }

    /** Pixel size of the preview frame of [graph] for a `sourceWidth x sourceHeight` source. */
    fun outputSize(
        graph: EditGraph,
        sourceWidth: Int,
        sourceHeight: Int
    ): Pair<Int, Int> = sizeFor(outputAspect(graph, sourceWidth, sourceHeight))

    /** Fits a `1280px` longest edge onto [aspect]. */
    fun sizeFor(aspect: Float): Pair<Int, Int> {
        val safeAspect = if (aspect.isFinite() && aspect > 0.0f) aspect else 1.0f
        return if (safeAspect >= 1.0f) {
            MAX_EDGE_PX to (MAX_EDGE_PX / safeAspect).roundToInt().coerceAtLeast(1)
        } else {
            (MAX_EDGE_PX * safeAspect).roundToInt().coerceAtLeast(1) to MAX_EDGE_PX
        }
    }

    /**
     * Packs a tightly packed `RGBA8` read-back into opaque ARGB pixels.
     *
     * Row order is preserved: every offscreen pass renders with `QuadOrientation.OFFSCREEN`, whose
     * `v = 0` is the image top, so FBO row `0` is already the first image row. Alpha is forced
     * opaque because the preview is a JPEG.
     */
    fun toArgb(rgba: ByteBuffer, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "Frame must be positive" }
        require(rgba.capacity() >= width * height * BYTES_PER_RGBA8_PIXEL) {
            "Read-back buffer is smaller than the frame"
        }
        val pixels = IntArray(width * height)
        for (index in pixels.indices) {
            val offset = index * BYTES_PER_RGBA8_PIXEL
            val red = rgba.get(offset).toInt() and BYTE_MASK
            val green = rgba.get(offset + 1).toInt() and BYTE_MASK
            val blue = rgba.get(offset + 2).toInt() and BYTE_MASK
            pixels[index] = OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
        }
        return pixels
    }
}
