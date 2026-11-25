package cn.devcxl.photosync.editor.cloud

import java.io.IOException
import java.net.HttpURLConnection

import android.util.Base64

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Gemini Interactions API provider (`POST {baseUrl}/v1beta/interactions`).
 *
 * Contract highlights (see `docs/research/2026-11-26-byok-cloud-edit-protocols-and-security.md`):
 * - authentication uses `x-goog-api-key` plus the pinned `Api-Revision` header;
 * - `store` is always `false`, so the interaction is not retained server-side;
 * - `background` is optional; when enabled the provider polls `GET /interactions/{id}`;
 * - the response image is read from `steps[].content[]` with `type == "image"` (the raw REST
 *   contract), never from the SDK-only `output_image` helper;
 * - there is **no binary mask** in this API, so the capability is [CloudMaskMode.SOFT] and a
 *   painted mask only becomes an extra "keep everything else unchanged" prompt constraint;
 * - a read timeout returns [CloudEditError.Timeout] after exactly one POST.
 *
 * All methods perform network I/O and **must be called off the main thread**.
 *
 * @param background True to submit with `"background": true` and poll the interaction.
 * @param pollIntervalMs Delay between `GET /interactions/{id}` polls.
 * @param pollTimeoutMs Total polling budget before [CloudEditError.Timeout] is returned.
 */
