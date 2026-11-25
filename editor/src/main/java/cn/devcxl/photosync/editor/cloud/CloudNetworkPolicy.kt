package cn.devcxl.photosync.editor.cloud

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException

/** Pre-flight rejection reasons for a cloud endpoint. Messages are intentionally URL-free. */
enum class CloudPolicyRejection(val summary: String) {
    /** The string is not a parseable absolute URL. */
    MALFORMED_URL("The endpoint URL is malformed"),

    /** `user:password@host` credentials in the URL are never accepted. */
    EMBEDDED_CREDENTIALS("Credentials embedded in the endpoint URL are not accepted"),

    /** Anything other than `http` or `https`, such as `ftp` or `file`. */
    UNSUPPORTED_SCHEME("Only https:// or an opted-in private LAN http endpoint is supported"),

    /** Plain `http://` aimed at a public host, IP literal or hostname. */
    CLEARTEXT_HTTP_FORBIDDEN("Cleartext HTTP is forbidden for public endpoints"),

    /** Plain `http://` to a private IPv4 literal while the LAN opt-in is off. */
    LAN_CLEARTEXT_DISABLED("Cleartext HTTP requires the explicit local-network opt-in"),

    /** The host could not be resolved during the second LAN validation step. */
    HOST_RESOLUTION_FAILED("The endpoint host could not be resolved"),

    /** Resolution returned at least one address outside RFC1918/loopback. */
    HOST_RESOLVED_OUTSIDE_PRIVATE_NETWORK("The endpoint resolved outside the private network")
}

/** Pre-flight result for [CloudNetworkPolicy.validateEndpoint]. */
sealed interface CloudEndpointCheck {
    /**
     * The endpoint may be contacted.
     *
     * @property url Parsed URL, guaranteed to use `https` or an opted-in private LAN `http`.
     * @property isLanCleartext True when this is the explicit self-hosted ComfyUI LAN exception.
     */
    class Allowed internal constructor(
        val url: URL,
        val isLanCleartext: Boolean
    ) : CloudEndpointCheck

    /** The endpoint must not be contacted. */
    data class Rejected(val reason: CloudPolicyRejection) : CloudEndpointCheck
}

