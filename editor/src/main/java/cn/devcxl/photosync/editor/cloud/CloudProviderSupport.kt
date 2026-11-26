package cn.devcxl.photosync.editor.cloud

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder

import kotlin.coroutines.coroutineContext

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONException
import org.json.JSONObject

/** Header value used for Gemini's pinned Interactions API revision. */
internal const val GEMINI_API_REVISION = "2026-05-20"

/** Largest provider error message forwarded to the UI. Long provider dumps are truncated. */
private const val MAX_ERROR_MESSAGE_CHARS = 300

/** Joins a user-supplied base URL with a provider path without producing double slashes. */
internal fun joinCloudUrl(baseUrl: String, path: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "Cloud base URL must not be blank" }
    return trimmed + "/" + path.trimStart('/')
}

/** Percent-encodes one query parameter value. */
internal fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8")

/**
 * Runs [block] with a connection opened by [CloudNetworkPolicy].
 *
 * The connection is disconnected when the calling coroutine is cancelled, which makes a blocked
 * socket read abort immediately instead of waiting for the read timeout. The connection is always
 * disconnected, also when [block] throws.
 */
internal suspend fun <T> withPinnedConnection(
    policy: CloudNetworkPolicy,
    endpoint: CloudEndpointCheck.Allowed,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    block: (HttpURLConnection) -> T
): T = withContext(Dispatchers.IO) {
    val connection = policy.openPinnedConnection(endpoint, headers, connectTimeoutMs, readTimeoutMs)
    // A watcher child disconnects the socket as soon as the caller is cancelled, which aborts a
    // blocked read immediately instead of waiting for the read timeout.
    val disconnectOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            connection.disconnect()
        }
    }
    try {
        block(connection)
    } finally {
        disconnectOnCancel.cancel()
        connection.disconnect()
    }
}

/** Pre-flight endpoint outcome shared by the provider implementations. */
internal sealed interface EndpointResolution {
    /** The endpoint passed [CloudNetworkPolicy.validateEndpoint]. */
    class Allowed(val check: CloudEndpointCheck.Allowed) : EndpointResolution

    /** The endpoint must not be contacted. */
    class Rejected(val reason: CloudPolicyRejection) : EndpointResolution
}

/** An HTTP response status that a provider must surface as a classified failure. */
internal class CloudHttpFailure(val error: CloudEditError) : IOException("Cloud request failed")

/**
 * Validates `baseUrl + path` through [CloudNetworkPolicy].
 *
 * @return `null` when the joined URL is not even a valid URL, [EndpointResolution.Rejected] when
 *   policy refuses it, or [EndpointResolution.Allowed] when a socket may be opened.
 */
internal fun resolveCloudEndpoint(
    policy: CloudNetworkPolicy,
    baseUrl: String,
    path: String
): EndpointResolution? = try {
    when (val check = policy.validateEndpoint(joinCloudUrl(baseUrl, path))) {
        is CloudEndpointCheck.Allowed -> EndpointResolution.Allowed(check)
        is CloudEndpointCheck.Rejected -> EndpointResolution.Rejected(check.reason)
    }
} catch (e: IllegalArgumentException) {
    null
}

/** True when a header name/value can be set without CR/LF injection. */
internal fun isSafeHeader(name: String?, value: String?): Boolean =
    !name.isNullOrBlank() &&
        value != null &&
        name.none { it == '\r' || it == '\n' } &&
        value.none { it == '\r' || it == '\n' }

/** Parses [body] as a JSON object, returning `null` for anything else. */
internal fun parseJsonObject(body: ByteArray): JSONObject? = try {
    JSONObject(String(body, Charsets.UTF_8))
} catch (e: JSONException) {
    null
} catch (e: RuntimeException) {
    null
}

/**
 * Extracts `error.message` from an OpenAI, Gemini or ComfyUI error envelope.
 *
 * The result is single-line and truncated so a provider cannot flood the settings page.
 */
internal fun providerErrorMessage(body: ByteArray?): String? {
    if (body == null || body.isEmpty()) return null
    val message = try {
        parseJsonObject(body)?.optJSONObject("error")?.optString("message").orEmpty()
    } catch (e: RuntimeException) {
        ""
    }
    val normalized = message.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return null
    return normalized.take(MAX_ERROR_MESSAGE_CHARS)
}

/** Reads `Retry-After` as whole seconds when the provider sent a numeric value. */
internal fun retryAfterSeconds(connection: HttpURLConnection): Long? =
    connection.getHeaderField("Retry-After")
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0L }