class GeminiEditProvider(
    private val baseUrl: String,
    private val configuredModel: String,
    private val credential: ByokCredential,
    private val networkPolicy: CloudNetworkPolicy,
    private val background: Boolean = true,
    private val connectTimeoutMs: Int = CloudNetworkPolicy.DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS
) : CloudEditProvider {

    override val kind: CloudProviderKind = CloudProviderKind.GEMINI

    override val capabilities = CloudProviderCapabilities(
        maskMode = CloudMaskMode.SOFT,
        maxImages = 1,
        maxUploadBytes = MAX_REQUEST_BYTES,
        supportsNegativePrompt = false,
        supportsSeed = false,
        blocking = !background
    )

    override suspend fun edit(
        request: CloudEditRequest,
        onStage: (CloudJobStage) -> Unit
    ): CloudEditResult = try {
        submit(request, onStage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudHttpFailure) {
        CloudEditResult.Failure(e.error)
    } catch (e: IOException) {
        CloudEditResult.Failure(e.toCloudEditError())
    } catch (e: JSONException) {
        CloudEditResult.Failure(CloudEditError.MalformedResponse("The response was not valid JSON"))
    } finally {
        request.releaseUploads()
    }

    override suspend fun testConnection(): CloudConnectionResult {
        val endpoint = resolveEndpoint(MODELS_PATH) ?: return CloudConnectionResult.Failed(
            CloudEditError.EndpointRejected(CloudPolicyRejection.MALFORMED_URL)
        )
        if (endpoint is EndpointResolution.Rejected) {
            return CloudConnectionResult.Failed(CloudEditError.EndpointRejected(endpoint.reason))
        }
        val allowed = (endpoint as EndpointResolution.Allowed).check
        val startedAt = System.nanoTime()
        val error = probeStatus(networkPolicy, allowed, authHeaders(), connectTimeoutMs, readTimeoutMs)
        return if (error == null) {
            CloudConnectionResult.Reachable((System.nanoTime() - startedAt) / NANOS_PER_MILLI)
        } else {
            CloudConnectionResult.Failed(error)
        }
    }

    private suspend fun submit(
        request: CloudEditRequest,
        onStage: (CloudJobStage) -> Unit
    ): CloudEditResult {
        val model = (request.model ?: configuredModel).trim()
        if (model.isEmpty()) {
            return CloudEditResult.Failure(CloudEditError.Invalid("No model is configured"))
        }
        val prompt = request.prompt.trim()
        if (prompt.isEmpty()) {
            return CloudEditResult.Failure(CloudEditError.Invalid("The prompt is empty"))
        }
        val image = request.image.file
        if (!image.isFile || !image.canRead()) {
            return CloudEditResult.Failure(CloudEditError.Invalid("The staged upload is missing"))
        }
        if (image.length() > MAX_REQUEST_BYTES) {
            return CloudEditResult.Failure(CloudEditError.TooLarge)
        }
        val encodedImage = Base64.encodeToString(image.readBytes(), Base64.NO_WRAP)
        if (estimatedRequestBytes(encodedImage, prompt) > MAX_REQUEST_BYTES) {
            return CloudEditResult.Failure(CloudEditError.TooLarge)
        }
        val endpoint = resolveEndpoint(INTERACTIONS_PATH) ?: return CloudEditResult.Failure(
            CloudEditError.EndpointRejected(CloudPolicyRejection.MALFORMED_URL)
        )
        if (endpoint is EndpointResolution.Rejected) {
            return CloudEditResult.Failure(CloudEditError.EndpointRejected(endpoint.reason))
        }
        val payload = buildPayload(request, model, prompt, encodedImage)
        onStage(CloudJobStage.SUBMITTING)
        val interaction = postJson((endpoint as EndpointResolution.Allowed).check, payload)
        val status = GeminiInteractionParser.status(interaction)
        if (status == GeminiInteractionParser.STATUS_COMPLETED) {
            return GeminiInteractionParser.extractImage(interaction)
        }
        if (GeminiInteractionParser.isTerminalFailure(status)) {
            return CloudEditResult.Failure(
                CloudEditError.Invalid(GeminiInteractionParser.failureSummary(status, interaction))
            )
        }
        val interactionId = interaction.optString("id").takeIf { it.isNotBlank() }
            ?: return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The interaction response had no id")
            )
        return poll(interactionId, onStage)
    }

    private fun buildPayload(
        request: CloudEditRequest,
        model: String,
        prompt: String,
        encodedImage: String
    ): JSONObject {
        val text = if (request.mask != null) "$prompt\n\n$SOFT_MASK_INSTRUCTION" else prompt
        val input = JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(
                JSONObject()
                    .put("type", "image")
                    .put("mime_type", "image/jpeg")
                    .put("data", encodedImage)
            )
        val payload = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("store", false)
            .put("response_format", JSONObject().put("type", "image").put("mime_type", "image/jpeg"))
        if (background) payload.put("background", true)
        aspectRatio(request.size)?.let { aspect ->
            payload.getJSONObject("response_format").put("aspect_ratio", aspect)
        }
        return payload
    }

    private suspend fun postJson(
        endpoint: CloudEndpointCheck.Allowed,
        payload: JSONObject
    ): JSONObject {
        val body = payload.toString().toByteArray()
        val response = withPinnedConnection(
            policy = networkPolicy,
            endpoint = endpoint,
            headers = authHeaders().plus("Content-Type" to "application/json"),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        ) { connection ->
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            readJsonResponse(connection)
        }
        return parseJsonObject(response)
            ?: throw CloudHttpFailure(
                CloudEditError.MalformedResponse("The interaction response was not JSON")
            )
    }

    private suspend fun poll(
        interactionId: String,
        onStage: (CloudJobStage) -> Unit
    ): CloudEditResult {
        val deadline = System.nanoTime() + pollTimeoutMs * NANOS_PER_MILLI
        onStage(CloudJobStage.QUEUED)
        while (true) {
            if (System.nanoTime() >= deadline) return CloudEditResult.Failure(CloudEditError.Timeout())
            delay(pollIntervalMs)
            onStage(CloudJobStage.RUNNING)
            val interaction = getInteraction(interactionId)
            val status = GeminiInteractionParser.status(interaction)
            if (status == GeminiInteractionParser.STATUS_COMPLETED) {
                return GeminiInteractionParser.extractImage(interaction)
            }
            if (GeminiInteractionParser.isTerminalFailure(status)) {
                return CloudEditResult.Failure(
                    CloudEditError.Invalid(
                        GeminiInteractionParser.failureSummary(status, interaction)
                    )
                )
            }
        }
    }

    private suspend fun getInteraction(interactionId: String): JSONObject {
        val endpoint = resolveEndpoint("$INTERACTIONS_PATH/${encodeQueryValue(interactionId)}")
            ?: throw CloudHttpFailure(
                CloudEditError.EndpointRejected(CloudPolicyRejection.MALFORMED_URL)
            )
        if (endpoint is EndpointResolution.Rejected) {
            throw CloudHttpFailure(CloudEditError.EndpointRejected(endpoint.reason))
        }
        val response = withPinnedConnection(
            policy = networkPolicy,
            endpoint = (endpoint as EndpointResolution.Allowed).check,
            headers = authHeaders(),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        ) { connection ->
            connection.requestMethod = "GET"
            readJsonResponse(connection)
        }
        return parseJsonObject(response)
            ?: throw CloudHttpFailure(
                CloudEditError.MalformedResponse("The interaction response was not JSON")
            )
    }

    private fun readJsonResponse(connection: HttpURLConnection): ByteArray {
        val status = networkPolicy.requireNonRedirectResponse(connection)
        if (status !in 200..299) {
            val body = runCatching { networkPolicy.readResponseBody(connection) }.getOrNull()
            throw CloudHttpFailure(
                statusToCloudError(status, retryAfterSeconds(connection), providerErrorMessage(body))
            )
        }
        return networkPolicy.readResponseBody(connection)
    }

    private fun authHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>(4)
        if (credential.apiKey.isNotBlank()) {
            headers["x-goog-api-key"] = credential.apiKey.trim()
        }
        val customName = credential.customAuthHeaderName
        val customValue = credential.customAuthHeaderValue
        if (isSafeHeader(customName, customValue)) {
            headers[checkNotNull(customName)] = checkNotNull(customValue)
        }
        headers["Api-Revision"] = GEMINI_API_REVISION
        headers["Accept"] = "application/json"
        return headers
    }

    private fun resolveEndpoint(path: String): EndpointResolution? = try {
        when (val check = networkPolicy.validateEndpoint(joinCloudUrl(baseUrl, path))) {
            is CloudEndpointCheck.Allowed -> EndpointResolution.Allowed(check)
            is CloudEndpointCheck.Rejected -> EndpointResolution.Rejected(check.reason)
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun estimatedRequestBytes(encodedImage: String, prompt: String): Long =
        encodedImage.length.toLong() + prompt.toByteArray().size + PROMPT_OVERHEAD_BYTES

    private fun aspectRatio(size: String?): String? =
        size?.trim()?.takeIf { ASPECT_RATIO.matches(it) }

    private companion object {
        const val INTERACTIONS_PATH = "/v1beta/interactions"
        const val MODELS_PATH = "/v1beta/models"
        const val MAX_REQUEST_BYTES = 20L * 1024 * 1024
        const val PROMPT_OVERHEAD_BYTES = 1024L
        const val NANOS_PER_MILLI = 1_000_000L
        const val DEFAULT_READ_TIMEOUT_MS = 120_000
        const val DEFAULT_POLL_INTERVAL_MS = 1_500L
        const val DEFAULT_POLL_TIMEOUT_MS = 300_000L
        /** Prepended when a painted mask exists: Gemini has no binary mask channel. */
        const val SOFT_MASK_INSTRUCTION =
            "Apply the edit only to the region described above and keep every other part of " +
                "the image exactly unchanged."

        val ASPECT_RATIO = Regex("""^\d{1,2}:\d{1,2}$""")
    }
}
