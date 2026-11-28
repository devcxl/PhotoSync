package cn.devcxl.photosync.editor.export

import java.io.File
import kotlin.math.abs

import android.content.Context
import android.graphics.Bitmap

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import kotlinx.coroutines.runBlocking

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.LutRef
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.vision.SkyMaskCodec
import cn.devcxl.photosync.editor.vision.SubjectMaskCodec

/**
 * 24 MP golden regression of the three AI export paths on a real device.
 *
 * Each case builds a synthetic `6000 x 4000` JPEG, stages the exact AI payload the editor would have
 * written into [AiAssetStore], and runs the production [BakeExporter]: the headless EGL context, the
 * `1920 x 512` column-band loop, the subject/sky band refiners and the container encoders are all
 * exercised at full resolution. Every case then reads the exported file back and checks the container
 * dimensions, the produced alpha or colour, the absence of an out-of-memory [BakeResult.Failure] and
 * the continuity of pixels across the band boundaries.
 *
 * The repository ships no 24 MP RAW sample, so all three cases use a JPEG source. A RAW export keeps
 * its existing (smaller) fixture coverage; this suite does not claim a 24 MP RAW run.
 */
@RunWith(AndroidJUnit4::class)
class AiBakeGoldenInstrumentedTest {

    private lateinit var context: Context
    private lateinit var workDir: File
    private lateinit var galleryDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDir = File(context.cacheDir, "ai-golden").apply {
            deleteRecursively()
            mkdirs()
        }
        galleryDir = File(workDir, "gallery")
    }

    @After
    fun tearDown() {
        AiAssetStore(context.filesDir).deletePhotoAssets(AiGoldenFixtures.PHOTO_ID)
        workDir.deleteRecursively()
    }

    @Test
    fun shouldExportTransparentCutoutAtFullResolution_whenBakingA24MegapixelJpeg() {
        val source = AiGoldenFixtures.createJpeg(workDir, "cutout.jpg")
        val maskRef = stageSubjectMask()
        val graph = EditGraph(
            subject = SubjectOps(maskRef = maskRef, bgMode = SubjectBackgroundMode.TRANSPARENT)
        )

        val output = bake(source, graph, BakeOutputFormat.PNG_RGBA, "cutout.png")

        val bitmap = AiGoldenFixtures.decode(output)
        try {
            assertEquals(AiGoldenFixtures.WIDTH, bitmap.width)
            assertEquals(AiGoldenFixtures.HEIGHT, bitmap.height)
            val foreground = AiGoldenFixtures.alphaAt(bitmap, 200, 2000)
            val background = AiGoldenFixtures.alphaAt(bitmap, 5800, 2000)
            assertTrue("foreground alpha=$foreground", foreground >= 250)
            assertTrue("background alpha=$background", background <= 5)
            val centre = AiGoldenFixtures.alphaAt(bitmap, 1920, 2000)
            assertTrue("the mask ramp must reach the band boundary, alpha=$centre", centre in 20..235)
            assertColumnDelta(bitmap, 1000, tolerance = 10, sample = AiGoldenFixtures::pixelAlpha)
            assertColumnDelta(bitmap, 3000, tolerance = 10, sample = AiGoldenFixtures::pixelAlpha)
            assertRowStripDelta(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun shouldCompositeAndGradeTheInpaintPatch_whenBakingA24MegapixelJpeg() {
        val source = AiGoldenFixtures.createJpeg(workDir, "inpaint.jpg")
        val graph = EditGraph(
            tone = ToneOps(exposure = 0.5f),
            inpaint = listOf(stageGreenPatch())
        )

        val output = bake(source, graph, BakeOutputFormat.JPEG, "inpaint.jpg")

        val bitmap = AiGoldenFixtures.decode(output)
        try {
            assertEquals(AiGoldenFixtures.WIDTH, bitmap.width)
            assertEquals(AiGoldenFixtures.HEIGHT, bitmap.height)
            val inside = bitmap.getPixel(1900, 2000)
            val red = AiGoldenFixtures.channel(inside, 16)
            val green = AiGoldenFixtures.channel(inside, 8)
            val blue = AiGoldenFixtures.channel(inside, 0)
            assertTrue("patch must stay green: r=$red g=$green b=$blue", green > 170)
            assertTrue("+0.5 EV must lift the patch: g=$green", green > red + 90 && green > blue + 60)
            val outside = bitmap.getPixel(2600, 2000)
            assertTrue(
                "outside the patch must stay neutral: ${Integer.toHexString(outside)}",
                AiGoldenFixtures.channel(outside, 8) < AiGoldenFixtures.channel(outside, 16) + 20
            )
            assertColumnDelta(bitmap, 2000, tolerance = 10, sample = AiGoldenFixtures::pixelRed)
            assertColumnDelta(bitmap, 2048, tolerance = 10, sample = AiGoldenFixtures::pixelRed)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun shouldCompositeTheProceduralSkyWithALut_whenBakingA24MegapixelJpeg() {
        val source = AiGoldenFixtures.createJpeg(workDir, "sky.jpg")
        val lutId = importIdentityLut()
        val graph = EditGraph(
            color = ColorOps(lut = LutRef(id = lutId, strength = 100)),
            sky = SkyOps(
                maskRef = stageSkyMask(),
                assetType = SkyAssetType.PROCEDURAL,
                assetIdOrRelPath = ProceduralSkyPreset.SUNSET_GLOW.wireId,
                relight = 60,
                horizonFeather = 40
            )
        )

        val output = bake(source, graph, BakeOutputFormat.JPEG, "sky.jpg")

        val bitmap = AiGoldenFixtures.decode(output)
        try {
            assertEquals(AiGoldenFixtures.WIDTH, bitmap.width)
            assertEquals(AiGoldenFixtures.HEIGHT, bitmap.height)
            val before = AiGoldenFixtures.regionAverage(source, 900, 200, 64)
            val after = AiGoldenFixtures.regionAverage(output, 900, 200, 64)
            val delta = (0..2).maxOf { abs(before[it] - after[it]) }
            assertTrue(
                "the sky must be replaced: before=${before.toList()} after=${after.toList()}",
                delta > 20
            )
            val beforeCoolness = before[2] - before[0]
            val afterCoolness = after[2] - after[0]
            assertTrue(
                "the new sky must read as the requested sunset zenith: " +
                    "before=$beforeCoolness after=$afterCoolness",
                afterCoolness - beforeCoolness > 20
            )
            assertColumnDelta(bitmap, 300, tolerance = 10, sample = AiGoldenFixtures::pixelRed)
            assertColumnDelta(bitmap, 3500, tolerance = 10, sample = AiGoldenFixtures::pixelRed)
        } finally {
            bitmap.recycle()
            CubeLutStore(context.filesDir).cacheFile(lutId)?.delete()
        }
    }

    private fun bake(
        source: File,
        graph: EditGraph,
        format: BakeOutputFormat,
        name: String
    ): File {
        val exporter = BakeExporter(context, FileGallerySink(galleryDir), context.filesDir)
        val result = runBlocking {
            exporter.bake(
                BakeRequest(
                    sourcePath = source.absolutePath,
                    displayName = name,
                    isRaw = false,
                    graph = graph,
                    outputFormat = format,
                    photoId = AiGoldenFixtures.PHOTO_ID
                )
            )
        }
        assertTrue("bake failed: $result", result is BakeResult.Success)
        val output = File(galleryDir, name)
        assertTrue("exported file missing: ${output.name}", output.isFile)
        return output
    }

    private fun stageSubjectMask(): String {
        val payload = checkNotNull(SubjectMaskCodec.encode(AiGoldenFixtures.cutoutMask()))
        val ref = AiAssetStore(context.filesDir)
            .write(AiGoldenFixtures.PHOTO_ID, AiAssetKind.SUBJECT_MASK, payload)
        return checkNotNull(ref).relativePath
    }

    private fun stageSkyMask(): String {
        val payload = checkNotNull(SkyMaskCodec.encode(AiGoldenFixtures.skyMask()))
        val ref = AiAssetStore(context.filesDir)
            .write(AiGoldenFixtures.PHOTO_ID, AiAssetKind.SKY_MASK, payload)
        return checkNotNull(ref).relativePath
    }

    private fun stageGreenPatch(): InpaintPatchRef {
        val (patchPng, maskPng) = AiGoldenFixtures.greenPatchPayload()
        val store = AiAssetStore(context.filesDir)
        val patch = checkNotNull(
            store.write(AiGoldenFixtures.PHOTO_ID, AiAssetKind.INPAINT_PATCH, patchPng)
        )
        val mask = checkNotNull(
            store.write(AiGoldenFixtures.PHOTO_ID, AiAssetKind.INPAINT_MASK, maskPng)
        )
        return AiGoldenFixtures.greenPatchRef(patch.relativePath, mask.relativePath, patch.sha256)
    }

    private fun importIdentityLut(): String {
        val info = CubeLutStore(context.filesDir)
            .importFromStream(
                "golden_identity.cube",
                AiGoldenFixtures.identityLutText().byteInputStream()
            )
            .getOrElse { throw AssertionError("LUT import failed", it) }
        return info.id
    }

    /** Compares columns `1918` and `1922` (both sides of the first `1920 px` band boundary). */
    private fun assertColumnDelta(bitmap: Bitmap, row: Int, tolerance: Int, sample: (Int) -> Int) {
        val left = sample(bitmap.getPixel(BOUNDARY_LEFT, row))
        val right = sample(bitmap.getPixel(BOUNDARY_RIGHT, row))
        assertTrue(
            "column seam at row=$row: left=$left right=$right",
            abs(left - right) <= tolerance
        )
    }

    /** Compares the last row of strip 0 with the first row of strip 1 at the mask ramp. */
    private fun assertRowStripDelta(bitmap: Bitmap) {
        val above = AiGoldenFixtures.alphaAt(bitmap, 1920, 511)
        val below = AiGoldenFixtures.alphaAt(bitmap, 1920, 512)
        assertTrue("row-strip seam: above=$above below=$below", abs(above - below) <= 10)
    }

    private companion object {
        private const val BOUNDARY_LEFT = 1918
        private const val BOUNDARY_RIGHT = 1922
    }
}
