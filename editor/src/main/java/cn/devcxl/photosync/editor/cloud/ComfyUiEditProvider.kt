package cn.devcxl.photosync.editor.cloud

import java.io.IOException
import java.util.UUID

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

import org.json.JSONObject

/**
 * Self-hosted ComfyUI provider driven through the raw HTTP API.
 *
 * Lifecycle: `/upload/image -> /upload/mask -> /prompt -> /history/{id} -> /view`.
 *
 * Safety properties (see `docs/research/2026-11-26-byok-cloud-edit-protocols-and-security.md`):
 * - `/upload/mask` can answer 200 without writing anything when `original_ref` is stale, so the
 *   uploaded mask is confirmed through `/view` before a prompt is submitted;
 * - every file name, subfolder and storage type returned by the server is checked against
 *   [ComfyPathGuard], which blocks `..`, separators and control characters;
 * - a polling timeout asks `/history/{id}` one last time instead of resubmitting the workflow;
 *   [resume] can continue a timed-out job later without ever creating a second queue entry;
 * - uploaded objects stay on the ComfyUI server (the raw API has no delete route); the local
 *   sanitized files are always deleted by the request lifecycle.
 *
 * All methods perform network I/O and **must be called off the main thread**.
 *
 * @param binding User workflow plus the node ids the app may inject values into.
 * @param pollIntervalMs Delay between `/history/{id}` polls.
 * @param pollTimeoutMs Total polling budget before [CloudEditError.Timeout] is returned.
 */
