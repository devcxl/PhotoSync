package cn.devcxl.photosync.editor.cloud

import android.graphics.Bitmap

import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.runBlocking

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeminiEditProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: LoopbackHttpServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun shouldSendInteractionsPayloadWithPrivacyFlags_whenEditing() {
        val uploadBytes = imageBytes(format = Bitmap.CompressFormat.JPEG)
        val resultBytes = imageBytes()
        lateinit var captured: TestHttpRequest
        startServer { request ->
            captured = request
            jsonResponse(completedInteraction(resultBytes))
        }
        val image = uploadHandle(tempFolder.root, uploadBytes)

        val result = runBlocking {
            provider().edit(CloudEditRequest(image = image, prompt = "change the sofa"))
        }

        server?.throwIfFailed()
        val success = result as CloudEditResult.Success
        assertArrayEquals(resultBytes, success.imageBytes)
        assertEquals("image/png", success.mimeType)
        assertEquals("POST", captured.method)
        assertEquals("/v1beta/interactions", captured.path)
        assertEquals("gemini-test-key", captured.header("x-goog-api-key"))
        assertEquals("2026-05-20", captured.header("api-revision"))
        val body = captured.jsonObject()
        assertEquals("gemini-test-model", body.getString("model"))
        assertEquals(false, body.getBoolean("store"))
        assertEquals(true, body.getBoolean("background"))
        val input = body.getJSONArray("input")
        assertEquals("text", input.getJSONObject(0).getString("type"))
        assertEquals("change the sofa", input.getJSONObject(0).getString("text"))
        assertEquals("image", input.getJSONObject(1).getString("type"))
        assertEquals(encodeBase64(uploadBytes), input.getJSONObject(1).getString("data"))
        assertFalse(image.file.exists())
    }

    @Test
    fun shouldPollInteractionUntilCompleted_whenBackgroundRequestIsInProgress() {
        val historyCalls = AtomicInteger()
        val resultBytes = imageBytes()
        startServer { request ->
            when {
                request.method == "POST" -> jsonResponse(
                    """{"id":"int_123","status":"in_progress"}"""
                )
                request.path == "/v1beta/interactions/int_123" -> {
                    if (historyCalls.incrementAndGet() == 1) {
                        jsonResponse("""{"id":"int_123","status":"in_progress"}""")
                    } else {
                        jsonResponse(completedInteraction(resultBytes))
                    }
                }
                else -> jsonResponse("""{"error":{"message":"unexpected path"}}""", status = 400)
            }
        }

        val result = runBlocking {
            provider(pollIntervalMs = 20, pollTimeoutMs = 5_000).edit(newRequest())
        }

        server?.throwIfFailed()
        assertArrayEquals(resultBytes, (result as CloudEditResult.Success).imageBytes)
        assertEquals(1, server!!.requestCount { it.method == "POST" })
        assertEquals(2, server!!.requestCount { it.method == "GET" })
    }

    @Test
    fun shouldDeclareSoftMaskAndNeverUploadMaskBytes_whenMaskIsProvided() {
        lateinit var captured: TestHttpRequest
        startServer { request ->
            captured = request
            jsonResponse(completedInteraction(imageBytes()))
        }
        val mask = editableMaskHandle(tempFolder.root)
        val maskBytes = mask.file.readBytes()

        val result = runBlocking {
            provider().edit(CloudEditRequest(image = newImage(), mask = mask, prompt = "remove the bin"))
        }

        assertTrue(result is CloudEditResult.Success)
        assertEquals(CloudMaskMode.SOFT, provider().capabilities.maskMode)
        val input = captured.jsonObject().getJSONArray("input")
        val text = input.getJSONObject(0).getString("text")
        assertTrue("The mask must become a prompt constraint", text.contains("keep every other part"))
        assertFalse(
            "Gemini has no mask channel; mask bytes must never be uploaded",
            String(captured.body, Charsets.ISO_8859_1).contains(encodeBase64(maskBytes))
        )
        assertFalse(mask.file.exists())
    }

    @Test
    fun shouldReturnTimeoutAfterExactlyOnePost_whenReadTimesOut() {
        startServer {
            Thread.sleep(1_500)
            jsonResponse("{}")
        }
        val image = newImage()

        val result = runBlocking {
            provider(readTimeoutMs = 250).edit(
                CloudEditRequest(image = image, prompt = "remove the sign")
            )
        }

        assertEquals(CloudEditError.Timeout(), (result as CloudEditResult.Failure).error)
        assertEquals(1, server!!.requestCount { it.method == "POST" })
        assertFalse(image.file.exists())
    }

    @Test
    fun shouldReportReachable_whenModelsProbeSucceeds() {
        startServer { jsonResponse("""{"models":[]}""") }

        val result = runBlocking { provider().testConnection() }

        assertTrue(result is CloudConnectionResult.Reachable)
        assertEquals("/v1beta/models", server!!.requests.single().path)
        assertEquals("gemini-test-key", server!!.requests.single().header("x-goog-api-key"))
    }

    private fun completedInteraction(imageBytes: ByteArray): String =
        """{"id":"v1_done","status":"completed","steps":[{"type":"model_output",""" +
            """"content":[{"type":"image","data":"${encodeBase64(imageBytes)}",""" +
            """"mime_type":"image/png"}]}]}"""

    private fun newImage() = uploadHandle(tempFolder.root, imageBytes())

    private fun newRequest(prompt: String = "remove the sign") =
        CloudEditRequest(image = newImage(), prompt = prompt)

    private fun startServer(handler: (TestHttpRequest) -> TestHttpResponse) {
        server = LoopbackHttpServer(handler).also { it.start() }
    }

    private fun provider(
        readTimeoutMs: Int = 5_000,
        pollIntervalMs: Long = 1_000,
        pollTimeoutMs: Long = 30_000
    ) = GeminiEditProvider(
        baseUrl = server!!.url(""),
        configuredModel = "gemini-test-model",
        credential = ByokCredential("gemini-profile", apiKey = "gemini-test-key"),
        networkPolicy = CloudNetworkPolicy(allowLanCleartextHttp = true),
        background = true,
        connectTimeoutMs = 2_000,
        readTimeoutMs = readTimeoutMs,
        pollIntervalMs = pollIntervalMs,
        pollTimeoutMs = pollTimeoutMs
    )

    private fun encodeBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}
