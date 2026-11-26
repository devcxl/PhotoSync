package cn.devcxl.photosync.editor.render

import java.io.File

import android.graphics.Bitmap
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.vision.SubjectImage
import cn.devcxl.photosync.editor.vision.SubjectMask
import cn.devcxl.photosync.editor.vision.SubjectMaskCodec
import cn.devcxl.photosync.wrapper.LinearRawBuffer
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * Loads the mask referenced by `EditGraph.subject.maskRef` and builds its [SubjectComposite].
 *
 * Everything here is deliberately blocking and safe to call from `Dispatchers.IO`: the editor save,
 * the offscreen background preview and the full-resolution bake all share this one resolution path,
 * so the canvas, the thumbnail and the exported file cannot disagree about the cut-out.
 */
internal object SubjectCompositeLoader {

    /**
     * Resolves the composite referenced by [graph] from an already-decoded [source].
     *
     * @param inpaint Pre-Pass A repair overlay the caller is about to blend into [source]. When it is
     *   present the composite is re-read from a composed copy, so a cut-out never carries the
     *   un-repaired pixels the user erased; the copy is private to this call.
     * @return `null` when the subject is inactive, the mask is unavailable or the source cannot be
     *   converted into a proxy image.
     */
    fun resolve(
        graph: EditGraph,
        source: EditorSource,
        filesDir: File,
        inpaint: InpaintOverlay? = null
    ): SubjectComposite? {
        if (graph.subject.bgMode == SubjectBackgroundMode.ORIGINAL) return null
        val mask = loadMask(filesDir, graph) ?: return null
        if (inpaint == null) return fromSource(source, mask)
        val composed = EditorSourceComposer.compose(source, inpaint)
        return try {
            fromSource(composed ?: source, mask)
        } finally {
            composed?.release()
        }
    }

    /**
     * Resolves the composite for a bake, decoding its own `1024px` proxy from [sourcePath].
     *
     * Used when the bake no longer owns an [EditorSource]; the decode is the same upright preview
     * the editor analyses, so the mask stays aligned with the exported pixels.
     *
     * @param inpaint Pre-Pass A repair overlay the bake already composed into its textures. Passing
     *   it keeps the FMGE initialization from seeding the cut-out with pixels the user erased.
     */
    fun resolveForFile(
        graph: EditGraph,
        sourcePath: String,
        isRaw: Boolean,
        filesDir: File,
        inpaint: InpaintOverlay? = null
    ): SubjectComposite? {
        if (graph.subject.bgMode == SubjectBackgroundMode.ORIGINAL) return null
        val mask = loadMask(filesDir, graph) ?: return null
        return fromFile(sourcePath, isRaw, mask, inpaint)
    }

    /** Loads and verifies the mask of [graph], or `null` when it is absent or tampered with. */
    fun loadMask(filesDir: File, graph: EditGraph): SubjectMask? {
        val maskRef = graph.subject.maskRef.trim()
        if (maskRef.isEmpty()) return null
        val digest = SubjectMaskCodec.digestFromRef(maskRef) ?: return null
        val payload = AiAssetStore(filesDir).read(maskRef, digest) ?: return null
        return SubjectMaskCodec.decode(payload)
    }

    /** Builds the composite from the proxy already held by [source]. */
    fun fromSource(source: EditorSource, mask: SubjectMask): SubjectComposite? = try {
        when (source) {
            is EditorSource.BitmapSource -> fromBitmap(source.proxy, mask)
            is EditorSource.LinearRawSource -> fromLinear(source.proxy, mask)
        }
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "OOM while building the subject composite")
        null
    } catch (error: RuntimeException) {
        Timber.e(error, "The subject composite could not be built")
        null
    }

    /** Builds the composite from an upright 8-bit bitmap. */
    fun fromBitmap(bitmap: Bitmap, mask: SubjectMask): SubjectComposite? {
        if (bitmap.isRecycled) return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return SubjectCompositeFactory.build(SubjectImage(width, height, pixels), mask)
    }

    /** Builds the composite from an upright 16-bit linear buffer. */
    fun fromLinear(buffer: LinearRawBuffer, mask: SubjectMask): SubjectComposite? {
        val pixels = LinearRawPreviewRenderer.renderArgb(
            source = buffer,
            graph = EditGraph(),
            fullResolutionShortEdge = minOf(buffer.width, buffer.height)
        )
        return SubjectCompositeFactory.build(
            SubjectImage(buffer.width, buffer.height, pixels),
            mask
        )
    }

    /** Decodes a `1024px` upright proxy of [sourcePath] and builds its composite. */
    fun fromFile(
        sourcePath: String,
        isRaw: Boolean,
        mask: SubjectMask,
        inpaint: InpaintOverlay? = null
    ): SubjectComposite? = try {
        if (isRaw) {
            val linear = RawWrapper.decodeToLinearRgba16F(sourcePath, PROXY_MAX_EDGE_PX)
            if (linear != null) {
                val composed = inpaint?.applyToLinearCopy(linear) ?: linear
                fromLinear(composed, mask)
            } else {
                val thumbnail = RawWrapper.decodeThumbnailBitmap(sourcePath, PROXY_MAX_EDGE_PX)
                thumbnail?.let { bitmap ->
                    try {
                        fromBitmapWithOverlay(bitmap, mask, inpaint)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
        } else {
            val loaded = EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw = false)
            loaded?.let { bitmap ->
                try {
                    fromBitmapWithOverlay(bitmap, mask, inpaint)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "OOM while decoding the subject composite proxy")
        null
    } catch (error: RuntimeException) {
        Timber.e(error, "The subject composite proxy could not be decoded")
        null
    }

    private fun fromBitmapWithOverlay(
        source: Bitmap,
        mask: SubjectMask,
        inpaint: InpaintOverlay?
    ): SubjectComposite? {
        val composed = inpaint?.applyToBitmapCopy(source) ?: source
        return try {
            fromBitmap(composed, mask)
        } finally {
            if (composed !== source && !composed.isRecycled) composed.recycle()
        }
    }

    private val PROXY_MAX_EDGE_PX = SubjectCompositeFactory.PROXY_MAX_EDGE_PX
}
