package cn.devcxl.photosync.editor.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.junit.Assert.assertEquals
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColumnBandTiling

/**
 * Seam under test: [BakeBandEncoder.scatterBandIntoStrip] — verifies that tightly-packed column
 * bands assemble into an exact, seam-free, top-down full-width RGBA8 row strip without relying on
 * driver-specific `GL_PACK_ROW_LENGTH` / `GL_PACK_SKIP_PIXELS`.
 */
class BakeBandScatterTest {

    @Test
    fun shouldAssembleAllColumnBandsIntoExactTopDownPixels_whenScatteringMultiBandStrip() {
        val cropWidth = 4000
        val cropHeight = 600
        val stripRows = ColumnBandTiling.STRIP_HEIGHT
        val maxBandWidth = ColumnBandTiling.MAX_BAND_WIDTH
        val rowBuffer = ByteBuffer.allocateDirect(cropWidth * stripRows * 4).order(ByteOrder.nativeOrder())
        val bandBuffer = ByteBuffer.allocateDirect(maxBandWidth * stripRows * 4).order(ByteOrder.nativeOrder())
        val scratch = ByteArray(maxBandWidth * 4)

        for (strip in ColumnBandTiling.rowStrips(cropHeight)) {
            for (band in ColumnBandTiling.columnBands(cropWidth, cropHeight, strip)) {
                bandBuffer.clear()
                for (row in 0 until band.validHeight) {
                    val y = strip.y0 + row
                    for (col in 0 until band.validWidth) {
                        val x = band.x0 + col
                        bandBuffer.put((x and 0xFF).toByte())
                        bandBuffer.put(((x ushr 8) and 0xFF).toByte())
                        bandBuffer.put((y and 0xFF).toByte())
                        bandBuffer.put(((y ushr 8) and 0xFF).toByte())
                    }
                }
                BakeBandEncoder.scatterBandIntoStrip(
                    bandBuffer = bandBuffer,
                    rowBuffer = rowBuffer,
                    rowScratch = scratch,
                    bandWidth = band.validWidth,
                    bandHeight = band.validHeight,
                    bandX0 = band.x0,
                    cropWidth = cropWidth
                )
            }
            // Verify every seam and corner of the assembled top-down strip
            val probeXs = intArrayOf(0, 1919, 1920, 3839, 3840, cropWidth - 1)
            val probeRows = intArrayOf(0, strip.height / 2, strip.height - 1)
            for (row in probeRows) {
                val expectedY = strip.y0 + row
                for (x in probeXs) {
                    val offset = (row * cropWidth + x) * 4
                    val actualX = (rowBuffer.get(offset).toInt() and 0xFF) or
                        ((rowBuffer.get(offset + 1).toInt() and 0xFF) shl 8)
                    val actualY = (rowBuffer.get(offset + 2).toInt() and 0xFF) or
                        ((rowBuffer.get(offset + 3).toInt() and 0xFF) shl 8)
                    assertEquals("pixel X mismatch at ($x, $expectedY)", x, actualX)
                    assertEquals("pixel Y mismatch at ($x, $expectedY)", expectedY, actualY)
                }
            }
        }
    }
}
