package cn.devcxl.photosync.editor.cloud

import java.io.Closeable
import java.io.File
import java.io.IOException

import timber.log.Timber

/** How a cloud provider applies a painted mask. */
enum class CloudMaskMode {
    /**
     * The provider accepts a same-size PNG whose transparent (`alpha = 0`) pixels are the editable
     * region. The mask produced by [CloudMaskPngEncoder] uses exactly this convention.
     */
    HARD,

    /**
     * The provider has no binary mask channel. The mask only becomes a "keep everything else
     * unchanged" prompt constraint, so the edit boundary is advisory rather than enforced.
     */
    SOFT,

    /** The provider cannot use a mask at all; the edit always covers the whole image. */
    NONE
}

/** Provider families supported by the BYOK cloud engine. */
enum class CloudProviderKind {
    /** An OpenAI-compatible `POST /v1/images/edits` endpoint (OpenAI, Azure, gateways). */
    OPENAI_COMPATIBLE,

    /** The Gemini Interactions API (`/v1beta/interactions`). */
    GEMINI,

    /** A self-hosted ComfyUI server driven through its raw HTTP routes. */
    COMFY_UI
}

/**
 * Static capabilities a provider advertises to the editor UI.
 *
 * The settings page consumes [CloudProviderCapabilities.maskMode] to decide whether an edit form
 * offers a painted mask; the upload/size/seed fields are gated by the same record. Every provider
 * fills it at construction so the future cloud-edit panel never has to guess a provider's contract
 * from its class name.
 *
 * @property maskMode Whether a painted mask is enforced, advisory or ignored.
 * @property maxImages Maximum number of input images accepted in one request.
 * @property maxUploadBytes Maximum bytes accepted for a single uploaded image.
 * @property supportsNegativePrompt True when the provider understands a negative prompt.
 * @property supportsSeed True when a deterministic seed can be supplied.
 * @property blocking True for a synchronous request, false when the provider polls a queue.
 */
data class CloudProviderCapabilities(
    val maskMode: CloudMaskMode,
    val maxImages: Int,
    val maxUploadBytes: Long,
    val supportsNegativePrompt: Boolean,
    val supportsSeed: Boolean,
    val blocking: Boolean
)

/** Lifecycle stages reported by [CloudEditProvider.edit] through its progress callback. */
enum class CloudJobStage {
    /** The sanitized upload is being sent (and a job is being created). */
    SUBMITTING,

    /** The provider accepted the job and it is waiting for a worker. */
    QUEUED,

    /** A worker is generating the result. */
    RUNNING,

    /** The finished image is being downloaded. */
    DOWNLOADING
}

/**
 * One cloud edit request.
 *
 * [image] and [mask] are staged, metadata-free uploads owned by the request. Every provider closes
 * them in a `finally` block, so the caller must not reuse them after [CloudEditProvider.edit]
 * returns — including when the call fails or is cancelled. When a mask is supplied it must be a PNG
 * at exactly [SanitizedUploadHandle.width] x [SanitizedUploadHandle.height] of [image], with
 * `alpha = 0` on the editable pixels (see [CloudMaskPngEncoder]).
 *
 * @property prompt User instruction. Providers reject a blank prompt before any network call.
 * @property negativePrompt Optional negative prompt; ignored by providers without support.
 * @property model Optional per-request model override. Providers without a model field ignore it.
 * @property size Optional provider-specific size such as `1024x1024` or `3:2`.
 * @property seed Optional deterministic seed; ignored by providers without support.
 */
class CloudEditRequest(
    val image: SanitizedUploadHandle,
    val mask: SanitizedUploadHandle? = null,
    val prompt: String,
    val negativePrompt: String? = null,
    val model: String? = null,
    val size: String? = null,
    val seed: Long? = null
) {

    /** Closes both staged uploads and deletes their temporary files. Safe to call repeatedly. */
    fun releaseUploads() {
        closeQuietly(image)
        mask?.let(::closeQuietly)
    }

    private fun closeQuietly(handle: Closeable) {
        try {
            handle.close()
        } catch (e: IOException) {
            Timber.w("Could not delete a staged cloud upload (%s)", e.javaClass.simpleName)
        }
    }
}

