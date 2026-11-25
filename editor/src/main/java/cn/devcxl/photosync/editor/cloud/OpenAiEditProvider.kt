package cn.devcxl.photosync.editor.cloud

import java.io.IOException
import java.net.HttpURLConnection

import android.util.Base64

import kotlinx.coroutines.CancellationException

import org.json.JSONException
import org.json.JSONObject

/**
 * OpenAI-compatible `POST {baseUrl}/v1/images/edits` provider.
 *
 * Contract highlights (see `docs/research/2026-11-26-byok-cloud-edit-protocols-and-security.md`):
 * - the model is **always** user supplied; no model name is compiled into this class, because the
 *   official model catalogue retires names faster than the app ships;
 * - the request is multipart and streams both files from disk;
 * - the mask is a same-size PNG whose transparent pixels are editable ([CloudMaskMode.HARD]);
 * - `b64_json` is preferred, and a `url` result is only downloaded over verified HTTPS;
 * - a read timeout returns [CloudEditError.Timeout] after exactly one POST. Retrying could bill
 *   the user twice, so it is never automatic.
 *
 * All methods perform network I/O and **must be called off the main thread**.
 *
 * @param baseUrl Gateway root, for example `https://api.openai.com` or an Azure/Foundry prefix.
 * @param configuredModel Model selected in the settings page.
 * @param credential API key or reverse-proxy auth header stored by [ByokSecretStore].
 * @param networkPolicy Endpoint guard that enforces HTTPS and the LAN cleartext opt-in.
 */
