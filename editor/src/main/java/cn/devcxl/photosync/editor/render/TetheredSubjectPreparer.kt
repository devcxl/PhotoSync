package cn.devcxl.photosync.editor.render

import java.io.File

import android.content.Context
import android.graphics.Bitmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.ModnetSubjectSegmenter
import cn.devcxl.photosync.editor.vision.OrtSessionManager
import cn.devcxl.photosync.editor.vision.SubjectImage
import cn.devcxl.photosync.editor.vision.SubjectMask
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * One subject mask prepared for a photo that has no `photoId` yet.
 *
 * The payload is the raw PNG the content-addressed store will persist under the new id; the mask is
 * the decoded form the preview renderer needs before that commit.
 */
data class TetheredSubjectPreparation(
    val mask: SubjectMask,
    val payload: ByteArray
)

/**
 * Optional `LiveTetherPreset` / quick-preset support: computes a subject mask for a file that is
 * about to be committed.
 *
 * A `null` return is the documented silent degradation: the caller strips `subject` and applies the
 * traditional grade only. Implementations must never throw for a missing model or a low-memory
 * device, and must never block the USB sync beyond one serialized inference.
 */
interface TetheredSubjectPresetPreparer {

    /**
     * Computes the subject mask of [sourcePath] when [graph] carries non-identity `SubjectOps`.
     *
     * @return The staged mask and its payload, or `null` to degrade to the traditional grade.
     */
    suspend fun prepare(sourcePath: String, isRaw: Boolean, graph: EditGraph): TetheredSubjectPreparation?
}

/**
 * Production preparer backed by MODNet grown through the shared [OrtSessionManager].
 *
 * Degradation rules mirror the safety matrix:
 * - `DeviceMemoryTier.LOW` (`<= 4 GiB`) never imports a model during sync, because the ingest queue
 *   must not compete with the camera transport for memory;
 * - a missing `MODNET_PORTRAIT` artifact is equally silent;
 * - any inference, decode or OOM failure is logged and yields `null`, so the caller imports the
 *   traditional grade instead of dropping the photo.
 */
class ModnetTetheredSubjectPresetPreparer(
    filesDir: File,
    private val sessions: OrtSessionManager,
    private val tier: DeviceMemoryTier
) : TetheredSubjectPresetPreparer {

    /** Production constructor: classifies the device from total RAM, honoring the user override. */
    constructor(
        context: Context,
        filesDir: File,
        sessions: OrtSessionManager
    ) : this(
        filesDir,
        sessions,
        EditorSettings(context).resolveMemoryTier(context)
    )

    private val rootDir: File = filesDir
    private val downloads = AiModelDownloadManager(File(rootDir, AiModelCatalog.MODELS_DIR_NAME))
    private val spec = AiModelCatalog.MODNET_PORTRAIT

    override suspend fun prepare(
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): TetheredSubjectPreparation? {
        if (!graph.subject.requiresMaskProcessing()) return null
        if (tier == DeviceMemoryTier.LOW) {
            Timber.i("Subject preset skipped on a low-memory device; applying the traditional grade")
            return null
        }
        if (!downloads.isInstalled(spec)) {
            Timber.i("MODNet is not installed; the tethered preset applies the traditional grade")
            return null
        }
        val image = withContext(Dispatchers.Default) { decodeProxy(sourcePath, isRaw) } ?: return null
        val segmenter = ModnetSubjectSegmenter(sessions, AiAssetStore(rootDir))
        val segmented = withContext(Dispatchers.Default) {
            segmenter.segment(image, downloads.modelFile(spec))
        }
        val mask = segmented.getOrElse { error ->
            Timber.w(error, "Tethered subject inference failed; applying the traditional grade")
            return null
        }
        val payload = segmenter.encodeMask(mask) ?: return null
        return TetheredSubjectPreparation(mask = mask, payload = payload)
    }

    /**
     * Decodes the upright preview MODNet analyses, capped at the model proxy size.
     *
     * Uses the same decoder as the editor and the cut-out loader, so a preset mask and an
     * editor-triggered mask describe the same pixels.
     */
    private fun decodeProxy(sourcePath: String, isRaw: Boolean): SubjectImage? {
        val bitmap = try {
            if (isRaw) {
                RawWrapper.decodeThumbnailBitmap(sourcePath, PROXY_MAX_EDGE_PX)
            } else {
                EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw = false)
            }
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while decoding the tethered subject proxy")
            null
        } catch (error: RuntimeException) {
            Timber.w(error, "The tethered subject proxy could not be decoded")
            null
        } ?: return null
        return try {
            bitmap.toSubjectImage()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun Bitmap.toSubjectImage(): SubjectImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return SubjectImage(width, height, pixels)
    }

    private companion object {
        const val PROXY_MAX_EDGE_PX = 1024
    }
}
