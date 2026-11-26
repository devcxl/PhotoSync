package cn.devcxl.photosync.editor.render

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color

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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.HalfFloatBits
import cn.devcxl.photosync.editor.domain.LinearCloudLayer
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Seam under test: the Post-Pass A / Pre-Pass B mount point of a `CloudEditLayer`.
 *
 * The cloud layer must be fused *before* Pass B, so the very same exposure, white balance and tone
 * parameters grade it together with the camera frame. These tests pin that order on the CPU
 * reference implementation and the bake's per-band UV mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudPassBOrderingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var assets: AiAssetStore

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        assets = AiAssetStore(filesDir)
    }

    @Test
    fun shouldGradeTheCloudResultWithPassB_whenExposureIsRaised() {
        val cloud = LinearCloudLayer(
            width = FRAME,
            height = FRAME,
            linearRgb = FloatArray(FRAME * FRAME * 3) { MID_GRAY },
            opacity = 1.0f
        )
        val source = midGrayBuffer(FRAME, FRAME)
        val neutral = LinearRawPreviewRenderer.renderArgb(
            source = source,
            graph = EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL),
            fullResolutionShortEdge = FRAME,
            cloud = cloud
        )
        val exposed = LinearRawPreviewRenderer.renderArgb(
            source = source,
            graph = EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL, tone = ToneOps(exposure = 1.0f)),
            fullResolutionShortEdge = FRAME,
            cloud = cloud
        )

        val neutralRed = Color.red(neutral[0])
        val exposedRed = Color.red(exposed[0])
        // The cloud pixel is 0.2 linear: +1 EV must brighten it, which only happens when Pass B runs
        // after the layer was fused. A blend after Pass B would leave the value at encodeSrgb(0.2).
        assertEquals(encodeSrgbByte(MID_GRAY).toFloat(), neutralRed.toFloat(), 1.0f)
        assertTrue("expected $exposedRed to exceed $neutralRed", exposedRed > neutralRed)
    }

    @Test
    fun shouldLeaveThePlateUnchanged_whenNoCloudLayerIsSupplied() {
        val source = midGrayBuffer(FRAME, FRAME)

        val without = LinearRawPreviewRenderer.renderArgb(
            source = source,
            graph = EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL),
            fullResolutionShortEdge = FRAME
        )
        val exposed = LinearRawPreviewRenderer.renderArgb(
            source = source,
            graph = EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL, tone = ToneOps(exposure = 1.0f)),
            fullResolutionShortEdge = FRAME
        )

        assertEquals(encodeSrgbByte(MID_GRAY), Color.red(without[0]))
        assertTrue(Color.red(exposed[0]) > Color.red(without[0]))
    }

    @Test
    fun shouldSkipTheLayer_whenTheGeometryDigestDoesNotMatch() {
        val boundGeometry = GeometryOps()
        val layer = writeLayer(boundGeometry)
        val matching = EditGraph(cloudLayer = layer)
        val mismatched = EditGraph(
            geometry = GeometryOps(crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f)),
            cloudLayer = layer
        )

        assertNotNull(CloudPreviewBlend.prepare(assets, matching, FRAME, FRAME))
        assertNull(CloudPreviewBlend.prepare(assets, mismatched, FRAME, FRAME))
        assertTrue(CloudPreviewBlend.isBound(matching))
        assertTrue(!CloudPreviewBlend.isBound(mismatched))
    }

    @Test
    fun shouldMapEveryBakeBandOntoTheFullFrame_whenTheCloudLayerIsSampled() {
        val cropWidth = 4000
        val cropHeight = 3000
        val strip = ColumnBandTiling.rowStrip(cropHeight, 0)
        val bands = ColumnBandTiling.columnBands(cropWidth, cropHeight, strip)
        assertTrue(bands.size > 1)

        bands.forEach { band ->
            val left = band.tileUv.mapU(0.0f) * cropWidth
            val right = band.tileUv.mapU(1.0f) * cropWidth
            val top = band.tileUv.mapV(0.0f) * cropHeight
            val bottom = band.tileUv.mapV(1.0f) * cropHeight
            // Band-local (0,0)..(1,1) must cover exactly the band's physical extent in the frame,
            // so neighbouring bands sample the cloud texture without a seam.
            assertEquals((band.x0 - band.halo).toFloat(), left, 0.5f)
            assertEquals((band.x0 - band.halo + band.fboWidth).toFloat(), right, 0.5f)
            assertEquals((band.y0 - band.halo).toFloat(), top, 0.5f)
            assertEquals((band.y0 - band.halo + band.fboHeight).toFloat(), bottom, 0.5f)
        }
    }

    @Test
    fun shouldFoldTheLayerOpacityIntoTheBakeFrame_whenTheDigestMatches() {
        val layer = writeLayer(GeometryOps()).copy(opacity = 40)
        val matching = EditGraph(cloudLayer = layer)
        val mismatched = EditGraph(
            geometry = GeometryOps(rotate90 = 1),
            cloudLayer = layer
        )

        assertEquals(0.4f, cn.devcxl.photosync.editor.export.BakeFrameFactory.cloudLayerOpacity(matching), 1e-4f)
        assertEquals(
            0.0f,
            cn.devcxl.photosync.editor.export.BakeFrameFactory.cloudLayerOpacity(mismatched),
            1e-4f
        )
    }

    private fun writeLayer(geometry: GeometryOps): CloudEditLayerRef {
        val bitmap = Bitmap.createBitmap(FRAME, FRAME, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
        val ref = requireNotNull(assets.write(PHOTO_ID, AiAssetKind.CLOUD_LAYER, bytes)) {
            "the cloud layer fixture could not be stored"
        }
        return CloudEditLayerRef(
            imageRelPath = ref.relativePath,
            sha256 = ref.sha256,
            opacity = 100,
            geometryDigest = CloudGeometryDigest.digest(geometry)
        )
    }

    /** A `FRAME x FRAME` scene-linear buffer with every pixel at [MID_GRAY]. */
    private fun midGrayBuffer(width: Int, height: Int): LinearRawBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        val bits = HalfFloatBits.toBits(MID_GRAY)
        repeat(width * height) {
            buffer.putShort(bits)
            buffer.putShort(bits)
            buffer.putShort(bits)
            buffer.putShort(HalfFloatBits.toBits(1.0f))
        }
        buffer.rewind()
        return LinearRawBuffer(
            width = width,
            height = height,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgba16fBuffer = buffer
        )
    }

    private fun encodeSrgbByte(linear: Float): Int {
        val encoded = LinearRawPreviewRendererTestProbe.encodeSrgb(linear)
        return (encoded * 255.0f + 0.5f).toInt()
    }

    private companion object {
        const val FRAME = 4
        const val PHOTO_ID = 42L
        const val MID_GRAY = 0.2f
        const val BYTES_PER_PIXEL = 8
    }
}

/** Access to the sRGB transfer function without widening the renderer's public surface. */
private object LinearRawPreviewRendererTestProbe {

    fun encodeSrgb(value: Float): Float {
        val clamped = value.coerceIn(0.0f, 1.0f)
        return if (clamped <= 0.0031308f) {
            clamped * 12.92f
        } else {
            1.055f * Math.pow(clamped.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        }
    }
}
