package cn.devcxl.photosync.editor.cloud

import java.io.File
import java.util.UUID

import android.content.Context
import android.graphics.Bitmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * End-to-end preparation, transport and persistence of one BYOK cloud edit.
 *
 * ## Request shapes
 * - [CloudEditMode.WHOLE_IMAGE]: the sanitized Post-Pass A frame alone.
 * - [CloudEditMode.PAINTED_MASK]: the sanitized frame plus a same-size editable PNG mask whose
 *   transparent pixels are the painted region.
 * - [CloudEditMode.SUBJECT_BACKGROUND]: the sanitized frame plus the **inverted** `SubjectMask`
 *   (background editable, foreground preserved).
 *
 * ## Guarantees
 * - no request is built before the one-time consent flag is accepted;
 * - every upload is re-encoded from pixels by [CloudUploadSanitizer], so EXIF, GPS and thumbnails
 *   cannot leave the device;
 * - the provider response is decoded defensively and committed through [AiAssetStore], which
 *   content-addresses the PNG under `filesDir/edits/<photoId>/`;
 * - staged files are deleted on success, failure and cancellation alike.
 *
 * The returned [CloudEditLayerRef] is bound to the current `geometryDigest`, starts fully opaque and
 * records the provider family plus the feather used by the persisted blend mask. All blocking work
 * runs on [Dispatchers.IO]; providers additionally do their own socket work off the caller thread.
 */
