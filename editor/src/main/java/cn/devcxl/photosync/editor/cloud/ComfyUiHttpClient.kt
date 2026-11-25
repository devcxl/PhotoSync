package cn.devcxl.photosync.editor.cloud

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID

import org.json.JSONObject

/** An uploaded ComfyUI file, as named by the server response. */
internal data class ComfyUploadRef(val name: String, val subfolder: String, val type: String) {
    /** The annotation ComfyUI expects in `LoadImage.inputs.image`. */
    val annotation: String
        get() = if (subfolder.isBlank()) name else "$subfolder/$name"
}

/** A ComfyUI output image reference from `/history/{id}`. */
internal data class ComfyImageRef(val filename: String, val subfolder: String, val type: String)

/**
 * Strict file-name rules for every ComfyUI path component.
 *
 * `/view` rejects leading `/` and `..`, but the client must not rely on the server: a malicious or
 * buggy server response must never steer the app into a traversal or a query injection.
 */
internal object ComfyPathGuard {

    /** Server-side file names must be plain: no separators, no `..`, no control characters. */
    fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() &&
            !name.startsWith(".") &&
            name.none { it == '/' || it == '\\' || it.code < 0x20 } &&
            !name.contains("..")

    /** A subfolder is a relative path of safe segments; empty means the root. */
    fun isSafeSubfolder(subfolder: String): Boolean =
        subfolder.isEmpty() || subfolder.split('/').all { segment ->
            segment.isNotEmpty() &&
                segment != "." &&
                segment != ".." &&
                segment.none { it == '\\' || it.code < 0x20 }
        }

    /** Only the three ComfyUI storage roots are addressable. */
    fun isSafeType(type: String): Boolean = type in COMFY_TYPES

    /** Prompt ids are canonical lowercase UUIDs, but tolerate other server-generated ids. */
    fun isSafePromptId(promptId: String): Boolean =
        promptId.isNotEmpty() && promptId.length <= 64 &&
            promptId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private val COMFY_TYPES = setOf("input", "output", "temp")
}

/**
 * Thin ComfyUI raw-API client used by [ComfyUiEditProvider].
 *
 * Only the six stable routes documented in the research note are used:
 * `/upload/image`, `/upload/mask`, `/prompt`, `/history/{id}`, `/view` and `/system_stats`.
 * Every request goes through [CloudNetworkPolicy], so redirects are rejected, response bodies are
 * capped and the LAN cleartext opt-in is enforced.
 */