class ComfyUiEditProvider(
    baseUrl: String,
    private val binding: ComfyUiWorkflowBinding,
    credential: ByokCredential,
    networkPolicy: CloudNetworkPolicy,
    connectTimeoutMs: Int = CloudNetworkPolicy.DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS
) : CloudEditProvider {

    private val client = ComfyUiHttpClient(
        baseUrl = baseUrl,
        credential = credential,
        networkPolicy = networkPolicy,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    )

    override val kind: CloudProviderKind = CloudProviderKind.COMFY_UI

    override val capabilities = CloudProviderCapabilities(
        maskMode = if (binding.supportsMask) CloudMaskMode.HARD else CloudMaskMode.NONE,
        maxImages = 1,
        maxUploadBytes = MAX_UPLOAD_BYTES,
        supportsNegativePrompt = binding.negativePromptNodeId.isNotBlank(),
        supportsSeed = binding.seedNodeId.isNotBlank(),
        blocking = false
    )

    override suspend fun edit(
        request: CloudEditRequest,
        onStage: (CloudJobStage) -> Unit
    ): CloudEditResult = try {
        runWorkflow(request, onStage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudHttpFailure) {
        CloudEditResult.Failure(e.error)
    } catch (e: IOException) {
        CloudEditResult.Failure(e.toCloudEditError())
    } finally {
        request.releaseUploads()
    }

    /**
     * Continues a previously submitted job without creating a new one.
     *
     * Call this after [CloudEditError.Timeout] instead of submitting the same workflow again: the
     * queue entry may still be running or already finished. The id is carried by
     * [CloudEditError.Timeout.resumableJobId].
     */
    suspend fun resume(promptId: String): CloudEditResult = try {
        if (!ComfyPathGuard.isSafePromptId(promptId)) {
            CloudEditResult.Failure(CloudEditError.Invalid(CloudEditIssue.PROMPT_ID_INVALID))
        } else {
            val entry = findFinishedEntry(promptId)
            if (entry == null) {
                CloudEditResult.Failure(CloudEditError.Timeout(promptId))
            } else {
                fetchOutput(entry)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudHttpFailure) {
        CloudEditResult.Failure(e.error)
    } catch (e: IOException) {
        CloudEditResult.Failure(e.toCloudEditError())
    }

    override suspend fun testConnection(): CloudConnectionResult {
        val startedAt = System.nanoTime()
        val error = client.probeSystemStats()
        return if (error == null) {
            CloudConnectionResult.Reachable((System.nanoTime() - startedAt) / NANOS_PER_MILLI)
        } else {
            CloudConnectionResult.Failed(error)
        }
    }

    private suspend fun runWorkflow(
        request: CloudEditRequest,
        onStage: (CloudJobStage) -> Unit
    ): CloudEditResult {
        val prompt = request.prompt.trim()
        if (prompt.isEmpty()) {
            return CloudEditResult.Failure(CloudEditError.Invalid(CloudEditIssue.EMPTY_PROMPT))
        }
        validateBinding()?.let { return CloudEditResult.Failure(it) }
        val image = request.image.file
        if (!image.isFile || !image.canRead()) {
            return CloudEditResult.Failure(CloudEditError.Invalid(CloudEditIssue.STAGED_UPLOAD_MISSING))
        }
        if (image.length() > MAX_UPLOAD_BYTES) {
            return CloudEditResult.Failure(CloudEditError.TooLarge)
        }
        if (request.mask != null && !binding.supportsMask) {
            return CloudEditResult.Failure(
                CloudEditError.Invalid(CloudEditIssue.WORKFLOW_NO_MASK_NODE)
            )
        }
        onStage(CloudJobStage.SUBMITTING)
        val imageRef = client.uploadImage(image, IMAGE_UPLOAD_NAME)
        val maskRef = uploadMask(request, imageRef)
        val workflow = try {
            ComfyWorkflowInjector.inject(
                binding = binding,
                imageRef = imageRef.annotation,
                maskRef = maskRef?.annotation,
                prompt = prompt,
                negativePrompt = request.negativePrompt,
                seed = request.seed
            )
        } catch (e: IllegalArgumentException) {
            return CloudEditResult.Failure(CloudEditError.Invalid(
                issue = CloudEditIssue.WORKFLOW_INVALID,
                providerMessage = e.message
            ))
        }
        val submittedId = client.submitPrompt(workflow, UUID.randomUUID().toString())
        val entry = awaitFinishedEntry(submittedId, onStage)
            ?: return CloudEditResult.Failure(CloudEditError.Timeout(submittedId))
        return fetchOutput(entry, onStage)
    }

    private suspend fun uploadMask(
        request: CloudEditRequest,
        imageRef: ComfyUploadRef
    ): ComfyUploadRef? {
        val mask = request.mask ?: return null
        if (!mask.file.isFile || !mask.file.canRead()) {
            throw CloudHttpFailure(CloudEditError.Invalid(CloudEditIssue.STAGED_MASK_MISSING))
        }
        if (mask.file.length() > MAX_UPLOAD_BYTES) {
            throw CloudHttpFailure(CloudEditError.TooLarge)
        }
        val ref = client.uploadMask(mask.file, MASK_UPLOAD_NAME, imageRef)
        client.verifyUploadedFile(ref, expectedWidth = mask.width, expectedHeight = mask.height)
        return ref
    }

    private suspend fun awaitFinishedEntry(
        promptId: String,
        onStage: (CloudJobStage) -> Unit
    ): JSONObject? {
        onStage(CloudJobStage.QUEUED)
        val deadline = System.nanoTime() + pollTimeoutMs * NANOS_PER_MILLI
        var firstPoll = true
        while (System.nanoTime() < deadline) {
            if (!firstPoll) delay(pollIntervalMs)
            firstPoll = false
            onStage(CloudJobStage.RUNNING)
            val entry = findFinishedEntry(promptId)
            if (entry != null) return entry
        }
        // Never resubmit blindly: the queue entry may have finished while the deadline expired.
        onStage(CloudJobStage.RUNNING)
        return findFinishedEntry(promptId)
    }

    /** Returns the finished history entry, `null` while it is pending, and throws on failure. */
    private suspend fun findFinishedEntry(promptId: String): JSONObject? {
        val history = client.fetchHistory(promptId)
        val entry = history.optJSONObject(promptId) ?: return null
        val status = entry.optJSONObject("status")
        val statusStr = status?.stringOrNull("status_str")
        if (statusStr == STATUS_ERROR) {
            throw CloudHttpFailure(CloudEditError.Invalid(CloudEditIssue.WORKFLOW_FAILED))
        }
        if (status?.optBoolean("completed") == true) return entry
        if (entry.optJSONObject("outputs")?.length() ?: 0 > 0) return entry
        return null
    }

    private suspend fun fetchOutput(
        entry: JSONObject,
        onStage: (CloudJobStage) -> Unit = {}
    ): CloudEditResult {
        val ref = selectOutput(entry)
            ?: return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The workflow produced no image")
            )
        onStage(CloudJobStage.DOWNLOADING)
        val bytes = client.downloadOutput(ref)
        return if (looksLikeImage(bytes)) {
            CloudEditResult.Success(bytes, mimeTypeFromMagic(bytes))
        } else {
            CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The provider returned a non-image payload")
            )
        }
    }

    /** Picks the first `type == output` image, falling back to a `temp` preview. */
    private fun selectOutput(entry: JSONObject): ComfyImageRef? {
        val outputs = entry.optJSONObject("outputs") ?: return null
        var fallback: ComfyImageRef? = null
        val nodeIds = outputs.keys()
        while (nodeIds.hasNext()) {
            val images = outputs.optJSONObject(nodeIds.next())?.optJSONArray("images") ?: continue
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                val ref = imageRef(image)
                if (ref.type == OUTPUT_TYPE) return ref
                if (fallback == null) fallback = ref
            }
        }
        return fallback
    }

    private fun imageRef(image: JSONObject): ComfyImageRef {
        val filename = image.stringOrNull("filename").orEmpty()
        val subfolder = image.stringOrNull("subfolder").orEmpty()
        val type = image.stringOrNull("type")?.takeIf { it.isNotBlank() } ?: OUTPUT_TYPE
        if (!ComfyPathGuard.isSafeFileName(filename) ||
            !ComfyPathGuard.isSafeSubfolder(subfolder) ||
            !ComfyPathGuard.isSafeType(type)
        ) {
            throw CloudHttpFailure(
                CloudEditError.MalformedResponse("ComfyUI returned an unsafe output path")
            )
        }
        return ComfyImageRef(filename, subfolder, type)
    }

    private fun validateBinding(): CloudEditError? = when {
        binding.workflowJson.isBlank() -> CloudEditError.Invalid(CloudEditIssue.WORKFLOW_INVALID)
        binding.imageNodeId.isBlank() -> CloudEditError.Invalid(CloudEditIssue.WORKFLOW_NODE_MISSING)
        binding.promptNodeId.isBlank() -> CloudEditError.Invalid(CloudEditIssue.WORKFLOW_NODE_MISSING)
        else -> null
    }

    private companion object {
        const val IMAGE_UPLOAD_NAME = "upload.jpg"
        const val MASK_UPLOAD_NAME = "mask.png"
        const val OUTPUT_TYPE = "output"
        const val STATUS_ERROR = "error"
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_POLL_TIMEOUT_MS = 600_000L
    }
}
