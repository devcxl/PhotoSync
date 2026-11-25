package cn.devcxl.photosync.editor.cloud

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import org.json.JSONObject

/** One request captured by [LoopbackHttpServer]. Header names are lowercased. */
internal class TestHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    /** Header lookup is case-insensitive because [headers] is normalized. */
    fun header(name: String): String? = headers[name.lowercase()]

    /** Parses the body as JSON; fails the test when it is not an object. */
    fun jsonObject(): JSONObject = JSONObject(String(body, Charsets.UTF_8))
}

/** Response produced by a [LoopbackHttpServer] handler. */
internal class TestHttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String? = "application/json",
    val headers: Map<String, String> = emptyMap()
)

/** Builds a JSON response. */
internal fun jsonResponse(json: String, status: Int = 200): TestHttpResponse =
    TestHttpResponse(status, json.toByteArray(), "application/json")

/** Builds a binary response. */
internal fun bytesResponse(bytes: ByteArray, contentType: String = "image/png"): TestHttpResponse =
    TestHttpResponse(200, bytes, contentType)

/**
 * Zero-dependency loopback HTTP server for provider tests.
 *
 * Handlers run on a worker thread and may block: that is how read-timeout and cancellation tests
 * keep a response open. Every request is recorded before the handler runs, so a stalled request can
 * still be counted. Responses always carry `Connection: close`, which keeps `HttpURLConnection`
 * from reusing a socket the test then closes.
 */
internal class LoopbackHttpServer(
    private val handler: (TestHttpRequest) -> TestHttpResponse
) : Closeable {

    private val serverSocket = ServerSocket(0, 32, InetAddress.getByName(HOST))
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "loopback-http").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    /** Every request the server received, in arrival order. */
    val requests = CopyOnWriteArrayList<TestHttpRequest>()

    /** Handler exceptions, surfaced by [throwIfFailed] so a broken stub cannot hang a test. */
    private val failures = CopyOnWriteArrayList<Throwable>()

    /** Port the server listens on. */
    val port: Int
        get() = serverSocket.localPort

    /** Starts the accept loop. */
    fun start() {
        executor.execute {
            while (!closed.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                executor.execute { handle(socket) }
            }
        }
    }

    /** Absolute loopback URL for [path], which may already contain a query string. */
    fun url(path: String): String = "http://$HOST:$port$path"

    /** Number of received requests matching [predicate]. */
    fun requestCount(predicate: (TestHttpRequest) -> Boolean = { true }): Int =
        requests.count(predicate)

    /** Waits until [expected] requests arrived; returns whether the count was reached. */
    fun awaitRequestCount(expected: Int, timeoutMs: Long = 2_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (requestCount() >= expected) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return requestCount() >= expected
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // Already closed by the test teardown.
        }
        executor.shutdownNow()
    }

    /** Rethrows the first handler failure so test failures point at the stub, not a timeout. */
    fun throwIfFailed() {
        failures.firstOrNull()?.let { throw AssertionError("Loopback handler failed", it) }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                val request = readRequest(client.getInputStream()) ?: return
                requests += request
                val response = try {
                    handler(request)
                } catch (e: Throwable) {
                    failures += e
                    jsonResponse("""{"error":{"message":"handler failed"}}""", status = 500)
                }
                writeResponse(client.getOutputStream(), response)
            }
        } catch (e: IOException) {
            // The client aborted (timeout/cancel tests); assertions use the request counters.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun readRequest(input: InputStream): TestHttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val target = parts[1]
        val headers = readHeaders(input)
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readFully(input, contentLength) else ByteArray(0)
        val queryIndex = target.indexOf('?')
        val path = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val query = if (queryIndex >= 0) parseQuery(target.substring(queryIndex + 1)) else emptyMap()
        return TestHttpRequest(parts[0], path, query, headers, body)
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        return headers
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            }
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
        }
        return buffer.toString("UTF-8")
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) throw IOException("Request body truncated")
            offset += read
        }
        return bytes
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').associate { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) {
                URLDecoder.decode(pair, "UTF-8") to ""
            } else {
                URLDecoder.decode(pair.substring(0, separator), "UTF-8") to
                    URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
            }
        }
    }

    private fun writeResponse(output: OutputStream, response: TestHttpResponse) {
        val header = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ')
                .append(STATUS_REASONS[response.status] ?: "Response").append("\r\n")
            response.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
            for ((name, value) in response.headers) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray())
        output.write(response.body)
        output.flush()
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_INTERVAL_MS = 10L
        val STATUS_REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            413 to "Payload Too Large",
            429 to "Too Many Requests",
            500 to "Internal Server Error"
        )
    }
}
