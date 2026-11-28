package cn.devcxl.photosync.editor.render

import java.io.File

import android.graphics.Bitmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.vision.ClassicSkySegmenter
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SkyMaskCodec
import cn.devcxl.photosync.editor.vision.SubjectImage
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * One sky mask prepared for a photo that has no `photoId` yet.
 *
 * @property mask The decoded mask the preview renderer can use before the row exists.
 * @property payload The grayscale PNG the content-addressed store will move under the new id.
 */
data class TetheredSkyPreparation(
    val mask: SkyMask,
    val payload: ByteArray
)

/**
 * Optional `LiveTetherPreset` support: computes a sky mask for a file that is about to be committed.
 *
 * A `null` return is the documented silent degradation: the caller clears `sky` and applies the
 * traditional grade. Implementations must never throw and must never block USB sync.
 */
interface TetheredSkyPresetPreparer {

    /**
     * Computes the sky mask of [sourcePath] when [graph] carries non-identity `SkyOps`.
     *
     * @return The staged mask and its payload, or `null` to degrade to the traditional grade.
     */
    suspend fun prepare(sourcePath: String, isRaw: Boolean, graph: EditGraph): TetheredSkyPreparation?
}

/**
 * Production preparer backed by the zero-model [ClassicSkySegmenter].
 *
 * The classic analysis is a `1024px` CPU pass with no download and no ORT session, so unlike MODNet
 * it is allowed to run on a `<= 4 GiB` device: the memory peak stays small and the serialized ingest
 * queue keeps it away from a running export. A `< 3 %` mask is treated as "no obvious sky" and
 * degrades silently rather than publishing a destructive replacement.
 */
class ClassicTetheredSkyPresetPreparer(
    private val segmenter: ClassicSkySegmenter = ClassicSkySegmenter()
) : TetheredSkyPresetPreparer {

    override suspend fun prepare(
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): TetheredSkyPreparation? {
        if (graph.sky.isIdentity() || graph.sky.assetType == cn.devcxl.photosync.editor.domain.SkyAssetType.NONE) {
            return null
        }
        val image = withContext(Dispatchers.Default) { decodeProxy(sourcePath, isRaw) } ?: return null
        val segmented = withContext(Dispatchers.Default) { segmenter.segment(image) }
        val result = segmented.getOrElse { error ->
            if (error is OutOfMemoryError) {
                Timber.e(error, "OOM while segmenting the tethered sky")
            } else {
                Timber.w(error, "Tethered sky segmentation failed; applying the traditional grade")
            }
            return null
        }
        if (!result.hasSky) {
            Timber.i("No obvious sky in the tethered photo; applying the traditional grade")
            return null
        }
        val payload = SkyMaskCodec.encode(result.mask) ?: return null
        return TetheredSkyPreparation(mask = result.mask, payload = payload)
    }

    /** Decodes the upright proxy with the same loader the editor uses. */
    private fun decodeProxy(sourcePath: String, isRaw: Boolean): SubjectImage? {
        val bitmap = try {
            if (isRaw) {
                RawWrapper.decodeThumbnailBitmap(sourcePath, PROXY_MAX_EDGE_PX)
            } else {
                EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw = false)
            }
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while decoding the tethered sky proxy")
            null
        } catch (error: RuntimeException) {
            Timber.w(error, "The tethered sky proxy could not be decoded")
            null
        } ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            SubjectImage(bitmap.width, bitmap.height, pixels)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private companion object {
        const val PROXY_MAX_EDGE_PX = 1024
    }
}
