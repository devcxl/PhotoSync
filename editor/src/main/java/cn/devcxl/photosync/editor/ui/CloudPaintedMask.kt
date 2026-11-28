package cn.devcxl.photosync.editor.ui

import android.graphics.Bitmap
import android.graphics.Color

import cn.devcxl.photosync.editor.domain.InpaintStrokeLayer

/**
 * Turns the erase brush's committed strokes into the editable mask a cloud repaint uploads.
 *
 * The stroke layer already lives in **visible (post-Pass A) normalized coordinates**, i.e. exactly
 * the frame the cloud request sends, so rasterizing it at the frame size needs no geometry mapping —
 * a stroke painted on the current canvas lands on the same pixels in the upload.
 *
 * Painted coverage becomes opaque white; the untouched area stays fully transparent. The provider
 * contract treats white-opaque as editable, so the endpoint keeps every unpainted pixel.
 */
internal object CloudPaintedMask {

    /** Long edge of the painted mask; the service rescales it to the sanitized upload width. */
    const val MAX_EDGE_PX = 1024

    /**
     * Rasterizes [layer] into a `width x height` editable mask.
     *
     * @return The mask bitmap, or `null` when nothing was painted or the size is degenerate.
     */
    fun rasterize(layer: InpaintStrokeLayer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || layer.isEmpty) return null
        val coverage = layer.rasterize(width, height)
        val pixels = IntArray(width * height)
        for (index in pixels.indices) {
            val alpha = coverage.coverage[index].toInt() and BYTE_MASK
            if (alpha <= 0) continue
            pixels[index] = Color.argb(alpha, 255, 255, 255)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** Mask size for a frame of [aspect] (width / height), capped at [MAX_EDGE_PX]. */
    fun sizeFor(aspect: Float): Pair<Int, Int> {
        val safe = aspect.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        return if (safe >= 1.0f) {
            MAX_EDGE_PX to (MAX_EDGE_PX / safe).toInt().coerceAtLeast(1)
        } else {
            (MAX_EDGE_PX * safe).toInt().coerceAtLeast(1) to MAX_EDGE_PX
        }
    }

    private const val BYTE_MASK = 0xFF
}