/** Maps an HTTP status to the shared error taxonomy. */
internal fun statusToCloudError(
    status: Int,
    retryAfterSeconds: Long?,
    message: String?
): CloudEditError = when (status) {
    401, 403 -> CloudEditError.Auth
    400, 404, 409, 422 -> CloudEditError.Invalid(providerMessage = message)
    413 -> CloudEditError.TooLarge
    429 -> CloudEditError.RateLimited(retryAfterSeconds)
    in 300..399 -> CloudEditError.RedirectRejected(status)
    in 500..599 -> CloudEditError.Server(status)
    else -> CloudEditError.Unknown(IOException("Unexpected HTTP status $status"))
}

/**
 * Classifies a transport failure after checking that the calling coroutine is still active.
 *
 * A cancelled coroutine produces a [kotlinx.coroutines.CancellationException] here, so provider
 * `catch (IOException)` blocks never turn a user cancellation into a network error.
 */
internal suspend fun IOException.toCloudEditError(): CloudEditError {
    coroutineContext.ensureActive()
    return when (this) {
        is CloudHttpFailure -> error
        is SocketTimeoutException -> CloudEditError.Timeout()
        is CloudResponseTooLargeException -> CloudEditError.TooLarge
        is CloudRedirectRejectedException -> CloudEditError.RedirectRejected(statusCode)
        else -> CloudEditError.Network(this)
    }
}

/**
 * Reads the width and height from a PNG `IHDR` chunk.
 *
 * A signature alone proves nothing: any old PNG passes it. Parsing `IHDR` lets the ComfyUI client
 * confirm that the bytes it got back are the mask it just uploaded, not a stale server file.
 *
 * @return `(width, height)` in pixels, or `null` for a truncated or non-PNG payload.
 */
internal fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < PNG_IHDR_END || !hasPngSignature(bytes)) return null
    val chunkLength = readIntBigEndian(bytes, PNG_IHDR_LENGTH_OFFSET)
    if (chunkLength != PNG_IHDR_LENGTH) return null
    if (!bytes.copyOfRange(PNG_IHDR_TYPE_OFFSET, PNG_IHDR_TYPE_OFFSET + 4)
            .contentEquals(PNG_IHDR_TYPE)
    ) {
        return null
    }
    val width = readIntBigEndian(bytes, PNG_IHDR_WIDTH_OFFSET)
    val height = readIntBigEndian(bytes, PNG_IHDR_HEIGHT_OFFSET)
    if (width <= 0 || height <= 0) return null
    return width to height
}

private fun hasPngSignature(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

/**
 * Returns the image MIME type matching the magic bytes of [bytes], or `null` when it is not an
 * image this client accepts.
 */
internal fun imageMimeTypeOrNull(bytes: ByteArray): String? = when {
    hasPngSignature(bytes) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
    bytes.size >= 6 && bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray()) -> "image/gif"
    else -> null
}

/** True when [bytes] starts with a PNG, JPEG, WebP or GIF signature. */
internal fun looksLikeImage(bytes: ByteArray): Boolean = imageMimeTypeOrNull(bytes) != null

/** Returns the MIME type matching the image signature of [bytes]. */
internal fun mimeTypeFromMagic(bytes: ByteArray): String = imageMimeTypeOrNull(bytes) ?: "image/png"

/** Reads a short authenticated probe response and maps it to a connection result. */
internal suspend fun probeStatus(
    policy: CloudNetworkPolicy,
    endpoint: CloudEndpointCheck.Allowed,
    headers: Map<String, String>,
    connectTimeoutMs: Int,
    readTimeoutMs: Int
): CloudEditError? = try {
    withPinnedConnection(policy, endpoint, headers, connectTimeoutMs, readTimeoutMs) { connection ->
        val status = policy.requireNonRedirectResponse(connection)
        if (status in 200..299) {
            null
        } else {
            val body = runCatching { policy.readResponseBody(connection) }.getOrNull()
            statusToCloudError(status, retryAfterSeconds(connection), providerErrorMessage(body))
        }
    }
} catch (e: IOException) {
    e.toCloudEditError()
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
    0x0D, 0x0A, 0x1A, 0x0A
)

private val PNG_IHDR_TYPE = byteArrayOf(
    'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()
)

/** Signature (8) + chunk length (4) + chunk type (4) + width/height (8). */
private const val PNG_IHDR_END = 24
private const val PNG_IHDR_LENGTH_OFFSET = 8
private const val PNG_IHDR_TYPE_OFFSET = 12
private const val PNG_IHDR_WIDTH_OFFSET = 16
private const val PNG_IHDR_HEIGHT_OFFSET = 20
private const val PNG_IHDR_LENGTH = 13