class CloudEditService internal constructor(
    private val photoId: Long,
    private val providers: CloudProviderSource,
    private val consent: CloudConsentStore,
    private val uploads: CloudUploadSanitizer,
    private val assets: AiAssetStore,
    private val maskDirectory: File,
    private val codec: CloudLayerCodec = CloudLayerCodec()
) : CloudEditExecutor {

    /** Creates a service backed by the app cache and `filesDir/edits`. */
    constructor(
        context: Context,
        photoId: Long,
        providers: CloudProviderSource
    ) : this(
        photoId = photoId,
        providers = providers,
        consent = CloudConsentStore(context),
        uploads = CloudUploadSanitizer(context),
        assets = AiAssetStore(context.filesDir),
        maskDirectory = File(context.cacheDir, MASK_DIR_NAME)
    )

    override suspend fun execute(
        command: CloudEditCommand,
        onStage: (CloudJobStage) -> Unit
    ): CloudEditOutcome = withContext(Dispatchers.IO) {
        if (!providers.isCloudEnabled()) return@withContext CloudEditOutcome.ProviderUnavailable
        if (!consent.isAccepted()) return@withContext CloudEditOutcome.ConsentRequired
        validate(command)?.let { return@withContext CloudEditOutcome.InvalidInput(it) }
        val provider = providers.createProvider().getOrElse { error ->
            Timber.i("Cloud edit unavailable: %s", error.javaClass.simpleName)
            return@withContext CloudEditOutcome.ProviderUnavailable
        }
        if (command.mode != CloudEditMode.WHOLE_IMAGE &&
            provider.capabilities.maskMode == CloudMaskMode.NONE
        ) {
            return@withContext CloudEditOutcome.InvalidInput(CloudEditInputIssue.MASK_NOT_SUPPORTED)
        }
        val staged = when (val staging = stage(command)) {
            is Staging.Rejected -> return@withContext staging.outcome
            is Staging.Ready -> staging.edit
        }
        val result = try {
            provider.edit(staged.request, onStage)
        } finally {
            // Providers release their uploads in a finally block; repeating it is idempotent and
            // keeps this service safe against a provider that violates that contract.
            staged.request.releaseUploads()
        }
        when (result) {
            is CloudEditResult.Success -> {
                currentCoroutineContext().ensureActive()
                publish(command, staged, provider.kind, result.imageBytes)
            }
            is CloudEditResult.Failure -> CloudEditOutcome.Failed(result.error)
        }
    }

    private fun validate(command: CloudEditCommand): CloudEditInputIssue? {
        if (command.prompt.isBlank()) return CloudEditInputIssue.BLANK_PROMPT
        if (command.source.isRecycled || command.source.width <= 0 || command.source.height <= 0) {
            return CloudEditInputIssue.MISSING_SOURCE
        }
        return when (command.mode) {
            CloudEditMode.WHOLE_IMAGE -> null
            CloudEditMode.PAINTED_MASK -> {
                val mask = command.paintedMask
                if (mask == null || mask.isRecycled) {
                    CloudEditInputIssue.MISSING_PAINTED_MASK
                } else {
                    null
                }
            }
            CloudEditMode.SUBJECT_BACKGROUND -> {
                if (command.subjectMask == null) CloudEditInputIssue.MISSING_SUBJECT_MASK else null
            }
        }
    }

    private fun stage(command: CloudEditCommand): Staging {
        val image = try {
            uploads.createUpload(command.source)
        } catch (error: CloudSanitizationException) {
            Timber.e(error, "Cloud upload sanitization failed")
            return Staging.Rejected(
                CloudEditOutcome.Failed(CloudEditError.Invalid(CloudEditIssue.GENERIC))
            )
        }
        var mask: CloudUploadHandle? = null
        try {
            val featherPx = if (command.mode == CloudEditMode.WHOLE_IMAGE) {
                0.0f
            } else {
                command.featherPx.takeIf { it.isFinite() && it > 0.0f } ?: 0.0f
            }
            mask = when (command.mode) {
                CloudEditMode.WHOLE_IMAGE -> null
                CloudEditMode.PAINTED_MASK -> writeEditableMask(
                    command.paintedMask ?: throw CloudSanitizationException("Missing painted mask"),
                    image.width,
                    image.height
                )
                CloudEditMode.SUBJECT_BACKGROUND -> writeSubjectMask(
                    command.subjectMask ?: throw CloudSanitizationException("Missing subject mask"),
                    image.width,
                    image.height
                )
            }
            val blendMask = buildBlendMask(command, image.width, image.height, featherPx)
            if (mask != null && blendMask == null) {
                throw CloudSanitizationException("Blend mask encoding failed")
            }
            val request = CloudEditRequest(
                image = image,
                mask = mask,
                prompt = command.prompt.trim(),
                negativePrompt = command.negativePrompt?.trim()?.takeIf { it.isNotEmpty() }
            )
            // Ownership of both handles moved into the request before this point.
            return Staging.Ready(StagedEdit(request, blendMask, featherPx))
        } catch (error: CloudSanitizationException) {
            Timber.e(error, "Cloud edit staging failed")
            image.close()
            mask?.close()
            return Staging.Rejected(
                CloudEditOutcome.Failed(CloudEditError.Invalid(CloudEditIssue.GENERIC))
            )
        } catch (error: Throwable) {
            image.close()
            mask?.close()
            throw error
        }
    }

    private fun buildBlendMask(
        command: CloudEditCommand,
        width: Int,
        height: Int,
        featherPx: Float
    ): ByteArray? {
        val coverage = when (command.mode) {
            CloudEditMode.WHOLE_IMAGE -> return null
            CloudEditMode.PAINTED_MASK -> command.paintedMask?.let { mask ->
                CloudBlendMask.paintedCoverage(mask, width, height)
            }
            CloudEditMode.SUBJECT_BACKGROUND -> command.subjectMask?.let { subject ->
                CloudBlendMask.backgroundCoverage(subject, width, height)
            }
        } ?: return null
        val feathered = CloudBlendMask.feather(coverage, width, height, featherPx)
        return CloudBlendMask.encode(feathered, width, height)
    }

    private fun writeEditableMask(mask: Bitmap, width: Int, height: Int): CloudUploadHandle {
        val file = File(maskDirectory, "cloud_mask_${UUID.randomUUID()}.png")
        uploads.writeEditableMaskPng(mask, width, height, file)
        return CloudUploadHandle(file, width, height)
    }

    private fun writeSubjectMask(
        subject: SubjectMask,
        width: Int,
        height: Int
    ): CloudUploadHandle {
        val bitmap = CloudMaskPngEncoder.createBackgroundEditableMaskBitmap(subject, width, height)
        return try {
            val file = File(maskDirectory, "cloud_mask_${UUID.randomUUID()}.png")
            CloudMaskPngEncoder.writePreservingAlphaPng(bitmap, file)
            CloudUploadHandle(file, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun publish(
        command: CloudEditCommand,
        staged: StagedEdit,
        kind: CloudProviderKind,
        imageBytes: ByteArray
    ): CloudEditOutcome {
        val decoded = codec.decodeImage(imageBytes)
            ?: return CloudEditOutcome.Failed(CloudEditError.MalformedResponse(null))
        return try {
            val png = codec.encodePng(decoded)
                ?: return CloudEditOutcome.Failed(CloudEditError.Unknown(null))
            val layerAsset = assets.write(photoId, AiAssetKind.CLOUD_LAYER, png)
                ?: return CloudEditOutcome.Failed(CloudEditError.Unknown(null))
            val maskAsset = staged.blendMaskPng?.let {
                assets.write(photoId, AiAssetKind.CLOUD_MASK, it)
            }
            if (staged.blendMaskPng != null && maskAsset == null) {
                Timber.e("The cloud blend mask could not be persisted")
                return CloudEditOutcome.Failed(CloudEditError.Unknown(null))
            }
            CloudEditOutcome.Applied(
                CloudEditLayerRef(
                    imageRelPath = layerAsset.relativePath,
                    maskRelPath = maskAsset?.relativePath,
                    sha256 = layerAsset.sha256,
                    opacity = DEFAULT_OPACITY,
                    featherPx = staged.featherPx,
                    geometryDigest = CloudGeometryDigest.digest(command.geometry),
                    providerTag = providerTag(kind)
                )
            )
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun providerTag(kind: CloudProviderKind): String = when (kind) {
        CloudProviderKind.OPENAI_COMPATIBLE -> "openai"
        CloudProviderKind.GEMINI -> "gemini"
        CloudProviderKind.COMFY_UI -> "comfyui"
    }

    private sealed interface Staging {
        class Ready(val edit: StagedEdit) : Staging
        class Rejected(val outcome: CloudEditOutcome) : Staging
    }

    private class StagedEdit(
        val request: CloudEditRequest,
        val blendMaskPng: ByteArray?,
        val featherPx: Float
    )

    private companion object {
        /** One upload mask staging folder under `cacheDir`. */
        const val MASK_DIR_NAME = "cloud_masks"

        /** A freshly generated layer is fully applied; the user may lower it with the slider. */
        const val DEFAULT_OPACITY = 100
    }
}
