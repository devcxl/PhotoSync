package cn.devcxl.photosync.editor.render

import java.io.ByteArrayOutputStream
import java.io.File

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import cn.devcxl.photosync.editor.cloud.CloudBlendMask
import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.GeometryOps

/**
 * Seam under test: [CloudLayerBlender] — the Post-Pass A / Pre-Pass B fusion and its two guards
 * (content digest, geometry digest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudLayerBlenderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var assets: AiAssetStore
    private lateinit var blender: CloudLayerBlender

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        assets = AiAssetStore(filesDir)
        blender = CloudLayerBlender(assets)
    }

    @Test
    fun shouldLeaveTheFrameUntouched_whenLayerOpacityIsZero() {
        val layer = writeLayer(solidBitmap(4, 4, Color.RED), opacity = 0)
        val frame = IntArray(16) { Color.BLUE }

        val result = blender.blendArgb(frame, 4, 4, layer, GeometryOps())

        assertEquals(CloudBlendStatus.OPACITY_ZERO, result.status)
        assertTrue(frame.all { it == Color.BLUE })
    }

    @Test
    fun shouldReplaceEveryPixel_whenOpacityIsFull() {
        val layer = writeLayer(solidBitmap(4, 4, Color.RED), opacity = 100)
        val frame = IntArray(16) { Color.BLUE }

        val result = blender.blendArgb(frame, 4, 4, layer, GeometryOps())

        assertEquals(CloudBlendStatus.APPLIED, result.status)
        assertEquals(16L, result.blendedPixels)
        assertTrue(frame.all { it == Color.RED })
    }

    @Test
    fun shouldBlendHalfway_whenOpacityIsFifty() {
        val layer = writeLayer(solidBitmap(4, 4, Color.WHITE), opacity = 50)
        val frame = IntArray(16) { Color.BLACK }

        val result = blender.blendArgb(frame, 4, 4, layer, GeometryOps())

        assertEquals(CloudBlendStatus.APPLIED, result.status)
        val pixel = frame.first()
        assertEquals(128.0f, Color.red(pixel).toFloat(), 1.0f)
        assertEquals(128.0f, Color.green(pixel).toFloat(), 1.0f)
        assertEquals(128.0f, Color.blue(pixel).toFloat(), 1.0f)
    }

    @Test
    fun shouldBlendOnlyCoveredPixels_whenAFeatheredMaskIsPresent() {
        val coverage = floatArrayOf(1.0f, 0.5f, 0.0f, 1.0f)
        val layer = writeLayer(
            image = solidBitmap(4, 1, Color.RED),
            opacity = 100,
            coverage = coverage,
            width = 4,
            height = 1
        )
        val frame = IntArray(4) { Color.BLUE }

        val result = blender.blendArgb(frame, 4, 1, layer, GeometryOps())

        assertEquals(CloudBlendStatus.APPLIED, result.status)
        assertEquals(3L, result.blendedPixels)
        assertEquals(Color.RED, frame[0])
        assertEquals(Color.RED, frame[3])
        assertEquals(Color.BLUE, frame[2])
        assertEquals(128.0f, Color.red(frame[1]).toFloat(), 1.0f)
        assertEquals(128.0f, Color.blue(frame[1]).toFloat(), 1.0f)
    }

    @Test
    fun shouldRejectTheLayer_whenItsPayloadWasTamperedWith() {
        val layer = writeLayer(solidBitmap(4, 4, Color.RED), opacity = 100)
        File(filesDir, layer.imageRelPath).writeBytes(byteArrayOf(1, 2, 3))
        val frame = IntArray(16) { Color.BLUE }

        val result = blender.blendArgb(frame, 4, 4, layer, GeometryOps())

        assertEquals(CloudBlendStatus.ASSET_INVALID, result.status)
        assertTrue(frame.all { it == Color.BLUE })
    }

    @Test
    fun shouldRejectTheLayer_whenItsMaskIsMissing() {
        val layer = writeLayer(solidBitmap(4, 4, Color.RED), opacity = 100).copy(
            maskRelPath = "edits/$PHOTO_ID/cloud_mask_${"d".repeat(64)}.png"
        )
        val frame = IntArray(16) { Color.BLUE }

        val result = blender.blendArgb(frame, 4, 4, layer, GeometryOps())

        assertEquals(CloudBlendStatus.ASSET_INVALID, result.status)
        assertTrue(frame.all { it == Color.BLUE })
    }

    @Test
    fun shouldSkipTheLayer_whenTheGeometryDigestMismatches() {
        val boundGeometry = GeometryOps(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f))
        val layer = writeLayer(solidBitmap(4, 4, Color.RED), opacity = 100, geometry = boundGeometry)
        val frame = IntArray(16) { Color.BLUE }

        val result = blender.blendArgb(frame, 4, 4, layer, GeometryOps())

        assertEquals(CloudBlendStatus.GEOMETRY_MISMATCH, result.status)
        assertTrue(result.isGeometryMismatch)
        assertFalse(result.isApplied)
        assertTrue(frame.all { it == Color.BLUE })
    }

    @Test
    fun shouldBlendInPlace_whenTheDestinationIsABitmap() {
        val layer = writeLayer(solidBitmap(4, 4, Color.RED), opacity = 100)
        val frame = solidBitmap(4, 4, Color.BLUE)

        val result = blender.blendBitmap(frame, layer, GeometryOps())

        assertEquals(CloudBlendStatus.APPLIED, result.status)
        assertEquals(Color.RED, frame.getPixel(2, 2))
    }

    @Test
    fun shouldBlendRgbaFloatFrames_whenThePreviewUsesFloatPixels() {
        val layer = writeLayer(solidBitmap(2, 2, Color.WHITE), opacity = 50)
        val frame = FloatArray(2 * 2 * 4)

        val result = blender.blendRgbaFloat(frame, 2, 2, layer, GeometryOps())

        assertEquals(CloudBlendStatus.APPLIED, result.status)
        assertEquals(0.5f, frame[0], 0.01f)
        assertEquals(0.5f, frame[1], 0.01f)
        assertEquals(0.5f, frame[2], 0.01f)
        assertEquals(0.0f, frame[3], 0.0f)
    }

    @Test
    fun shouldReportNoLayer_whenTheGraphHasNoCloudLayer() {
        val frame = IntArray(4) { Color.BLUE }

        val result = blender.blendArgb(frame, 2, 2, null, GeometryOps())

        assertEquals(CloudBlendStatus.NO_LAYER, result.status)
        assertTrue(frame.all { it == Color.BLUE })
    }

    @Test
    fun shouldFallBackToThePathDigest_whenTheReferenceCarriesNoSha256() {
        val layer = writeLayer(solidBitmap(2, 2, Color.RED), opacity = 100).copy(sha256 = "")
        val frame = IntArray(4) { Color.BLUE }

        val result = blender.blendArgb(frame, 2, 2, layer, GeometryOps())

        assertEquals(CloudBlendStatus.APPLIED, result.status)
        assertTrue(frame.all { it == Color.RED })
    }

    private fun writeLayer(
        image: Bitmap,
        opacity: Int,
        geometry: GeometryOps = GeometryOps(),
        coverage: FloatArray? = null,
        width: Int = image.width,
        height: Int = image.height
    ): CloudEditLayerRef {
        val layerRef = assets.write(PHOTO_ID, AiAssetKind.CLOUD_LAYER, pngBytes(image))
            ?: throw AssertionError("The test layer could not be stored")
        val maskRef = coverage?.let {
            val maskBytes = CloudBlendMask.encode(it, width, height)
                ?: throw AssertionError("The test mask could not be encoded")
            assets.write(PHOTO_ID, AiAssetKind.CLOUD_MASK, maskBytes)
                ?: throw AssertionError("The test mask could not be stored")
        }
        return CloudEditLayerRef(
            imageRelPath = layerRef.relativePath,
            maskRelPath = maskRef?.relativePath,
            sha256 = layerRef.sha256,
            opacity = opacity,
            featherPx = if (coverage == null) 0.0f else 4.0f,
            geometryDigest = CloudGeometryDigest.digest(geometry),
            providerTag = "openai"
        )
    }

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        return output.toByteArray()
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private companion object {
        const val PHOTO_ID = 11L
    }
}