/** Resolves a host to every address it advertises. Injectable so DNS-rebinding tests are hermetic. */
fun interface DnsResolver {
    /** @throws UnknownHostException when [host] does not resolve. */
    @Throws(UnknownHostException::class)
    fun resolve(host: String): List<InetAddress>

    companion object {
        /** Resolver backed by the platform stub. */
        fun system(): DnsResolver = DnsResolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

/**
 * Network guard for BYOK cloud providers.
 *
 * 1. Public endpoints must use `https://`. Cleartext `http://` is rejected before a socket is
 *    opened, including hostnames such as `localhost` that merely resolve to loopback.
 * 2. The only cleartext exception is a self-hosted ComfyUI on the local network. It requires the
 *    explicit [allowLanCleartextHttp] opt-in, a strict literal private IPv4 host
 *    (`10/8`, `172.16/12`, `192.168/16` or `127.0.0.1`) and a second check that *every* resolved
 *    [InetAddress] is site-local or loopback. This blocks DNS rebinding, `0.0.0.0`, IPv6 and
 *    decimal/integer IP tricks.
 * 3. [openPinnedConnection] disables automatic redirects for authenticated requests, applies
 *    connect/read timeouts and rejects CR/LF header injection.
 * 4. [readResponseBody] caps the response body at [MAX_RESPONSE_BYTES].
 *
 * Validation performs DNS resolution and must run off the main thread.
 */
class CloudNetworkPolicy(
    val allowLanCleartextHttp: Boolean = false,
    private val dnsResolver: DnsResolver = DnsResolver.system()
) {

    /** Validates [rawUrl] without opening a connection. */
    fun validateEndpoint(rawUrl: String): CloudEndpointCheck {
        val url = try {
            URL(rawUrl)
        } catch (e: MalformedURLException) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.MALFORMED_URL)
        }
        if (url.userInfo != null) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.EMBEDDED_CREDENTIALS)
        }
        val host = url.host
        if (host.isNullOrBlank()) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.MALFORMED_URL)
        }
        return when {
            url.protocol.equals("https", ignoreCase = true) ->
                CloudEndpointCheck.Allowed(url, isLanCleartext = false)
            url.protocol.equals("http", ignoreCase = true) -> validateCleartext(url)
            else -> CloudEndpointCheck.Rejected(CloudPolicyRejection.UNSUPPORTED_SCHEME)
        }
    }

    /**
     * Opens a connection for [endpoint] with redirects disabled, timeouts applied and [headers]
     * set. Callers must treat any 3xx status as a hard error; see [requireNonRedirectResponse].
     */
    fun openPinnedConnection(
        endpoint: CloudEndpointCheck.Allowed,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): HttpURLConnection {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "Cloud timeouts must be positive" }
        val connection = endpoint.url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        for ((name, value) in headers) {
            require(!containsHeaderBreak(name) && !containsHeaderBreak(value)) {
                "Invalid cloud request header"
            }
            connection.setRequestProperty(name, value)
        }
        return connection
    }

    /**
     * Reads the response body with a hard byte ceiling.
     *
     * @throws CloudResponseTooLargeException when the declared or streamed body exceeds [maxBytes].
     */
    fun readResponseBody(
        connection: HttpURLConnection,
        maxBytes: Int = MAX_RESPONSE_BYTES
    ): ByteArray {
        require(maxBytes > 0) { "Response limit must be positive" }
        val status = connection.responseCode
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maxBytes) {
            throw CloudResponseTooLargeException(maxBytes)
        }
        val stream = (if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream)
            ?: return ByteArray(0)
        return readBounded(stream, maxBytes)
    }

    /**
     * Returns the status code and rejects any 3xx response.
     *
     * @throws CloudRedirectRejectedException for every redirect status, because the request may
     *   carry an authorization header.
     */
    fun requireNonRedirectResponse(connection: HttpURLConnection): Int {
        val status = connection.responseCode
        if (status in REDIRECT_STATUS_RANGE) {
            throw CloudRedirectRejectedException(status)
        }
        return status
    }

    private fun validateCleartext(url: URL): CloudEndpointCheck {
        val host = url.host.orEmpty()
        if (!isLiteralPrivateIpv4(host)) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.CLEARTEXT_HTTP_FORBIDDEN)
        }
        if (!allowLanCleartextHttp) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.LAN_CLEARTEXT_DISABLED)
        }
        val addresses = try {
            dnsResolver.resolve(host)
        } catch (e: UnknownHostException) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.HOST_RESOLUTION_FAILED)
        } catch (e: RuntimeException) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.HOST_RESOLUTION_FAILED)
        }
        if (addresses.isEmpty()) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.HOST_RESOLUTION_FAILED)
        }
        if (!addresses.all { it.isSiteLocalAddress || it.isLoopbackAddress }) {
            return CloudEndpointCheck.Rejected(CloudPolicyRejection.HOST_RESOLVED_OUTSIDE_PRIVATE_NETWORK)
        }
        return CloudEndpointCheck.Allowed(url, isLanCleartext = true)
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        stream.use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_BYTES))
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) throw CloudResponseTooLargeException(maxBytes)
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    companion object {
        /** Hard ceiling for any cloud response body: 32 MiB. */
        const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024

        /** Default TCP connect timeout for cloud requests. */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000

        /** Default socket read timeout for cloud requests and polling. */
        const val DEFAULT_READ_TIMEOUT_MS = 30_000

        private const val HTTP_BAD_REQUEST = 400
        private val REDIRECT_STATUS_RANGE = 300..399
        private const val BUFFER_BYTES = 8 * 1024
        private const val LOOPBACK = 0x7F000001L

        /**
         * Strict dotted-quad parser. Rejects shorthand, leading zeros (octal ambiguity),
         * non-ASCII digits and out-of-range octets.
         */
        private fun parseStrictIpv4(host: String): Long? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            var value = 0L
            for (part in parts) {
                if (part.isEmpty() || part.length > 3) return null
                if (!part.all { it in '0'..'9' }) return null
                if (part.length > 1 && part[0] == '0') return null
                val octet = part.toIntOrNull() ?: return null
                if (octet > 255) return null
                value = (value shl 8) or octet.toLong()
            }
            return value
        }

        private fun isLiteralPrivateIpv4(host: String): Boolean {
            val value = parseStrictIpv4(host) ?: return false
            val first = ((value ushr 24) and 0xFF).toInt()
            val second = ((value ushr 16) and 0xFF).toInt()
            return when (first) {
                10 -> true
                172 -> second in 16..31
                192 -> second == 168
                else -> value == LOOPBACK
            }
        }

        private fun containsHeaderBreak(value: String): Boolean =
            value.any { it == '\r' || it == '\n' }
    }
}

/** Response body exceeded [limitBytes]. The message never contains the URL or body. */
class CloudResponseTooLargeException(val limitBytes: Int) :
    IOException("Cloud response exceeded $limitBytes bytes")

/** A redirect was returned for an authenticated request and was not followed. */
class CloudRedirectRejectedException(val statusCode: Int) :
    IOException("Cloud endpoint returned redirect status $statusCode, which is not followed")

/**
 * Log-safe rendering helpers. Credentials, authorization header values, query strings and
 * fragments must never reach Timber, crash reports or Toasts verbatim.
 */
object CloudLogRedaction {
    private const val REDACTED = "***"
    private const val MISSING = "<none>"

    /** Returns a fixed marker for any non-empty secret-bearing value. */
    fun redactForLog(value: String?): String =
        if (value.isNullOrEmpty()) MISSING else REDACTED

    /**
     * Renders only `scheme://host[:port]` for a URL. User info, path, query and fragment are
     * dropped, so API keys in query parameters cannot leak into logs.
     */
    fun redactUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return MISSING
        val url = try {
            URL(rawUrl)
        } catch (e: MalformedURLException) {
            return "<invalid-url>"
        }
        val host = url.host
        if (host.isNullOrBlank()) return "<invalid-url>"
        val portSuffix = if (url.port == -1) "" else ":${url.port}"
        return "${url.protocol}://$host$portSuffix"
    }
}
