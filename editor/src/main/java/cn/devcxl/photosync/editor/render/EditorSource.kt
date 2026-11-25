package cn.devcxl.photosync.editor.render

import android.graphics.Bitmap

import timber.log.Timber

import cn.devcxl.photosync.wrapper.LinearRawBuffer
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Source pixels staged for the editor canvas.
 *
 * RAW photos arrive as 16-bit linear [LinearRawBuffer] texels so the GPU keeps the full dynamic
 * range; JPEG photos (and RAW files on devices without a GLES 3.0 context) use 8-bit sRGB
 * bitmaps that already carry their display-referred look.
 */
internal sealed interface EditorSource {

    val width: Int
    val height: Int

    /** True when texels hold scene-linear light rather than sRGB-encoded values. */
    val isLinear: Boolean

    /** Releases resources owned by this source. */
    fun release()

    class BitmapSource(val full: Bitmap, val proxy: Bitmap) : EditorSource {
        override val width: Int get() = full.width
        override val height: Int get() = full.height
        override val isLinear: Boolean get() = false

        override fun release() {
            if (proxy !== full && !proxy.isRecycled) {
                proxy.recycle()
            }
            if (!full.isRecycled) {
                full.recycle()
            }
        }
    }

    class LinearRawSource(
        val full: LinearRawBuffer,
        val proxy: LinearRawBuffer
    ) : EditorSource {
        override val width: Int get() = full.width
        override val height: Int get() = full.height
        override val isLinear: Boolean get() = true
        val autoBrightGain: Float get() = full.autoBrightGain

        /** Direct buffers are JVM-owned, so there is nothing to free explicitly. */
        override fun release() = Unit
    }
}

/**
 * Decodes the full-preview and drag-proxy textures for [EditorSourceLoader.load].
 */
internal object EditorSourceLoader {

    private const val LINEAR_PREVIEW_MAX_EDGE_PX = 2048

    /** Longest edge of the drag proxy derived from the linear preview. */
    internal const val LINEAR_PROXY_MAX_EDGE_PX = 1024

    /**
     * Fingerprint of everything [load] bakes into its output besides the file content: the linear
     * preview/proxy caps above and the matching 2048/1024px caps in `EditorBitmapLoader`.
     *
     * [RecentEditorSourceCache] includes it in its key, so bump this string whenever one of those
     * caps changes; otherwise a source decoded with the old caps could be reused.
     */
    internal const val DECODE_PROFILE_ID = "preview-2048-linear-2048-proxy-1024-v1"

    /**
     * Loads [sourcePath] into an [EditorSource], preferring a 16-bit linear decode for RAW files
     * when [preferLinear] (a GLES 3.0 context) is available.
     *
     * The proxy is derived from the full linear buffer instead of running a second LibRaw
     * demosaic, which halves RAW open latency without a visible quality change at 1024px.
     */
    fun load(sourcePath: String, isRaw: Boolean, preferLinear: Boolean): EditorSource? {
        if (isRaw && preferLinear) {
            val linear = try {
                RawWrapper.decodeToLinearRgba16F(sourcePath, LINEAR_PREVIEW_MAX_EDGE_PX)
            } catch (oom: OutOfMemoryError) {
                Timber.e(oom, "OOM while decoding the linear RAW preview")
                null
            }
            if (linear != null) {
                return EditorSource.LinearRawSource(
                    full = linear,
                    proxy = linear.downscaledTo(LINEAR_PROXY_MAX_EDGE_PX)
                )
            }
            Timber.w("Linear RAW decode unavailable; falling back to the 8-bit preview path")
        }
        val full = EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw) ?: return null
        return EditorSource.BitmapSource(
            full = full,
            proxy = EditorBitmapLoader.createProxyBitmap(full)
        )
    }
}