internal class ComfyUiHttpClient(
    private val baseUrl: String,
    private val credential: ByokCredential,
    private val networkPolicy: CloudNetworkPolicy,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int
) {

    suspend fun uploadImage(file: File, fileName: String): ComfyUploadRef {
        val multipart = CloudMultipart()
            .addFile("image", fileName, "image/jpeg", file)
            .addField("type", "input")
            .addField("overwrite", "true")
        return parseUpload(sendMultipart(UPLOAD_IMAGE_PATH, multipart), "upload")
    }

    suspend fun uploadMask(
        maskFile: File,
        fileName: String,
        original: ComfyUploadRef
    ): ComfyUploadRef {
        val originalRef = JSONObject()
            .put("filename", original.name)
            .put("subfolder", original.subfolder)
            .put("type", original.type)
        val multipart = CloudMultipart()
            .addFile("image", fileName, "image/png", maskFile)
            .addField("original_ref", originalRef.toString())
            .addField("type", "input")
        return parseUpload(sendMultipart(UPLOAD_MASK_PATH, multipart), "mask upload")
    }

    /**
     * Confirms a mask exists by fetching it through `/view`.
     *
     * `/upload/mask` answers 200 even when `original_ref` was not found and nothing was written, so
     * the only trustworthy confirmation is a non-empty image body.
     */
    suspend fun verifyUploadedFile(ref: ComfyUploadRef) {
        val bytes = get(viewQuery(ref.name, ref.subfolder, ref.type))
        if (!isPng(bytes)) {
            throw CloudHttpFailure(
                CloudEditError.Invalid("ComfyUI reported a mask it did not store")
            )
        }
    }

    suspend fun submitPrompt(workflow: JSONObject, promptId: String): String {
        val payload = JSONObject()
            .put("prompt", workflow)
            .put("client_id", UUID.randomUUID().toString())
            .put("prompt_id", promptId)
        val json = parseJsonObject(sendJson(PROMPT_PATH, payload))
            ?: throw CloudHttpFailure(
                CloudEditError.MalformedResponse("ComfyUI returned an invalid prompt response")
            )
        val nodeErrors = json.optJSONObject("node_errors")
        if (nodeErrors != null && nodeErrors.length() > 0) {
            throw CloudHttpFailure(CloudEditError.Invalid("ComfyUI rejected the workflow"))
        }
        val serverId = json.stringOrNull("prompt_id")
        if (serverId == null) return promptId
        if (!ComfyPathGuard.isSafePromptId(serverId)) {
            throw CloudHttpFailure(
                CloudEditError.MalformedResponse("ComfyUI returned an unsafe prompt id")
            )
        }
        return serverId
    }

    suspend fun fetchHistory(promptId: String): JSONObject {
        val bytes = get("$HISTORY_PATH/${encodeQueryValue(promptId)}")
        return parseJsonObject(bytes)
            ?: throw CloudHttpFailure(
                CloudEditError.MalformedResponse("ComfyUI returned invalid history JSON")
            )
    }

    suspend fun downloadOutput(ref: ComfyImageRef): ByteArray {
        if (!ComfyPathGuard.isSafeFileName(ref.filename) ||
            !ComfyPathGuard.isSafeSubfolder(ref.subfolder) ||
            !ComfyPathGuard.isSafeType(ref.type)
        ) {
            throw CloudHttpFailure(
                CloudEditError.MalformedResponse("ComfyUI returned an unsafe output path")
            )
        }
        return get(viewQuery(ref.filename, ref.subfolder, ref.type))
    }

    suspend fun probeSystemStats(): CloudEditError? = try {
        get(SYSTEM_STATS_PATH)
        null
    } catch (e: CloudHttpFailure) {
        e.error
    } catch (e: IOException) {
        e.toCloudEditError()
    }

    private suspend fun sendMultipart(path: String, multipart: CloudMultipart): ByteArray =
        withPinnedConnection(
            policy = networkPolicy,
            endpoint = endpoint(path),
            headers = authHeaders().plus("Content-Type" to multipart.contentType),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        ) { connection ->
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setFixedLengthStreamingMode(multipart.contentLength())
            connection.outputStream.use { multipart.writeTo(it) }
            readBody(connection)
        }

    private suspend fun sendJson(path: String, payload: JSONObject): ByteArray {
        val body = payload.toString().toByteArray()
        return withPinnedConnection(
            policy = networkPolicy,
            endpoint = endpoint(path),
            headers = authHeaders().plus("Content-Type" to "application/json"),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        ) { connection ->
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            readBody(connection)
        }
    }

    private suspend fun get(pathWithQuery: String): ByteArray = withPinnedConnection(
        policy = networkPolicy,
        endpoint = endpoint(pathWithQuery),
        headers = authHeaders(),
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    ) { connection ->
        connection.requestMethod = "GET"
        readBody(connection)
    }

    private fun readBody(connection: HttpURLConnection): ByteArray {
        val status = networkPolicy.requireNonRedirectResponse(connection)
        if (status !in 200..299) {
            val body = runCatching { networkPolicy.readResponseBody(connection) }.getOrNull()
            throw CloudHttpFailure(
                statusToCloudError(status, retryAfterSeconds(connection), providerErrorMessage(body))
            )
        }
        return networkPolicy.readResponseBody(connection)
    }

    private fun parseUpload(bytes: ByteArray, action: String): ComfyUploadRef {
        val json = parseJsonObject(bytes)
            ?: throw CloudHttpFailure(
                CloudEditError.MalformedResponse("ComfyUI returned an invalid $action response")
            )
        val name = json.stringOrNull("name").orEmpty()
        val subfolder = json.stringOrNull("subfolder").orEmpty()
        val type = json.stringOrNull("type")?.takeIf { it.isNotBlank() } ?: "input"
        if (!ComfyPathGuard.isSafeFileName(name) || !ComfyPathGuard.isSafeSubfolder(subfolder)) {
            throw CloudHttpFailure(
                CloudEditError.Invalid("ComfyUI returned an unusable file path")
            )
        }
        if (!ComfyPathGuard.isSafeType(type)) {
            throw CloudHttpFailure(
                CloudEditError.MalformedResponse("ComfyUI returned an unknown storage type")
            )
        }
        return ComfyUploadRef(name, subfolder, type)
    }

    private fun endpoint(pathWithQuery: String): CloudEndpointCheck.Allowed = try {
        when (val check = networkPolicy.validateEndpoint(joinCloudUrl(baseUrl, pathWithQuery))) {
            is CloudEndpointCheck.Allowed -> check
            is CloudEndpointCheck.Rejected ->
                throw CloudHttpFailure(CloudEditError.EndpointRejected(check.reason))
        }
    } catch (e: IllegalArgumentException) {
        throw CloudHttpFailure(
            CloudEditError.EndpointRejected(CloudPolicyRejection.MALFORMED_URL)
        )
    }

    private fun authHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>(3)
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

    private fun viewQuery(filename: String, subfolder: String, type: String): String =
        "$VIEW_PATH?filename=${encodeQueryValue(filename)}" +
            "&subfolder=${encodeQueryValue(subfolder)}&type=${encodeQueryValue(type)}"

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private companion object {
        const val UPLOAD_IMAGE_PATH = "/upload/image"
        const val UPLOAD_MASK_PATH = "/upload/mask"
        const val PROMPT_PATH = "/prompt"
        const val HISTORY_PATH = "/history"
        const val VIEW_PATH = "/view"
        const val SYSTEM_STATS_PATH = "/system_stats"
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}

/** Reads a JSON string property, returning `null` for missing values and JSON `null`. */
internal fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String
