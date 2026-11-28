package cn.devcxl.photosync.editor.render

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.graphics.Bitmap
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * The Pre-Pass A overlay of one graph's `EditGraph.inpaint` list, ready to be applied to a decode.
 *
 * The overlay is the single integration point every render path shares:
 * - the preview canvas composes a copy of the decoded [EditorSource];
 * - the background preview and the preview cache compose the source they hand to the GPU or CPU
 *   renderer;
 * - the full-resolution bake composes the bitmap / linear frame **and** each Tier 2 slice before it
 *   is uploaded to the GPU.
 *
 * Because [InpaintSourceCompositor] resolves every normalized box against the frame it is given,
 * the same overlay pastes the same repaired pixels at 1280px, at 2048px and at 60MP.
 *
 * An overlay is immutable and cheap to keep: only the verified patch payloads are read, and only
 * inside their bounding boxes. Build one with [of]; an empty graph yields `null` so callers keep
 * their zero-cost fast path.
 */
internal class InpaintOverlay(
    private val compositor: InpaintSourceCompositor,
    val patches: List<InpaintPatchRef>
) {

    /** True when the overlay has nothing to blend. */
    val isEmpty: Boolean
        get() = patches.isEmpty()

    /** Blends every patch into [bitmap], which must be a mutable `ARGB_8888` copy. */
    fun applyToBitmap(bitmap: Bitmap): InpaintComposeReport =
        compositor.applyToBitmap(bitmap, patches)

    /**
     * Returns a private mutable copy of [source] with every patch blended in.
     *
     * The caller owns the copy and must recycle it. `null` means the copy could not be allocated or
     * the source is immutable; the caller then renders the unrepaired pixels rather than nothing.
     */
    fun applyToBitmapCopy(source: Bitmap): Bitmap? {
        val copy = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while copying a bitmap for the inpaint overlay")
            null
        } catch (error: IllegalStateException) {
            Timber.e(error, "A bitmap could not be copied for the inpaint overlay")
            null
        } ?: return null
        if (copy.isRecycled) return null
        val report = applyToBitmap(copy)
        Timber.d("Inpaint overlay applied to bitmap: %d patches", report.appliedPatches)
        return copy
    }

    /**
     * Returns a private copy of [source] with every patch blended into its half-float texels.
     *
     * The returned buffer is JVM-owned; nothing has to be released.
     */
    fun applyToLinearCopy(source: LinearRawBuffer): LinearRawBuffer? {
        val pixels = try {
            ByteBuffer
                .allocateDirect(source.width * source.height * LinearRawBuffer.BYTES_PER_PIXEL)
                .order(ByteOrder.nativeOrder())
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while copying a linear frame for the inpaint overlay")
            return null
        }
        val view = source.rgba16fBuffer.duplicate().order(ByteOrder.nativeOrder())
        view.rewind()
        pixels.put(view)
        pixels.rewind()
        val copy = LinearRawBuffer(
            width = source.width,
            height = source.height,
            autoBrightGain = source.autoBrightGain,
            effectiveIso = source.effectiveIso,
            rgba16fBuffer = pixels
        )
        val report = applyToLinearBuffer(copy)
        Timber.d("Inpaint overlay applied to linear frame: %d patches", report.appliedPatches)
        return copy
    }

    /** Blends every patch into a `GL_RGBA16F` [buffer] in place. */
    fun applyToLinearBuffer(buffer: LinearRawBuffer): InpaintComposeReport =
        applyToLinearBuffer(
            buffer = buffer.rgba16fBuffer,
            width = buffer.width,
            height = buffer.height
        )

    /**
     * Blends every patch into an interleaved half-float RGBA [buffer] in place.
     *
     * @param originX Left edge of the buffer inside the pre-crop source, in source pixels.
     * @param originY Top edge of the buffer inside the pre-crop source, in source pixels.
     * @param sourceWidth Pre-crop source width the normalized boxes resolve against.
     * @param sourceHeight Pre-crop source height the normalized boxes resolve against.
     */
    fun applyToLinearBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        originX: Int = 0,
        originY: Int = 0,
        sourceWidth: Int = width,
        sourceHeight: Int = height
    ): InpaintComposeReport = compositor.applyToLinearRgba16f(
        buffer = buffer,
        width = width,
        height = height,
        patches = patches,
        originX = originX,
        originY = originY,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight
    )

    companion object {

        /**
         * Returns the overlay of [graph], or `null` when nothing was erased.
         *
         * @param filesDir Application files directory that owns the AI asset store.
         */
        fun of(filesDir: File, graph: EditGraph): InpaintOverlay? =
            of(AiAssetStore(filesDir), graph)

        /** Returns the overlay of [graph] on [store], or `null` when nothing was erased. */
        fun of(store: AiAssetStore, graph: EditGraph): InpaintOverlay? {
            if (graph.inpaint.isEmpty()) return null
            return InpaintOverlay(InpaintSourceCompositor(store), graph.inpaint)
        }
    }
}
