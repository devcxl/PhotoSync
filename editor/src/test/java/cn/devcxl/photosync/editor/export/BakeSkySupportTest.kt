package cn.devcxl.photosync.editor.export

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyBlendParams
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SkyRelightStats
import cn.devcxl.photosync.editor.render.SkyLayerPixels
import cn.devcxl.photosync.editor.vision.SkyMask

/**
 * Seam under test: [BakeSkySupport] — the per-band guided refinement that lifts the 1024px sky mask
 * onto a full-resolution bake band without a column seam.
 */
class BakeSkySupportTest {

    @Test
    fun shouldRefineTheBandMaskAgainstTheBandLuma_whenTheGuideIsAvailable() {
        val width = 64
        val height = 16
        val support = BakeSkySupport(layer(width, height, edgeShift = 0))
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
        }

        val mask = support.refineBandMask(
            pixels = pixels,
            width = width,
            height = height,
            band = ColumnBandTiling.fullFrame(width, height),
            inverseMatrix = Mat3.identity(),
            warpField = null,
            referenceShortEdge = width
        )

        assertNotNull(mask)
        val refined = mask!!
        val left = refined[(height / 2) * width + 8]
        val right = refined[(height / 2) * width + 56]
        assertTrue("left=$left right=$right", left > 0.6f)
        assertTrue("left=$left right=$right", right < 0.4f)
        assertTrue(refined.all { it.isFinite() })
    }

    @Test
    fun shouldFallBackToTheBilinearMask_whenTheGuideIsMissing() {
        val width = 32
        val height = 8
        val support = BakeSkySupport(layer(width, height, edgeShift = 0))

        val mask = support.refineBandMask(
            pixels = null,
            width = width,
            height = height,
            band = ColumnBandTiling.fullFrame(width, height),
            inverseMatrix = Mat3.identity(),
            warpField = null,
            referenceShortEdge = width
        )

        assertNotNull(mask)
        assertTrue(mask!![(height / 2) * width + 4] > 0.5f)
        assertTrue(mask[(height / 2) * width + 28] < 0.5f)
    }

    private fun layer(width: Int, height: Int, edgeShift: Int): SkyLayerPixels = SkyLayerPixels(
        mask = SkyMask(
            width = width,
            height = height,
            probability = FloatArray(width * height) { index ->
                if (index % width < width / 2) 1.0f else 0.0f
            },
            coverageRatio = 0.5f
        ),
        asset = null,
        procedural = ProceduralSkyPreset.CLEAR_BLUE,
        blend = SkyBlendParams(
            procedural = ProceduralSkyPreset.CLEAR_BLUE,
            edgeShift = edgeShift
        ),
        stats = SkyRelightStats.NEUTRAL,
        ops = SkyOps(
            assetType = SkyAssetType.PROCEDURAL,
            assetIdOrRelPath = ProceduralSkyPreset.CLEAR_BLUE.wireId
        )
    )
}
