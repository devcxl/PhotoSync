package cn.devcxl.photosync.editor.cloud

import java.io.File

import android.graphics.Bitmap
import android.graphics.Color

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
class OpenAiEditProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: LoopbackHttpServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun shouldEncodeMultipartAndEditableMask_whenEditing() {
        val uploadBytes = imageBytes(format = Bitmap.CompressFormat.JPEG)
        val resultBytes = imageBytes(color = Color.GREEN)
        lateinit var captured: TestHttpRequest
        startServer { request ->
            captured = request
            jsonResponse(
                """{"created":1,"data":[{"b64_json":"${encodeBase64(resultBytes)}"}],""" +
                    """"output_format":"png"}"""
            )
        }
        val image = uploadHandle(tempFolder.root, uploadBytes)
        val mask = editableMaskHandle(tempFolder.root)
        val request = CloudEditRequest(image = image, mask = mask, prompt = "replace the sky")

        val result = runBlocking { provider().edit(request) }

        server?.throwIfFailed()
        val success = result as CloudEditResult.Success
        assertArrayEquals(resultBytes, success.imageBytes)
        assertEquals("image/png", success.mimeType)
        assertEquals("POST", captured.method)
        assertEquals("/v1/images/edits", captured.path)
        assertEquals("Bearer sk-test-key", captured.header("authorization"))
        val parts = parseMultipart(captured)
        assertEquals("user-model-x", parts.text("model"))
        assertEquals("replace the sky", parts.text("prompt"))
        assertEquals("1", parts.text("n"))
        assertEquals("upload.jpg", parts.part("image").fileName)
        assertEquals("image/jpeg", parts.part("image").contentType)
        assertArrayEquals(uploadBytes, parts.part("image").bytes)
        assertEquals("mask.png", parts.part("mask").fileName)
        assertEquals("image/png", parts.part("mask").contentType)
        assertEditableMask(parts.part("mask").bytes, image.width, image.height)
        assertFalse("The sanitized upload must be deleted", image.file.exists())
        assertFalse("The staged mask must be deleted", mask.file.exists())
    }

    @Test
    fun shouldSendUserSelectedModelAndNeverARetiredOne_whenEditing() {
        val models = mutableListOf<String>()
        val resultBytes = imageBytes()
        startServer { request ->
            synchronized(models) { models += parseMultipart(request).text("model") }
            jsonResponse(
                """{"data":[{"b64_json":"${encodeBase64(resultBytes)}"}],"output_format":"png"}"""
            )
        }
        val configured = runBlocking {
            provider(model = "gateway-model-a").edit(newRequest())
        }
        assertTrue(configured is CloudEditResult.Success)
        val overridden = runBlocking {
            provider(model = "gateway-model-a").edit(
                CloudEditRequest(
                    image = uploadHandle(tempFolder.root, imageBytes()),
                    prompt = "make it warm",
                    model = "override-model-b"
                )
            )
        }
        assertTrue(overridden is CloudEditResult.Success)
        assertEquals(listOf("gateway-model-a", "override-model-b"), models)

        val source = File(SOURCE_PATH).readText()
        assertFalse("Retired model names must never be hardcoded", source.contains("gpt-image"))
        assertFalse("Retired model names must never be hardcoded", source.contains("dall-e"))
    }

    @Test
    fun shouldReturnTimeoutAfterExactlyOneRequest_whenReadTimesOut() {
        startServer {
            Thread.sleep(1_500)
            jsonResponse("{}")
        }
        val image = uploadHandle(tempFolder.root, imageBytes())

        val result = runBlocking {
            provider(readTimeoutMs = 250).edit(
                CloudEditRequest(image = image, prompt = "remove the sign")
            )
        }

        val failure = result as CloudEditResult.Failure
        assertEquals(CloudEditError.Timeout(), failure.error)
        assertEquals("A timed-out POST must never be retried", 1, server!!.requestCount())
        assertFalse(image.file.exists())
    }

    @Test
    fun shouldAbortTransferAndDeleteUploads_whenCoroutineIsCancelled() {
        startServer {
            Thread.sleep(10_000)
            jsonResponse("{}")
        }
        val image = uploadHandle(tempFolder.root, imageBytes())
        val mask = editableMaskHandle(tempFolder.root)
        val provider = provider(readTimeoutMs = 30_000)
        val request = CloudEditRequest(image = image, mask = mask, prompt = "clean the wall")

        val elapsedMs = runBlocking {
            val job = launch(Dispatchers.IO) { provider.edit(request) }
            assertTrue("The request never reached the stub", server!!.awaitRequestCount(1))
            val startedAt = System.nanoTime()
            job.cancelAndJoin()
            (System.nanoTime() - startedAt) / 1_000_000
        }

        assertTrue("Cancellation must not wait for the read timeout", elapsedMs < 2_000)
        assertFalse(image.file.exists())
        assertFalse(mask.file.exists())
        assertEquals(1, server!!.requestCount())
    }

    @Test
    fun shouldRejectHttpResultUrl_whenProviderReturnsOne() {
        startServer {
            jsonResponse(
                """{"data":[{"url":"http://127.0.0.1:${server!!.port}/result.png"}]}"""
            )
        }

        val result = runBlocking { provider().edit(newRequest()) }

        val failure = result as CloudEditResult.Failure
        assertEquals(
            CloudEditError.EndpointRejected(CloudPolicyRejection.CLEARTEXT_HTTP_FORBIDDEN),
            failure.error
        )
        assertEquals("The insecure result URL must not be fetched", 1, server!!.requestCount())
    }

    @Test
    fun shouldMapUnauthorizedResponse_whenApiKeyIsRejected() {
        startServer {
            jsonResponse("""{"error":{"message":"Incorrect API key"}}""", status = 401)
        }

        val result = runBlocking { provider().edit(newRequest()) }

        assertEquals(CloudEditError.Auth, (result as CloudEditResult.Failure).error)
    }

    @Test
    fun shouldReportReachable_whenModelsProbeSucceeds() {
        startServer { jsonResponse("""{"data":[]}""") }

        val result = runBlocking { provider().testConnection() }

        assertTrue(result is CloudConnectionResult.Reachable)
        assertTrue((result as CloudConnectionResult.Reachable).latencyMs >= 0L)
        assertEquals("/v1/models", server!!.requests.single().path)
    }

    private fun newRequest(prompt: String = "remove the sign") = CloudEditRequest(
        image = uploadHandle(tempFolder.root, imageBytes()),
        prompt = prompt
    )

    private fun startServer(handler: (TestHttpRequest) -> TestHttpResponse) {
        server = LoopbackHttpServer(handler).also { it.start() }
    }

    private fun provider(
        model: String = "user-model-x",
        readTimeoutMs: Int = 5_000
    ) = OpenAiEditProvider(
        baseUrl = server!!.url(""),
        configuredModel = model,
        credential = ByokCredential("openai-profile", apiKey = "sk-test-key"),
        networkPolicy = CloudNetworkPolicy(allowLanCleartextHttp = true),
        connectTimeoutMs = 2_000,
        readTimeoutMs = readTimeoutMs
    )

    private fun encodeBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private companion object {
        const val SOURCE_PATH =
            "src/main/java/cn/devcxl/photosync/editor/cloud/OpenAiEditProvider.kt"
    }
}