class OpenAiEditProvider(
    private val baseUrl: String,
    private val configuredModel: String,
    private val credential: ByokCredential,
    private val networkPolicy: CloudNetworkPolicy,
    private val connectTimeoutMs: Int = CloudNetworkPolicy.DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : CloudEditProvider {

    override val kind: CloudProviderKind = CloudProviderKind.OPENAI_COMPATIBLE

    private val downloader = CloudImageResultDownloader(
        networkPolicy,
        connectTimeoutMs,
        readTimeoutMs
    )

    override val capabilities = CloudProviderCapabilities(
        maskMode = CloudMaskMode.HARD,
        maxImages = 1,
        maxUploadBytes = MAX_IMAGE_BYTES,
        supportsNegativePrompt = false,
        supportsSeed = false,
        blocking = true
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
        val endpoint = resolveEndpoint(MODELS_PATH)
            ?: return CloudConnectionResult.Failed(
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
        if (prompt.length > MAX_PROMPT_CHARS) {
            return CloudEditResult.Failure(CloudEditError.Invalid("The prompt is too long"))
        }
        validateUploads(request)?.let { return CloudEditResult.Failure(it) }
        val endpoint = resolveEndpoint(EDITS_PATH)
        when (endpoint) {
            null -> return CloudEditResult.Failure(
                CloudEditError.EndpointRejected(CloudPolicyRejection.MALFORMED_URL)
            )
            is EndpointResolution.Rejected -> return CloudEditResult.Failure(
                CloudEditError.EndpointRejected(endpoint.reason)
            )
            is EndpointResolution.Allowed -> Unit
        }
        val multipart = buildMultipart(request, model, prompt)
        onStage(CloudJobStage.SUBMITTING)
        val body = withPinnedConnection(
            policy = networkPolicy,
            endpoint = (endpoint as EndpointResolution.Allowed).check,
            headers = authHeaders(),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        ) { connection ->
            writeMultipart(connection, multipart)
            readResponse(connection)
        }
        return interpret(body)
    }

    private fun validateUploads(request: CloudEditRequest): CloudEditError? {
        val image = request.image.file
        if (!image.isFile || !image.canRead()) {
            return CloudEditError.Invalid("The staged upload is missing")
        }
        if (image.length() > MAX_IMAGE_BYTES) return CloudEditError.TooLarge
        val mask = request.mask ?: return null
        if (!mask.file.isFile || !mask.file.canRead()) {
            return CloudEditError.Invalid("The staged mask is missing")
        }
        if (mask.file.length() > MAX_MASK_BYTES) return CloudEditError.TooLarge
        if (mask.width != request.image.width || mask.height != request.image.height) {
            return CloudEditError.Invalid("The mask must match the uploaded image size")
        }
        return null
    }

    private fun buildMultipart(
        request: CloudEditRequest,
        model: String,
        prompt: String
    ): CloudMultipart {
        val multipart = CloudMultipart()
            .addField("model", model)
            .addField("prompt", prompt)
            .addField("n", "1")
            .addFile("image", IMAGE_UPLOAD_NAME, "image/jpeg", request.image.file)
        request.size?.trim()?.takeIf { it.isNotEmpty() }?.let { multipart.addField("size", it) }
        request.mask?.let { mask ->
            multipart.addFile("mask", MASK_UPLOAD_NAME, "image/png", mask.file)
        }
        return multipart
    }

    private fun writeMultipart(connection: HttpURLConnection, multipart: CloudMultipart) {
        connection.doOutput = true
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", multipart.contentType)
        connection.setFixedLengthStreamingMode(multipart.contentLength())
        connection.outputStream.use { multipart.writeTo(it) }
    }

    private fun readResponse(connection: HttpURLConnection): ByteArray {
        val status = networkPolicy.requireNonRedirectResponse(connection)
        if (status !in 200..299) {
            val body = runCatching { networkPolicy.readResponseBody(connection) }.getOrNull()
            val error = statusToCloudError(
                status,
                retryAfterSeconds(connection),
                providerErrorMessage(body)
            )
            throw CloudHttpFailure(error)
        }
        return networkPolicy.readResponseBody(connection)
    }

    private suspend fun interpret(body: ByteArray): CloudEditResult {
        val json = parseJsonObject(body)
            ?: return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The response was not JSON")
            )
        val data = json.optJSONArray("data")
        val first = data?.optJSONObject(0)
            ?: return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The response contained no image")
            )
        val encoded = first.optString("b64_json").takeIf { it.isNotBlank() }
        if (encoded != null) {
            val bytes = try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return CloudEditResult.Failure(
                    CloudEditError.MalformedResponse("The base64 image was malformed")
                )
            }
            return imageResult(bytes, mimeForFormat(json.optString("output_format")))
        }
        val rawUrl = first.optString("url").takeIf { it.isNotBlank() }
            ?: return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The response contained no image")
            )
        return downloader.downloadHttps(rawUrl)
    }

    private fun imageResult(bytes: ByteArray, mimeType: String): CloudEditResult =
        if (looksLikeImage(bytes)) {
            CloudEditResult.Success(bytes, mimeType)
        } else {
            CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The provider returned a non-image payload")
            )
        }

    private fun authHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>(2)
        val customName = credential.customAuthHeaderName
        val customValue = credential.customAuthHeaderValue
        if (isSafeHeader(customName, customValue)) {
            headers[checkNotNull(customName)] = checkNotNull(customValue)
        } else if (credential.apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${credential.apiKey.trim()}"
        }
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

    private fun mimeForFormat(format: String): String = when (format.lowercase()) {
        "jpeg", "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    private companion object {
        const val EDITS_PATH = "/v1/images/edits"
        const val MODELS_PATH = "/v1/models"
        const val IMAGE_UPLOAD_NAME = "upload.jpg"
        const val MASK_UPLOAD_NAME = "mask.png"
        const val MAX_PROMPT_CHARS = 32_000
        const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
        const val MAX_MASK_BYTES = 4L * 1024 * 1024
        const val NANOS_PER_MILLI = 1_000_000L

        /** OpenAI recommends at least 120 s: generation regularly takes 10-60 s. */
        const val DEFAULT_READ_TIMEOUT_MS = 120_000
    }
}
