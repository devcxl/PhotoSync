package cn.devcxl.photosync.editor.cloud

import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.runBlocking

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ComfyUiEditProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: LoopbackHttpServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun shouldUploadInjectPollAndFetch_whenWorkflowRuns() {
        val uploadBytes = imageBytes()
        val resultBytes = imageBytes()
        val maskPng = imageBytes(width = 8, height = 6)
        var imageParts: List<MultipartPart> = emptyList()
        var maskParts: List<MultipartPart> = emptyList()
        lateinit var promptRequest: TestHttpRequest
        val historyCalls = AtomicInteger()
        startServer { request ->
            when {
                request.path == "/upload/image" -> {
                    imageParts = parseMultipart(request)
                    jsonResponse("""{"name":"upload.jpg","subfolder":"","type":"input"}""")
                }
                request.path == "/upload/mask" -> {
                    maskParts = parseMultipart(request)
                    jsonResponse("""{"name":"mask.png","subfolder":"","type":"input"}""")
                }
                request.path == "/view" && request.query["filename"] == MASK_NAME ->
                    bytesResponse(maskPng)
                request.path == "/prompt" -> {
                    promptRequest = request
                    jsonResponse(
                        """{"prompt_id":"$PROMPT_ID","number":7,"node_errors":{}}"""
                    )
                }
                request.path == "/history/$PROMPT_ID" ->
                    if (historyCalls.incrementAndGet() == 1) {
                        jsonResponse("{}")
                    } else {
                        jsonResponse(historyJson(OUTPUT_NAME))
                    }
                request.path == "/view" && request.query["filename"] == OUTPUT_NAME ->
                    bytesResponse(resultBytes)
                else -> jsonResponse("""{"error":{"message":"unexpected route"}}""", 400)
            }
        }

        val image = uploadHandle(tempFolder.root, uploadBytes)
        val mask = editableMaskHandle(tempFolder.root)
        val maskBytes = mask.file.readBytes()
        val result = runBlocking {
            provider().edit(
                CloudEditRequest(
                    image = image,
                    mask = mask,
                    prompt = "replace the sofa",
                    negativePrompt = "blurry",
                    seed = 42L
                )
            )
        }

        server?.throwIfFailed()
        assertTrue(
            "Unexpected error: ${(result as? CloudEditResult.Failure)?.error}",
            result is CloudEditResult.Success
        )
        assertArrayEquals(resultBytes, (result as CloudEditResult.Success).imageBytes)
        assertEquals("Bearer comfy-token", promptRequest.header("authorization"))

        val workflow = promptRequest.jsonObject().getJSONObject("prompt")
        assertEquals("upload.jpg", workflow.getJSONObject("10").getJSONObject("inputs")
            .getString("image"))
        assertEquals("mask.png", workflow.getJSONObject("11").getJSONObject("inputs")
            .getString("image"))
        assertEquals("replace the sofa", workflow.getJSONObject("6").getJSONObject("inputs")
            .getString("text"))
        assertEquals("blurry", workflow.getJSONObject("7").getJSONObject("inputs")
            .getString("text"))
        assertEquals(42L, workflow.getJSONObject("3").getJSONObject("inputs").getLong("seed"))
        assertTrue(promptRequest.jsonObject().getString("prompt_id").isNotBlank())
        assertTrue(promptRequest.jsonObject().getString("client_id").isNotBlank())
        assertEquals(2, server!!.requestCount { it.path == "/history/$PROMPT_ID" })

        assertEquals("input", imageParts.text("type"))
        assertEquals("true", imageParts.text("overwrite"))
        assertEquals("upload.jpg", imageParts.part("image").fileName)
        assertArrayEquals(uploadBytes, imageParts.part("image").bytes)
        val originalRef = JSONObject(maskParts.text("original_ref"))
        assertEquals("upload.jpg", originalRef.getString("filename"))
        assertEquals("", originalRef.getString("subfolder"))
        assertEquals("input", originalRef.getString("type"))
        assertEquals("image/png", maskParts.part("image").contentType)
        assertArrayEquals(maskBytes, maskParts.part("image").bytes)
        assertFalse(image.file.exists())
        assertFalse(mask.file.exists())
    }

    @Test
    fun shouldRejectSilentMaskUpload_whenTheServerDidNotStoreTheFile() {
        startServer { request ->
            when (request.path) {
                "/upload/image" ->
                    jsonResponse("""{"name":"upload.jpg","subfolder":"","type":"input"}""")
                "/upload/mask" ->
                    jsonResponse("""{"name":"mask.png","subfolder":"","type":"input"}""")
                "/view" -> jsonResponse("""{"error":{"message":"not found"}}""", status = 404)
                else -> jsonResponse("""{"error":{"message":"unexpected route"}}""", 400)
            }
        }

        val result = runBlocking { provider().edit(newRequest()) }

        val failure = result as CloudEditResult.Failure
        assertTrue(failure.error is CloudEditError.Invalid)
        assertEquals(0, server!!.requestCount { it.path == "/prompt" })
        assertEquals(1, server!!.requestCount { it.path == "/view" })
    }

    @Test
    fun shouldRejectMask_whenViewReturnsAPngWithAnotherDimensions() {
        startServer { request ->
            when (request.path) {
                "/upload/image" ->
                    jsonResponse("""{"name":"upload.jpg","subfolder":"","type":"input"}""")
                "/upload/mask" ->
                    jsonResponse("""{"name":"mask.png","subfolder":"","type":"input"}""")
                "/view" -> bytesResponse(imageBytes(width = 4, height = 4))
                else -> jsonResponse("""{"error":{"message":"unexpected route"}}""", 400)
            }
        }

        val result = runBlocking { provider().edit(newRequest()) }

        val failure = result as CloudEditResult.Failure
        assertEquals(
            CloudEditIssue.MASK_NOT_STORED,
            (failure.error as CloudEditError.Invalid).issue
        )
        assertEquals(0, server!!.requestCount { it.path == "/prompt" })
    }

    @Test
    fun shouldRejectPathTraversal_whenMaskResponseNamesParentDirectory() {
        startServer { request ->
            when (request.path) {
                "/upload/image" ->
                    jsonResponse("""{"name":"upload.jpg","subfolder":"","type":"input"}""")
                "/upload/mask" ->
                    jsonResponse("""{"name":"../evil.png","subfolder":"","type":"input"}""")
                else -> jsonResponse("""{"error":{"message":"unexpected route"}}""", 400)
            }
        }

        val result = runBlocking { provider().edit(newRequest()) }

        assertTrue((result as CloudEditResult.Failure).error is CloudEditError.Invalid)
        assertEquals(0, server!!.requestCount { it.path == "/view" })
        assertEquals(0, server!!.requestCount { it.path == "/prompt" })
    }

    @Test
    fun shouldRejectUnsafeOutputName_whenHistoryPointsOutsideTheOutputFolder() {
        startServer { request ->
            when {
                request.path == "/upload/image" ->
                    jsonResponse("""{"name":"upload.jpg","subfolder":"","type":"input"}""")
                request.path == "/upload/mask" ->
                    jsonResponse("""{"name":"mask.png","subfolder":"","type":"input"}""")
                request.path == "/view" -> bytesResponse(imageBytes(width = 8, height = 6))
                request.path == "/prompt" ->
                    jsonResponse("""{"prompt_id":"$PROMPT_ID","node_errors":{}}""")
                request.path.startsWith("/history/") ->
                    jsonResponse(historyJson("../../secret.png"))
                else -> jsonResponse("""{"error":{"message":"unexpected route"}}""", 400)
            }
        }

        val result = runBlocking { provider().edit(newRequest()) }

        val error = (result as CloudEditResult.Failure).error
        assertTrue("Unexpected error: $error", error is CloudEditError.MalformedResponse)
        assertEquals(0, server!!.requestCount { it.path == "/view" && it.query["filename"] == "../../secret.png" })
    }

    @Test
    fun shouldRejectNodeErrors_whenComfyUiRefusesTheWorkflow() {
        startServer { request ->
            when (request.path) {
                "/upload/image" ->
                    jsonResponse("""{"name":"upload.jpg","subfolder":"","type":"input"}""")
                "/upload/mask" ->
                    jsonResponse("""{"name":"mask.png","subfolder":"","type":"input"}""")
                "/view" -> bytesResponse(imageBytes(width = 8, height = 6))
                "/prompt" -> jsonResponse(
                    """{"error":{"message":"Prompt outputs failed validation"},""" +
                        """"node_errors":{"10":{"errors":[]}}}""",
                    status = 400
                )
                else -> jsonResponse("""{"error":{"message":"unexpected route"}}""", 400)
            }
        }

        val result = runBlocking { provider().edit(newRequest()) }

        assertTrue((result as CloudEditResult.Failure).error is CloudEditError.Invalid)
        assertEquals(0, server!!.requestCount { it.path.startsWith("/history/") })
    }

    @Test
    fun shouldResumeFromHistoryInsteadOfResubmitting_whenPollingTimedOut() {
        val resultBytes = imageBytes()
        var historyCompleted = false
        val promptCalls = AtomicInteger()
        startServer { request ->
            when {
                request.path == "/upload/image" ->
                    jsonResponse("""{"name":"upload.jpg","subfolder":"","type":"input"}""")
                request.path == "/upload/mask" ->
                    jsonResponse("""{"name":"mask.png","subfolder":"","type":"input"}""")
                request.path == "/view" && request.query["filename"] == MASK_NAME ->
                    bytesResponse(imageBytes(width = 8, height = 6))
                request.path == "/prompt" -> {
                    promptCalls.incrementAndGet()
                    jsonResponse("""{"prompt_id":"$PROMPT_ID","node_errors":{}}""")
                }
                request.path.startsWith("/history/") ->
                    if (historyCompleted) {
                        jsonResponse(historyJson(OUTPUT_NAME))
                    } else {
                        jsonResponse("{}")
                    }
                request.path == "/view" && request.query["filename"] == OUTPUT_NAME ->
                    bytesResponse(resultBytes)
                else -> jsonResponse("""{"error":{"message":"unexpected route"}}""", 400)
            }
        }
        val provider = provider(pollIntervalMs = 40, pollTimeoutMs = 150)

        val timedOut = runBlocking { provider.edit(newRequest()) }

        val failure = timedOut as CloudEditResult.Failure
        val timeout = failure.error as CloudEditError.Timeout
        assertEquals(PROMPT_ID, timeout.resumableJobId)
        assertEquals("The workflow must be submitted exactly once", 1, promptCalls.get())
        assertTrue(server!!.requestCount { it.path.startsWith("/history/") } >= 2)

        historyCompleted = true
        val resumed = runBlocking { provider.resume(PROMPT_ID) }

        assertArrayEquals(resultBytes, (resumed as CloudEditResult.Success).imageBytes)
        assertEquals("Resume must never create a second queue entry", 1, promptCalls.get())
    }

    @Test
    fun shouldReportReachable_whenSystemStatsProbeSucceeds() {
        startServer { jsonResponse("""{"system":{"comfyui_version":"1.0"}}""") }

        val result = runBlocking { provider().testConnection() }

        assertTrue(result is CloudConnectionResult.Reachable)
        assertEquals("/system_stats", server!!.requests.single().path)
    }

    private fun newRequest() = CloudEditRequest(
        image = uploadHandle(tempFolder.root, imageBytes()),
        mask = editableMaskHandle(tempFolder.root),
        prompt = "remove the sign"
    )

    private fun startServer(handler: (TestHttpRequest) -> TestHttpResponse) {
        server = LoopbackHttpServer(handler).also { it.start() }
    }

    private fun provider(
        pollIntervalMs: Long = 30,
        pollTimeoutMs: Long = 10_000
    ) = ComfyUiEditProvider(
        baseUrl = server!!.url(""),
        binding = BINDING,
        credential = ByokCredential("comfy-profile", apiKey = "comfy-token"),
        networkPolicy = CloudNetworkPolicy(allowLanCleartextHttp = true),
        connectTimeoutMs = 2_000,
        readTimeoutMs = 5_000,
        pollIntervalMs = pollIntervalMs,
        pollTimeoutMs = pollTimeoutMs
    )

    private fun historyJson(outputName: String): String =
        """{"$PROMPT_ID":{"prompt":[7,"$PROMPT_ID"],"outputs":{"9":{"images":[""" +
            """{"filename":"$outputName","subfolder":"","type":"output"}]}},""" +
            """"status":{"status_str":"success","completed":true,"messages":[]}}}"""

    private companion object {
        const val PROMPT_ID = "0b0e8f2a-4b1c-4f9e-9d7a-2f6c1e8a9b3d"
        const val MASK_NAME = "mask.png"
        const val OUTPUT_NAME = "photosync_00001_.png"

        val BINDING = ComfyUiWorkflowBinding(
            workflowJson = """
                {
                  "3": {"class_type": "KSampler", "inputs": {"seed": 1, "steps": 20}},
                  "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "old prompt"}},
                  "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "old negative"}},
                  "10": {"class_type": "LoadImage", "inputs": {"image": "old.jpg"}},
                  "11": {"class_type": "LoadImage", "inputs": {"image": "old-mask.png"}},
                  "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "photosync"}}
                }
            """.trimIndent(),
            imageNodeId = "10",
            maskNodeId = "11",
            promptNodeId = "6",
            negativePromptNodeId = "7",
            seedNodeId = "3"
        )
    }
}
