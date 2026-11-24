package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnBandTilingTest {

    @Test
    fun shouldSplitRowStripsOf512_whenFrameIs4000RowsHigh() {
        val strips = ColumnBandTiling.rowStrips(4000)
        assertEquals(8, strips.size)
        assertEquals(RowStrip(0, 512), strips.first())
        assertEquals(RowStrip(3584, 416), strips.last())
    }

    @Test
    fun shouldKeepFullStrip_whenFrameHeightIsMultipleOf512() {
        val strips = ColumnBandTiling.rowStrips(1024)
        assertEquals(2, strips.size)
        assertEquals(512, strips.last().height)
    }

    @Test
    fun shouldSplitColumnBandsOf1920_whenFrameIs6000ColumnsWide() {
        val strip = ColumnBandTiling.rowStrip(4000, 0)
        val bands = ColumnBandTiling.columnBands(6000, 4000, strip)
        assertEquals(listOf(1920, 1920, 1920, 240), bands.map { it.validWidth })
        assertEquals(listOf(0, 1920, 3840, 5760), bands.map { it.x0 })
    }

    @Test
    fun shouldConfineEveryBandFbo_whenExporting100Mp() {
        val width = 11648
        val height = 8736
        var bandCount = 0
        for (strip in ColumnBandTiling.rowStrips(height)) {
            for (band in ColumnBandTiling.columnBands(width, height, strip)) {
                bandCount++
                assertTrue(band.fboWidth <= ColumnBandTiling.MAX_FBO_WIDTH)
                assertTrue(band.fboHeight <= ColumnBandTiling.MAX_FBO_HEIGHT)
                assertTrue(band.fboWidth <= 2048)
                assertTrue(band.fboHeight <= 640)
            }
        }
        assertEquals(ColumnBandTiling.stripCount(height) * ColumnBandTiling.bandCount(width), bandCount)
    }

    @Test
    fun shouldCoverEveryPixelExactlyOnce_whenBandsAreScattered() {
        val width = 9504
        val height = 6336
        val strip = ColumnBandTiling.rowStrip(height, 3)
        val bands = ColumnBandTiling.columnBands(width, height, strip)
        assertEquals(width, bands.sumOf { it.validWidth })
        assertTrue(bands.all { it.validHeight == strip.height })
    }

    @Test
    fun shouldScatterReadPixelsWithFullWidthStride_whenReadingSecondBand() {
        val width = 9504
        val height = 6336
        val strip = ColumnBandTiling.rowStrip(height, 2)
        val band = ColumnBandTiling.columnBand(width, height, strip, 1)
        val read = ColumnBandTiling.readPlan(band, width)
        assertEquals(64, read.readX)
        assertEquals(64, read.readY)
        assertEquals(1920, read.readWidth)
        assertEquals(512, read.readHeight)
        assertEquals(width, read.packRowLength)
        assertEquals(1920, read.packSkipPixels)
        assertEquals(4, read.packAlignment)
    }

    @Test
    fun shouldStripHalo_whenReadingLastPartialBand() {
        val width = 6000
        val height = 4000
        val strip = ColumnBandTiling.rowStrip(height, 7)
        val band = ColumnBandTiling.columnBand(width, height, strip, 3)
        assertEquals(240, band.validWidth)
        assertEquals(416, band.validHeight)
        assertEquals(368, band.fboWidth)
        assertEquals(544, band.fboHeight)
        val read = ColumnBandTiling.readPlan(band, width)
        assertEquals(64, read.readX)
        assertEquals(64, read.readY)
        assertEquals(240, read.readWidth)
        assertEquals(416, read.readHeight)
        assertEquals(5760, read.packSkipPixels)
    }

    @Test
    fun shouldKeepOutputSize_whenGeometryIsIdentity() {
        val size = ColumnBandTiling.outputSize(6000, 4000, GeometryOps())
        assertEquals(6000 to 4000, size)
    }

    @Test
    fun shouldSwapOutputSize_whenRotatedBy90Degrees() {
        val size = ColumnBandTiling.outputSize(
            6000,
            4000,
            GeometryOps(rotate90 = 1)
        )
        assertEquals(4000 to 6000, size)
    }

    @Test
    fun shouldHalveOutputSize_whenCropIsHalfTheFrame() {
        val size = ColumnBandTiling.outputSize(
            6000,
            4000,
            GeometryOps(crop = CropRect(left = 0.0f, top = 0.0f, right = 0.5f, bottom = 0.5f))
        )
        assertEquals(3000 to 2000, size)
    }

    @Test
    fun shouldUseIdentityTransform_whenRenderingTheWholeFrame() {
        val band = ColumnBandTiling.fullFrame(1200, 800)
        assertEquals(0, band.halo)
        assertEquals(1200, band.fboWidth)
        assertEquals(800, band.fboHeight)
        assertEquals(0.0f, band.tileUv.mapU(0.0f), 0.0f)
        assertEquals(1.0f, band.tileUv.mapV(1.0f), 0.0f)
    }
}