/**
 * A staged upload owned by a [CloudEditRequest] rather than by [CloudUploadSanitizer].
 *
 * This is the seam the editor's cloud-edit panel will use to hand an editable mask PNG (written by
 * [CloudUploadSanitizer.writeEditableMaskPng]) to a request, so the mask shares the photo's
 * delete-on-close lifecycle. The settings-only slice does not construct it yet; the provider tests
 * already reuse the same class so their fixtures cannot drift from the future integration.
 */
class CloudUploadHandle(
    private val backingFile: File,
    override val width: Int,
    override val height: Int
) : SanitizedUploadHandle {

    @Volatile
    private var closed = false

    override val file: File
        get() = backingFile

    override fun close() {
        if (closed) return
        closed = true
        if (!backingFile.delete() && backingFile.exists()) {
            Timber.w("Could not delete a staged cloud mask after the request finished")
        }
    }
}

/** Result of [CloudEditProvider.edit]. */
sealed interface CloudEditResult {
    /**
     * The provider returned a finished image.
     *
     * @property imageBytes Decoded image bytes; never a base64 payload or a JSON envelope.
     * @property mimeType Image MIME type, for example `image/png` or `image/jpeg`.
     */
    class Success(val imageBytes: ByteArray, val mimeType: String) : CloudEditResult

    /** The edit failed with a classified, user-presentable error. */
    data class Failure(val error: CloudEditError) : CloudEditResult
}

/**
 * Machine-readable reason behind a [CloudEditError.Invalid].
 *
 * The domain layer never carries user-facing English text: the settings page maps these codes to
 * localized strings, so a provider detail can change without leaking a debug sentence into the UI.
 */
enum class CloudEditIssue {

    /** No specific local reason; the provider may have supplied a sanitized detail. */
    GENERIC,

    /** The provider profile has no model name. */
    NO_MODEL_CONFIGURED,

    /** The user prompt is blank. */
    EMPTY_PROMPT,

    /** The user prompt exceeds the provider limit. */
    PROMPT_TOO_LONG,

    /** The staged photo upload disappeared before the request could send it. */
    STAGED_UPLOAD_MISSING,

    /** The staged mask upload disappeared before the request could send it. */
    STAGED_MASK_MISSING,

    /** The uploaded mask dimensions differ from the photo. */
    MASK_SIZE_MISMATCH,

    /** ComfyUI accepted `/upload/mask` but `/view` does not return the uploaded mask. */
    MASK_NOT_STORED,

    /** A server-returned path failed the traversal/name guard. */
    UNSAFE_FILE_PATH,

    /** The user workflow JSON is empty, malformed or incomplete. */
    WORKFLOW_INVALID,

    /** ComfyUI refused the workflow with node errors. */
    WORKFLOW_REJECTED,

    /** ComfyUI reported that the workflow execution failed. */
    WORKFLOW_FAILED,

    /** The configured workflow has no mask input node. */
    WORKFLOW_NO_MASK_NODE,

    /** The configured workflow misses its image or prompt node id. */
    WORKFLOW_NODE_MISSING,

    /** A queue id is not a safe ComfyUI prompt id. */
    PROMPT_ID_INVALID,

    /** Gemini ended the interaction in a failed state. */
    INTERACTION_FAILED,

    /** A provider response was not valid JSON. */
    RESPONSE_NOT_JSON,

    /** A provider response contained no image block. */
    RESPONSE_NO_IMAGE,

    /** A base64 image payload could not be decoded. */
    RESPONSE_BAD_BASE64,

    /** A provider returned bytes that are not a supported image. */
    RESPONSE_NOT_IMAGE
}

/**
 * Classified cloud failure. Messages never contain API keys, authorization headers or file paths.
 *
 * Every error except [RateLimited] and [Server] is terminal: the providers never retry a POST
 * automatically, because OpenAI and Gemini may already have billed a timed-out request.
 */
