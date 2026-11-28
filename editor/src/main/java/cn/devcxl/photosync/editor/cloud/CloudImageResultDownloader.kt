package cn.devcxl.photosync.editor.cloud

import java.io.IOException

import kotlinx.coroutines.CancellationException

/**
 * Downloads a provider-hosted result image.
 *
 * Some OpenAI-compatible gateways answer with a `url` instead of inline base64. Such a URL is only
 * fetched over verified HTTPS (never cleartext, never the LAN exception), without forwarding the
 * API credential, with redirects refused and with the shared 32 MiB response ceiling.
 */
internal class CloudImageResultDownloader(
    private val networkPolicy: CloudNetworkPolicy,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int
) {

    /** Downloads [rawUrl] and validates that the body really is an image. */
    suspend fun downloadHttps(rawUrl: String): CloudEditResult {
        val check = networkPolicy.validateEndpoint(rawUrl)
        if (check !is CloudEndpointCheck.Allowed ||
            check.isLanCleartext ||
            !check.url.protocol.equals("https", ignoreCase = true)
        ) {
            return CloudEditResult.Failure(
                CloudEditError.EndpointRejected(CloudPolicyRejection.CLEARTEXT_HTTP_FORBIDDEN)
            )
        }
        return try {
            val bytes = withPinnedConnection(
                policy = networkPolicy,
                endpoint = check,
                headers = emptyMap(),
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            ) { connection ->
                val status = networkPolicy.requireNonRedirectResponse(connection)
                if (status !in 200..299) {
                    val body = runCatching {
                        networkPolicy.readResponseBody(connection)
                    }.getOrNull()
                    throw CloudHttpFailure(
                        statusToCloudError(
                            status,
                            retryAfterSeconds(connection),
                            providerErrorMessage(body)
                        )
                    )
                }
                networkPolicy.readResponseBody(connection)
            }
            if (looksLikeImage(bytes)) {
                CloudEditResult.Success(bytes, mimeTypeFromMagic(bytes))
            } else {
                CloudEditResult.Failure(
                    CloudEditError.MalformedResponse("The provider returned a non-image payload")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudHttpFailure) {
            CloudEditResult.Failure(e.error)
        } catch (e: IOException) {
            CloudEditResult.Failure(e.toCloudEditError())
        }
    }
}
