package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileUvContinuityTest {

    private val cropWidth = 5000
    private val cropHeight = 4000

    @Test
    fun shouldMapAdjacentBandsToTheSameGlobalU_whenTheirEdgesTouch() {
        val strip = ColumnBandTiling.rowStrip(cropHeight, 0)
        val bands = ColumnBandTiling.columnBands(cropWidth, cropHeight, strip)
        for (index in 0 until bands.size - 1) {
            val left = bands[index]
            val right = bands[index + 1]
            val sharedGlobalX = right.x0.toFloat()
            val localLeft = (sharedGlobalX - (left.x0 - left.halo)) / left.fboWidth
            val localRight = (sharedGlobalX - (right.x0 - right.halo)) / right.fboWidth
            assertEquals(
                left.tileUv.mapU(localLeft),
                right.tileUv.mapU(localRight),
                1e-6f
            )
        }
    }

    @Test
    fun shouldMapAdjacentRowStripsToTheSameGlobalV_whenTheirEdgesTouch() {
        val strips = ColumnBandTiling.rowStrips(cropHeight)
        val bandOfFirst = ColumnBandTiling.columnBand(cropWidth, cropHeight, strips[0], 0)
        val bandOfSecond = ColumnBandTiling.columnBand(cropWidth, cropHeight, strips[1], 0)
        val sharedGlobalY = strips[1].y0.toFloat()
        val localFirst = (sharedGlobalY - (bandOfFirst.y0 - bandOfFirst.halo)) /
            bandOfFirst.fboHeight
        val localSecond = (sharedGlobalY - (bandOfSecond.y0 - bandOfSecond.halo)) /
            bandOfSecond.fboHeight
        assertEquals(
            bandOfFirst.tileUv.mapV(localFirst),
            bandOfSecond.tileUv.mapV(localSecond),
            1e-6f
        )
    }

    @Test
    fun shouldSpanFullGlobalRange_whenBandCoversTheWholeFrame() {
        val band = ColumnBandTiling.fullFrame(cropWidth, cropHeight)
        assertEquals(0.0f, band.tileUv.mapU(0.0f), 1e-6f)
        assertEquals(1.0f, band.tileUv.mapU(1.0f), 1e-6f)
        assertEquals(0.0f, band.tileUv.mapV(0.0f), 1e-6f)
        assertEquals(1.0f, band.tileUv.mapV(1.0f), 1e-6f)
    }

    @Test
    fun shouldIncludeHalo_whenMappingBandOrigin() {
        val strip = ColumnBandTiling.rowStrip(cropHeight, 1)
        val band = ColumnBandTiling.columnBand(cropWidth, cropHeight, strip, 2)
        val globalLeft = band.tileUv.mapU(0.0f) * cropWidth
        val globalTop = band.tileUv.mapV(0.0f) * cropHeight
        assertEquals((band.x0 - band.halo).toFloat(), globalLeft, 1e-3f)
        assertEquals((band.y0 - band.halo).toFloat(), globalTop, 1e-3f)
    }

    @Test
    fun shouldBoundSourceSliceWithMargin_whenTransformIsIdentity() {
        val band = ColumnBandTiling.columnBand(
            cropWidth = 6000,
            cropHeight = 4000,
            strip = ColumnBandTiling.rowStrip(4000, 1),
            bandIndex = 1
        )
        val request = SourceSlicePlanner.plan(
            postCropToPreCrop = Mat3.identity(),
            band = band,
            cropWidth = 6000,
            cropHeight = 4000,
            sourceWidth = 6000,
            sourceHeight = 4000
        )
        assertEquals(1852, request.bounds.left)
        assertEquals(444, request.bounds.top)
        assertEquals(3908, request.bounds.right)
        assertEquals(1092, request.bounds.bottom)
        assertFalse(request.exceedsUploadLimit)
        assertEquals(1852f / 6000f, request.rect.x0, 1e-6f)
        assertEquals(2056f / 6000f, request.rect.width, 1e-6f)
        assertEquals(648f / 4000f, request.rect.height, 1e-6f)
    }

    @Test
    fun shouldScaleSourceSliceByCrop_whenGeometryCropsHalfTheFrame() {
        val geometry = GeometryOps(
            crop = CropRect(left = 0.0f, top = 0.0f, right = 0.5f, bottom = 1.0f)
        )
        val inverse = GeometryMath.buildPassAInverseMatrix(geometry, 6000f / 4000f)
        val outWidth = 3000
        val outHeight = 4000
        val band = ColumnBandTiling.columnBand(
            cropWidth = outWidth,
            cropHeight = outHeight,
            strip = ColumnBandTiling.rowStrip(outHeight, 0),
            bandIndex = 0
        )
        val request = SourceSlicePlanner.plan(
            postCropToPreCrop = inverse,
            band = band,
            cropWidth = outWidth,
            cropHeight = outHeight,
            sourceWidth = 6000,
            sourceHeight = 4000
        )
        assertEquals(0, request.bounds.left)
        assertEquals(1988, request.bounds.right)
        assertEquals(0, request.bounds.top)
        assertFalse(request.rect.width <= 0.0f)
    }

    @Test
    fun shouldFlagUploadLimit_whenBoxExceedsTheUploadBudget() {
        val band = ColumnBandTiling.columnBand(
            cropWidth = 6000,
            cropHeight = 4000,
            strip = ColumnBandTiling.rowStrip(4000, 0),
            bandIndex = 0
        )
        val request = SourceSlicePlanner.plan(
            postCropToPreCrop = Mat3.identity(),
            band = band,
            cropWidth = 6000,
            cropHeight = 4000,
            sourceWidth = 6000,
            sourceHeight = 4000,
            maxEdgePx = 1024
        )
        assertTrue(request.exceedsUploadLimit)
    }

    @Test
    fun shouldCoverMoreThanOneBand_whenCropSplitsFrameIntoThreeBands() {
        val band = ColumnBandTiling.columnBand(
            cropWidth = 5000,
            cropHeight = 4000,
            strip = ColumnBandTiling.rowStrip(4000, 0),
            bandIndex = 2
        )
        assertEquals(1160, band.validWidth)
        assertEquals(1160 + 128, band.fboWidth)
        assertEquals(1.0f, band.tileUv.mapU((band.fboWidth - 64f) / band.fboWidth), 1e-6f)
    }
}