sealed class CloudEditError {

    /** 401/403: the key or reverse-proxy credential was rejected. */
    data object Auth : CloudEditError()

    /**
     * 400/422 or a locally invalid request.
     *
     * @param issue Typed local reason the settings page localizes.
     * @param providerMessage Sanitized provider detail, when the response body carried one.
     */
    data class Invalid(
        val issue: CloudEditIssue = CloudEditIssue.GENERIC,
        val providerMessage: String? = null
    ) : CloudEditError()

    /** 413, a local upload above the provider limit, or a response above the 32 MiB ceiling. */
    data object TooLarge : CloudEditError()

    /** 429. [retryAfterSeconds] comes from `Retry-After` when the provider sent one. */
    data class RateLimited(val retryAfterSeconds: Long?) : CloudEditError()

    /** 5xx from the provider. */
    data class Server(val status: Int) : CloudEditError()

    /**
     * A socket connect/read timeout. The request is never automatically repeated.
     *
     * @property resumableJobId ComfyUI queue id that can be passed to
     *   [ComfyUiEditProvider.resume]; `null` for providers without a queue.
     */
    data class Timeout(val resumableJobId: String? = null) : CloudEditError()

    /** The surrounding coroutine was cancelled; uploads were already deleted. */
    data object Cancelled : CloudEditError()

    /** [CloudNetworkPolicy] rejected the endpoint before a socket was opened. */
    data class EndpointRejected(val reason: CloudPolicyRejection) : CloudEditError()

    /** The endpoint answered with a redirect. Redirects are never followed for authenticated calls. */
    data class RedirectRejected(val status: Int) : CloudEditError()

    /** DNS, TCP or TLS failure. [cause] is for logs only and is never shown verbatim to the user. */
    data class Network(val cause: IOException?) : CloudEditError()

    /** The response JSON did not match the provider contract. */
    data class MalformedResponse(val message: String?) : CloudEditError()

    /** Unexpected failure; [cause] is logged but never surfaced with secrets or paths. */
    data class Unknown(val cause: Throwable?) : CloudEditError()
}

/** Result of [CloudEditProvider.testConnection]. */
sealed interface CloudConnectionResult {
    /** The endpoint answered an authenticated probe. */
    data class Reachable(val latencyMs: Long) : CloudConnectionResult

    /** The probe failed with a classified error. */
    data class Failed(val error: CloudEditError) : CloudConnectionResult
}

/**
 * One BYOK cloud editing backend.
 *
 * The provider owns the whole `submit -> poll -> fetch` lifecycle, so the UI only awaits [edit] and
 * observes [CloudJobStage] callbacks. Implementations must:
 *
 * - validate URLs through [CloudNetworkPolicy] before opening a socket;
 * - never retry a timed-out POST automatically;
 * - never log keys, authorization headers or original photo paths;
 * - close both staged uploads in a `finally` block and honour coroutine cancellation.
 *
 * All methods perform network I/O and **must be called off the main thread**.
 */
interface CloudEditProvider {

    /** Provider family, used by the settings UI to pick field visibility. */
    val kind: CloudProviderKind

    /** Static capabilities of this provider. */
    val capabilities: CloudProviderCapabilities

    /**
     * Runs one edit.
     *
     * @param request Uploads and prompt for this edit. Uploads are released before this returns.
     * @param onStage Progress callback invoked from the provider worker thread.
     * @return [CloudEditResult.Success] with decoded image bytes or a classified failure.
     * @throws kotlinx.coroutines.CancellationException when the calling coroutine is cancelled; the
     *   staged uploads are deleted before it propagates.
     */
    suspend fun edit(
        request: CloudEditRequest,
        onStage: (CloudJobStage) -> Unit = {}
    ): CloudEditResult

    /**
     * Probes the configured endpoint with an authenticated, read-only request.
     *
     * @return [CloudConnectionResult.Reachable] with the round-trip latency in milliseconds, or a
     *   classified failure.
     */
    suspend fun testConnection(): CloudConnectionResult
}
