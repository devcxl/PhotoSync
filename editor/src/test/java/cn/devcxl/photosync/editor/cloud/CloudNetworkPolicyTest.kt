package cn.devcxl.photosync.editor.cloud

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudNetworkPolicyTest {

    @Test
    fun shouldAllowHttps_whenHostIsPublic() {
        val resolver = RecordingResolver()
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = false, dnsResolver = resolver)

        val result = policy.validateEndpoint("https://api.openai.com/v1/images/edits")

        assertTrue(result is CloudEndpointCheck.Allowed)
        assertFalse((result as CloudEndpointCheck.Allowed).isLanCleartext)
        assertEquals(0, resolver.calls)
    }

    @Test
    fun shouldRejectCleartextPublicHost_whenLanOptInIsOn() {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = true)
        val targets = listOf(
            "http://api.openai.com/v1/images",
            "http://203.0.113.9:8080/edit",
            "http://localhost:8188/prompt",
            "http://0.0.0.0:8188/prompt",
            "http://192.168.1.010:8188/prompt",
            "http://127.0.0.2:8188/prompt",
            "http://[::1]:8188/prompt"
        )

        for (target in targets) {
            val result = policy.validateEndpoint(target)
            assertEquals(
                target,
                CloudEndpointCheck.Rejected(CloudPolicyRejection.CLEARTEXT_HTTP_FORBIDDEN),
                result
            )
        }
    }

    @Test
    fun shouldRejectPrivateCleartext_whenLanOptInIsOff() {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = false)

        val result = policy.validateEndpoint("http://192.168.1.20:8188/prompt")

        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.LAN_CLEARTEXT_DISABLED),
            result
        )
    }

    @Test
    fun shouldAllowEveryPrivateIpv4Range_whenLanOptInIsOn() {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = true) { host ->
            listOf(InetAddress.getByName(host))
        }
        val targets = listOf(
            "http://10.0.0.5:8188/prompt",
            "http://172.16.0.1:8188/prompt",
            "http://172.31.255.254:8188/prompt",
            "http://192.168.0.10:8188/prompt",
            "http://127.0.0.1:8188/prompt"
        )

        for (target in targets) {
            val result = policy.validateEndpoint(target)
            assertTrue("$target was rejected: $result", result is CloudEndpointCheck.Allowed)
            assertTrue((result as CloudEndpointCheck.Allowed).isLanCleartext)
        }
    }

    @Test
    fun shouldReject_whenDnsRebindsPrivateLiteralToPublicAddress() {
        val publicAddress = InetAddress.getByName("203.0.113.7")
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = true) { listOf(publicAddress) }

        val result = policy.validateEndpoint("http://192.168.0.10:8188/prompt")

        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.HOST_RESOLVED_OUTSIDE_PRIVATE_NETWORK),
            result
        )
    }

    @Test
    fun shouldReject_whenResolutionReturnsAnyNonPrivateAddress() {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = true) {
            listOf(InetAddress.getByName("192.168.0.10"), InetAddress.getByName("2001:db8::1"))
        }

        val result = policy.validateEndpoint("http://192.168.0.10:8188/prompt")

        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.HOST_RESOLVED_OUTSIDE_PRIVATE_NETWORK),
            result
        )
    }

    @Test
    fun shouldReject_whenResolutionFails() {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = true) {
            throw UnknownHostException("unresolvable")
        }

        val result = policy.validateEndpoint("http://192.168.0.10:8188/prompt")

        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.HOST_RESOLUTION_FAILED),
            result
        )
    }

    @Test
    fun shouldRejectUnsafeUrlShapes_whenCredentialsOrSchemesAreInvalid() {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = true)

        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.EMBEDDED_CREDENTIALS),
            policy.validateEndpoint("https://user:pass@192.168.0.10:8188/prompt")
        )
        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.UNSUPPORTED_SCHEME),
            policy.validateEndpoint("ftp://example.com/file.jpg")
        )
        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.MALFORMED_URL),
            policy.validateEndpoint("not a url")
        )
        assertEquals(
            CloudEndpointCheck.Rejected(CloudPolicyRejection.MALFORMED_URL),
            policy.validateEndpoint("http:///missing-host")
        )
    }

    @Test
    fun shouldDisableRedirectsAndApplyTimeouts_whenOpeningPinnedConnection() {
        val authHeader = AtomicReference<String>()
        withServer(
            handler = { _, headers ->
                authHeader.set(headers["authorization"])
                StubResponse(
                    status = 302,
                    headers = mapOf("Location" to "http://127.0.0.1:1/steal"),
                    reason = "Found"
                )
            }
        ) { port ->
            val policy = CloudNetworkPolicy(allowLanCleartextHttp = true)
            val endpoint = policy.allowedEndpoint("http://127.0.0.1:$port/redirect")
            val connection = policy.openPinnedConnection(
                endpoint,
                headers = mapOf("Authorization" to "Bearer sk-secret"),
                connectTimeoutMs = 1234,
                readTimeoutMs = 5678
            )
            try {
                assertFalse(connection.instanceFollowRedirects)
                assertEquals(1234, connection.connectTimeout)
                assertEquals(5678, connection.readTimeout)
                val thrown = assertThrows(CloudRedirectRejectedException::class.java) {
                    policy.requireNonRedirectResponse(connection)
                }
                assertEquals(302, thrown.statusCode)
            } finally {
                connection.disconnect()
            }
            assertEquals("Bearer sk-secret", authHeader.get())
        }
    }

    @Test
    fun shouldRejectResponse_whenDeclaredContentLengthExceedsCap() {
        val payload = ByteArray(4096) { 0x41 }
        withServer(handler = { _, _ -> StubResponse(status = 200, body = payload) }) { port ->
            val policy = CloudNetworkPolicy(allowLanCleartextHttp = true)
            val endpoint = policy.allowedEndpoint("http://127.0.0.1:$port/large")
            val connection = policy.openPinnedConnection(endpoint)
            try {
                val thrown = assertThrows(CloudResponseTooLargeException::class.java) {
                    policy.readResponseBody(connection, maxBytes = 1024)
                }
                assertEquals(1024, thrown.limitBytes)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun shouldRejectResponse_whenChunkedBodyExceedsCap() {
        val payload = ByteArray(8192) { 0x42 }
        withServer(handler = { _, _ ->
            StubResponse(status = 200, body = payload, chunked = true)
        }) { port ->
            val policy = CloudNetworkPolicy(allowLanCleartextHttp = true)
            val endpoint = policy.allowedEndpoint("http://127.0.0.1:$port/chunked")
            val connection = policy.openPinnedConnection(endpoint)
            try {
                assertThrows(CloudResponseTooLargeException::class.java) {
                    policy.readResponseBody(connection, maxBytes = 1024)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun shouldReadBodyAndPreserveHeaders_whenResponseIsWithinCap() {
        val authHeader = AtomicReference<String>()
        withServer(
            handler = { _, headers ->
                authHeader.set(headers["x-comfy-token"])
                StubResponse(status = 200, body = "ok".toByteArray(Charsets.UTF_8))
            }
        ) { port ->
            val policy = CloudNetworkPolicy(allowLanCleartextHttp = true)
            val endpoint = policy.allowedEndpoint("http://127.0.0.1:$port/view")
            val connection = policy.openPinnedConnection(
                endpoint,
                headers = mapOf("X-Comfy-Token" to "t0ken")
            )
            try {
                assertEquals(200, policy.requireNonRedirectResponse(connection))
                assertEquals("ok", String(policy.readResponseBody(connection), Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
            assertEquals("t0ken", authHeader.get())
        }
    }

    @Test
    fun shouldRejectHeaderInjection_whenOpeningPinnedConnection() {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = true)
        val endpoint = policy.allowedEndpoint("http://127.0.0.1:8188/prompt")

        assertThrows(IllegalArgumentException::class.java) {
            policy.openPinnedConnection(
                endpoint,
                headers = mapOf("Authorization" to "Bearer x\r\nX-Injected: yes")
            )
        }
    }

    @Test
    fun shouldRedactSecretsAndQueries_whenFormattingForLogs() {
        assertEquals("<none>", CloudLogRedaction.redactForLog(null))
        assertEquals("<none>", CloudLogRedaction.redactForLog(""))
        assertEquals("***", CloudLogRedaction.redactForLog("sk-live-secret"))

        val redacted = CloudLogRedaction.redactUrl(
            "https://user:pass@api.example.com:8443/v1/images?key=sk-live-secret#fragment"
        )
        assertEquals("https://api.example.com:8443", redacted)
        assertFalse(redacted.contains("sk-live-secret"))
        assertFalse(redacted.contains("pass"))
        assertFalse(redacted.contains("/v1/images"))
        assertEquals("<invalid-url>", CloudLogRedaction.redactUrl("not a url"))
    }

    private fun CloudNetworkPolicy.allowedEndpoint(rawUrl: String): CloudEndpointCheck.Allowed {
        val result = validateEndpoint(rawUrl)
        assertTrue("Expected $rawUrl to be allowed, got $result", result is CloudEndpointCheck.Allowed)
        return result as CloudEndpointCheck.Allowed
    }

    private fun withServer(
        handler: (requestLine: String, headers: Map<String, String>) -> StubResponse,
        block: (Int) -> Unit
    ) {
        StubHttpServer(handler).use { server -> block(server.port) }
    }

    private class StubHttpServer(
        private val handler: (String, Map<String, String>) -> StubResponse
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val worker = Thread({ serve() }, "stub-http").apply {
            isDaemon = true
            start()
        }

        private fun serve() {
            while (!socket.isClosed) {
                try {
                    socket.accept().use(::handle)
                } catch (e: SocketException) {
                    return
                } catch (e: IOException) {
                    return
                }
            }
        }

        private fun handle(client: Socket) {
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = input.readLine() ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val name = line.substring(0, separator).trim().lowercase()
                    headers[name] = line.substring(separator + 1).trim()
                }
            }
            val response = handler(requestLine, headers)
            val output = client.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
                for ((name, value) in response.headers) append("$name: $value\r\n")
                if (response.chunked) {
                    append("Transfer-Encoding: chunked\r\n")
                } else {
                    append("Content-Length: ${response.body.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            if (response.chunked) {
                val sizeLine = "${Integer.toHexString(response.body.size)}\r\n"
                output.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
                output.write(response.body)
                output.write("\r\n0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                output.write(response.body)
            }
            output.flush()
        }

        override fun close() {
            try {
                socket.close()
            } catch (e: IOException) {
                // Closing the listener is best effort in tests.
            }
        }
    }

    private class StubResponse(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val chunked: Boolean = false,
        val reason: String = "OK"
    )

    private class RecordingResolver : DnsResolver {
        var calls = 0
            private set

        override fun resolve(host: String): List<InetAddress> {
            calls++
            return listOf(InetAddress.getByName(host))
        }
    }
}
