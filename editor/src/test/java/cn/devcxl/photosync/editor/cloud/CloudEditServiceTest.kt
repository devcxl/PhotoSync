package cn.devcxl.photosync.editor.cloud

import java.io.File
import java.util.UUID

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Seam under test: [CloudEditService] — consent gating, the three request shapes, provider
 * dispatch, content-addressed persistence and staged-file cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudEditServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var uploadDir: File
    private lateinit var maskDir: File
    private lateinit var assets: AiAssetStore
    private lateinit var consent: CloudConsentStore
    private lateinit var sanitizer: CloudUploadSanitizer

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        filesDir = tempFolder.newFolder("files")
        uploadDir = tempFolder.newFolder("uploads")
        maskDir = tempFolder.newFolder("masks")
        assets = AiAssetStore(filesDir)
        consent = CloudConsentStore(
            context.getSharedPreferences("consent-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        )
        sanitizer = CloudUploadSanitizer(uploadDir)
    }

    @Test
    fun shouldSendSanitizedWholeFrameAndPersistLayer_whenModeIsWholeImage() = runBlocking {
        consent.accept()
        val source = solidBitmap(2400, 1600, Color.BLUE)
        val provider = RecordingProvider(result = successPng(32, 24))
        val service = service(provider)
        val geometry = GeometryOps(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f))

        val outcome = service.execute(
            CloudEditCommand(
                mode = CloudEditMode.WHOLE_IMAGE,
                prompt = "  make it blue  ",
                geometry = geometry,
                source = source
            )
        ) {}

        val applied = appliedLayer(outcome)
        val capture = provider.capture ?: throw AssertionError("The provider never saw the request")
        assertEquals(2048, capture.width)
        assertEquals(1365, capture.height)
        assertNull("A whole-image edit must not send a mask", capture.maskBytes)
        assertEquals("make it blue", capture.prompt)
        assertEquals(100, applied.layer.opacity)
        assertNull(applied.layer.maskRelPath)
        assertEquals(0.0f, applied.layer.featherPx, 0.0f)
        assertEquals("openai", applied.layer.providerTag)
        assertEquals(CloudGeometryDigest.digest(geometry), applied.layer.geometryDigest)
        assertNotNull(assets.read(applied.layer.imageRelPath, applied.layer.sha256))
        assertStagingDirectoriesAreEmpty()
    }

    @Test
    fun shouldSendPaintedEditableMaskAndPersistFeatheredBlendMask_whenModeIsPainted() = runBlocking {
        consent.accept()
        val source = solidBitmap(64, 48, Color.BLUE)
        val painted = paintedHalfBitmap(64, 48)
        val provider = RecordingProvider()
        val service = service(provider)

        val outcome = service.execute(
            CloudEditCommand(
                mode = CloudEditMode.PAINTED_MASK,
                prompt = "replace the left half",
                geometry = GeometryOps(),
                source = source,
                paintedMask = painted,
                featherPx = 16.0f
            )
        ) {}

        val applied = appliedLayer(outcome)
        val capture = provider.capture ?: throw AssertionError("The provider never saw the request")
        val paintedMask = capture.maskBytes
            ?: throw AssertionError("A painted edit must send a mask")
        assertEquals(64, capture.maskWidth)
        assertEquals(48, capture.maskHeight)
        assertMaskPixel(paintedMask, 4, 24, 0)
        assertMaskPixel(paintedMask, 60, 24, 255)
        assertEquals(16.0f, applied.layer.featherPx, 0.0f)
        val maskPath = applied.layer.maskRelPath
            ?: throw AssertionError("A local edit must persist its blend mask")
        val maskPng = assets.read(maskPath, digestOf(maskPath))
            ?: throw AssertionError("The blend mask was not stored")
        val coverage = decodeGrayscale(maskPng)
        assertEquals(255, coverage.getPixel(4, 24) and 0xFF)
        assertEquals(0, coverage.getPixel(60, 24) and 0xFF)
        assertStagingDirectoriesAreEmpty()
    }

    @Test
    fun shouldSendInvertedSubjectMask_whenModeIsBackgroundReplacement() = runBlocking {
        consent.accept()
        val source = solidBitmap(64, 48, Color.GREEN)
        val subject = SubjectMask(
            width = 64,
            height = 48,
            alpha = FloatArray(64 * 48) { index -> if (index % 64 < 32) 1.0f else 0.0f }
        )
        val provider = RecordingProvider()
        val service = service(provider)

        val outcome = service.execute(
            CloudEditCommand(
                mode = CloudEditMode.SUBJECT_BACKGROUND,
                prompt = "replace the background with a studio",
                geometry = GeometryOps(),
                source = source,
                subjectMask = subject,
                featherPx = 0.0f
            )
        ) {}

        val applied = appliedLayer(outcome)
        val capture = provider.capture ?: throw AssertionError("The provider never saw the request")
        val providerMask = capture.maskBytes
            ?: throw AssertionError("A background edit must send a mask")
        assertMaskPixel(providerMask, 4, 24, 255)
        assertMaskPixel(providerMask, 60, 24, 0)
        val maskPath = applied.layer.maskRelPath
            ?: throw AssertionError("A local edit must persist its blend mask")
        val maskPng = assets.read(maskPath, digestOf(maskPath))
            ?: throw AssertionError("The blend mask was not stored")
        val coverage = decodeGrayscale(maskPng)
        assertEquals(0, coverage.getPixel(4, 24) and 0xFF)
        assertEquals(255, coverage.getPixel(60, 24) and 0xFF)
    }

    @Test
    fun shouldRejectLocalMask_whenProviderHasNoMaskChannel() = runBlocking {
        consent.accept()
        val provider = RecordingProvider(
            capabilities = capabilities(CloudMaskMode.NONE)
        )

        val outcome = service(provider).execute(
            CloudEditCommand(
                mode = CloudEditMode.PAINTED_MASK,
                prompt = "repaint",
                geometry = GeometryOps(),
                source = solidBitmap(32, 24, Color.BLUE),
                paintedMask = paintedHalfBitmap(32, 24)
            )
        ) {}

        assertEquals(
            CloudEditInputIssue.MASK_NOT_SUPPORTED,
            (outcome as? CloudEditOutcome.InvalidInput)?.issue
        )
        assertEquals(0, provider.editCalls)
        assertStagingDirectoriesAreEmpty()
    }

    @Test
    fun shouldRequireConsent_whenConsentWasNotAccepted() = runBlocking {
        val provider = RecordingProvider()

        val outcome = service(provider).execute(wholeCommand()) {}

        assertTrue(outcome is CloudEditOutcome.ConsentRequired)
        assertEquals(0, provider.editCalls)
    }

    @Test
    fun shouldReportProviderUnavailable_whenCloudIsDisabled() = runBlocking {
        consent.accept()
        val provider = RecordingProvider()

        val outcome = service(provider, enabled = false).execute(wholeCommand()) {}

        assertTrue(outcome is CloudEditOutcome.ProviderUnavailable)
        assertEquals(0, provider.editCalls)
    }

    @Test
    fun shouldDeleteStagedUploads_whenProviderFails() = runBlocking {
        consent.accept()
        val provider = RecordingProvider(
            result = CloudEditResult.Failure(CloudEditError.Server(500))
        )

        val outcome = service(provider).execute(
            CloudEditCommand(
                mode = CloudEditMode.PAINTED_MASK,
                prompt = "repaint",
                geometry = GeometryOps(),
                source = solidBitmap(64, 48, Color.BLUE),
                paintedMask = paintedHalfBitmap(64, 48)
            )
        ) {}

        assertTrue(outcome is CloudEditOutcome.Failed)
        assertStagingDirectoriesAreEmpty()
        assertNull("A failed request must not leave a photo directory", photoDirectory())
    }

    @Test
    fun shouldDeleteStagedUploads_whenTheJobIsCancelled() = runBlocking {
        consent.accept()
        val started = CompletableDeferred<Unit>()
        val provider = RecordingProvider(
            behavior = {
                started.complete(Unit)
                awaitCancellation()
            }
        )

        val job = launch(Dispatchers.Default) {
            service(provider).execute(
                CloudEditCommand(
                    mode = CloudEditMode.PAINTED_MASK,
                    prompt = "repaint",
                    geometry = GeometryOps(),
                    source = solidBitmap(64, 48, Color.BLUE),
                    paintedMask = paintedHalfBitmap(64, 48)
                )
            ) {}
        }
        withTimeout(CANCEL_TIMEOUT_MS) { started.await() }
        job.cancelAndJoin()

        assertStagingDirectoriesAreEmpty()
        assertNull("A cancelled request must not leave a photo directory", photoDirectory())
    }

    private fun service(provider: CloudEditProvider, enabled: Boolean = true): CloudEditService =
        CloudEditService(
            photoId = PHOTO_ID,
            providers = FakeProviderSource(enabled, provider),
            consent = consent,
            uploads = sanitizer,
            assets = assets,
            maskDirectory = maskDir
        )

    private fun appliedLayer(outcome: CloudEditOutcome): CloudEditOutcome.Applied =
        outcome as? CloudEditOutcome.Applied
            ?: throw AssertionError("Expected an applied layer")

    private fun wholeCommand(): CloudEditCommand = CloudEditCommand(
        mode = CloudEditMode.WHOLE_IMAGE,
        prompt = "make it blue",
        geometry = GeometryOps(),
        source = solidBitmap(32, 24, Color.BLUE)
    )

    private fun photoDirectory(): File? =
        File(filesDir, "${AiAssetStore.EDITS_DIR}/$PHOTO_ID").takeIf { it.exists() }

    private fun assertStagingDirectoriesAreEmpty() {
        assertTrue("The upload folder must be empty", uploadDir.listFiles().isNullOrEmpty())
        assertTrue("The mask folder must be empty", maskDir.listFiles().isNullOrEmpty())
    }

    private fun assertMaskPixel(png: ByteArray, x: Int, y: Int, expectedAlpha: Int) {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw AssertionError("The provider mask was not a decodable PNG")
        try {
            assertEquals(expectedAlpha, Color.alpha(bitmap.getPixel(x, y)))
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeGrayscale(png: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw AssertionError("The blend mask was not a decodable PNG")

    private fun digestOf(path: String): String =
        path.substringAfterLast('_').substringBeforeLast('.')

    private fun successPng(width: Int, height: Int): CloudEditResult =
        CloudEditResult.Success(imageBytes(width, height), "image/png")

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun paintedHalfBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f,
            0f,
            width / 2f,
            height.toFloat(),
            Paint().apply { color = Color.WHITE }
        )
        return bitmap
    }

    private fun capabilities(maskMode: CloudMaskMode): CloudProviderCapabilities =
        CloudProviderCapabilities(
            maskMode = maskMode,
            maxImages = 1,
            maxUploadBytes = 32L * 1024L * 1024L,
            supportsNegativePrompt = false,
            supportsSeed = false,
            blocking = true
        )

    private class RecordingProvider(
        override val kind: CloudProviderKind = CloudProviderKind.OPENAI_COMPATIBLE,
        override val capabilities: CloudProviderCapabilities = CloudProviderCapabilities(
            maskMode = CloudMaskMode.HARD,
            maxImages = 1,
            maxUploadBytes = 32L * 1024L * 1024L,
            supportsNegativePrompt = false,
            supportsSeed = false,
            blocking = true
        ),
        private val result: CloudEditResult = CloudEditResult.Success(imageBytes(8, 6), "image/png"),
        private val behavior: suspend () -> Unit = {}
    ) : CloudEditProvider {

        var editCalls: Int = 0
            private set

        var capture: Capture? = null
            private set

        override suspend fun edit(
            request: CloudEditRequest,
            onStage: (CloudJobStage) -> Unit
        ): CloudEditResult {
            editCalls++
            capture = Capture(
                width = request.image.width,
                height = request.image.height,
                prompt = request.prompt,
                imageBytes = request.image.file.readBytes(),
                maskBytes = request.mask?.file?.readBytes(),
                maskWidth = request.mask?.width,
                maskHeight = request.mask?.height
            )
            onStage(CloudJobStage.SUBMITTING)
            behavior()
            return result
        }

        override suspend fun testConnection(): CloudConnectionResult =
            CloudConnectionResult.Reachable(1L)
    }

    private class Capture(
        val width: Int,
        val height: Int,
        val prompt: String,
        val imageBytes: ByteArray,
        val maskBytes: ByteArray?,
        val maskWidth: Int?,
        val maskHeight: Int?
    )

    private class FakeProviderSource(
        private val enabled: Boolean,
        private val provider: CloudEditProvider?
    ) : CloudProviderSource {

        override fun isCloudEnabled(): Boolean = enabled

        override fun activeProfile(): CloudProviderProfile? = null

        override fun createProvider(): Result<CloudEditProvider> =
            if (!enabled) {
                Result.failure(IllegalStateException("Cloud edits are disabled"))
            } else {
                provider?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("No provider profile"))
            }
    }

    private companion object {
        const val PHOTO_ID = 7L
        const val CANCEL_TIMEOUT_MS = 5_000L
    }
}
