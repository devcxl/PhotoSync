package cn.devcxl.photosync.editor.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.ProceduralSky
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyBlendParams
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SkyRelightStats
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SkyModelCatalog

/**
 * Seam under test: [SkyPreparer] — the `< 3 %` gate, the installed-pack check and the OKLab relight
 * statistics shared by the canvas and the bake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyPreparerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun shouldRejectTheLayer_whenTheMaskCoversLessThanThreePercent() {
        val source = blueOverBrownSource()
        val graph = proceduralGraph(ProceduralSkyPreset.CLEAR_BLUE)

        val layer = SkyPreparer.prepare(
            source = source,
            graph = graph,
            filesDir = temporaryFolder.root,
            outputWidth = 64,
            outputHeight = 64,
            maskOverride = maskOf(0.01f)
        )

        assertNull(layer)
    }

    @Test
    fun shouldDegradeTheLayer_whenThePackIsNotInstalled() {
        val source = blueOverBrownSource()
        val graph = EditGraph(
            sky = SkyOps(
                assetType = SkyAssetType.PACK,
                assetIdOrRelPath = SkyModelCatalog.CC0_CLOUD_PACK.id,
                assetSha256 = "a".repeat(64)
            )
        )

        val layer = SkyPreparer.prepare(
            source = source,
            graph = graph,
            filesDir = temporaryFolder.root,
            outputWidth = 64,
            outputHeight = 64,
            maskOverride = maskOf(0.50f)
        )

        assertNull(layer)
    }

    @Test
    fun shouldRejectACustomAsset_whenItsSha256IsBlank() {
        val source = blueOverBrownSource()
        val graph = EditGraph(
            sky = SkyOps(
                assetType = SkyAssetType.CUSTOM,
                assetIdOrRelPath = "skies/sky_${"b".repeat(64)}.jpg",
                assetSha256 = ""
            )
        )

        val layer = SkyPreparer.prepare(
            source = source,
            graph = graph,
            filesDir = temporaryFolder.root,
            outputWidth = 64,
            outputHeight = 64,
            maskOverride = maskOf(0.50f)
        )

        assertNull(layer)
    }

    @Test
    fun shouldApplyTheHorizonOffsetOnce_whenBuildingAProceduralCpuLayer() {
        val layer = SkyLayerPixels(
            mask = maskOf(0.5f),
            asset = null,
            procedural = ProceduralSkyPreset.CLEAR_BLUE,
            blend = SkyBlendParams(
                procedural = ProceduralSkyPreset.CLEAR_BLUE,
                horizonOffset = 0.2f
            ),
            stats = SkyRelightStats.NEUTRAL,
            ops = SkyOps(
                assetType = SkyAssetType.PROCEDURAL,
                assetIdOrRelPath = ProceduralSkyPreset.CLEAR_BLUE.wireId
            )
        )

        val cpu = checkNotNull(
            SkyPreparer.cpuLayer(
                layer = layer,
                inverseMatrix = Mat3.identity(),
                warpField = null,
                width = 8,
                height = 8,
                referenceShortEdge = 64
            )
        )
        val expected = FloatArray(3)
        ProceduralSky.sampleSrgb(
            ProceduralSkyPreset.CLEAR_BLUE,
            0.0625f,
            0.0625f,
            0.2f,
            expected
        )

        assertEquals(pack(expected), cpu.skySrgb[0])
    }

    @Test
    fun shouldComputeWarmDeltas_whenTheReplacementSkyIsWarm() {
        val source = blueOverBrownSource()
        val clear = checkNotNull(prepare(source, ProceduralSkyPreset.CLEAR_BLUE))
        val sunset = checkNotNull(prepare(source, ProceduralSkyPreset.SUNSET_GLOW))

        assertNotNull(clear.stats)
        assertTrue(
            "a sunset replacement must be warmer than a clear blue one",
            sunset.stats.deltaA > clear.stats.deltaA
        )
        assertTrue("a sunset replacement must be less blue", sunset.stats.deltaB > clear.stats.deltaB)
    }

    private fun prepare(source: EditorSource, preset: ProceduralSkyPreset) = SkyPreparer.prepare(
        source = source,
        graph = proceduralGraph(preset),
        filesDir = temporaryFolder.root,
        outputWidth = 64,
        outputHeight = 64,
        maskOverride = maskOf(0.50f)
    )

    private fun proceduralGraph(preset: ProceduralSkyPreset): EditGraph = EditGraph(
        sky = SkyOps(
            assetType = SkyAssetType.PROCEDURAL,
            assetIdOrRelPath = preset.wireId
        )
    )

    private fun blueOverBrownSource(): EditorSource {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (row in 0 until 64) {
            val colour = if (row < 32) Color.rgb(70, 130, 210) else Color.rgb(120, 90, 60)
            for (column in 0 until 64) {
                bitmap.setPixel(column, row, colour)
            }
        }
        return EditorSource.BitmapSource(full = bitmap, proxy = bitmap)
    }

    private fun pack(srgb: FloatArray): Int =
        ((srgb[0].coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((srgb[1].coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (srgb[2].coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)

    private fun maskOf(skyFraction: Float): SkyMask {
        val width = 8
        val height = 8
        val skyRows = (height * skyFraction).toInt()
        val probability = FloatArray(width * height) { index ->
            if (index / width < skyRows) 1.0f else 0.0f
        }
        return SkyMask(width, height, probability, SkyMask.coverageOf(probability))
    }
}
