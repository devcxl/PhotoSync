package cn.devcxl.photosync.wrapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegScanlineContractTest {

    @Test
    fun shouldExposeFourByteRgbaRowsAndBaselineQuality_whenCreated() {
        val contract = JpegScanlineContract(width = 6000, height = 4000)
        assertEquals(24_000, contract.rowBytes)
        assertEquals(95, contract.quality)
        assertFalse(contract.isComplete)
        assertEquals(4000, contract.remainingRows)
    }

    @Test
    fun shouldAcceptStrip_whenRowsFitRemainingHeightAndBuffer() {
        val contract = JpegScanlineContract(width = 120, height = 512)
        contract.requireStrip(rows = 512, bufferBytes = 512 * 480)
        assertEquals(512, contract.acceptRows(512))
        assertTrue(contract.isComplete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectStrip_whenRowsExceedRemainingHeight() {
        val contract = JpegScanlineContract(width = 120, height = 512)
        contract.requireStrip(rows = 513, bufferBytes = 513 * 480)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectStrip_whenBufferIsTooSmall() {
        val contract = JpegScanlineContract(width = 120, height = 512)
        contract.requireStrip(rows = 64, bufferBytes = 64 * 480 - 1)
    }

    @Test
    fun shouldClampAcceptedRows_whenMoreRowsThanRemainingAreOffered() {
        val contract = JpegScanlineContract(width = 64, height = 600)
        assertEquals(512, contract.acceptRows(512))
        assertEquals(88, contract.acceptRows(200))
        assertTrue(contract.isComplete)
    }

    @Test
    fun shouldAlignStripsToMcuRows_whenRowsExceedOneMcuBlock() {
        assertEquals(512, JpegScanlineContract.alignedStripRows(512))
        assertEquals(496, JpegScanlineContract.alignedStripRows(511))
        assertEquals(16, JpegScanlineContract.alignedStripRows(16))
        assertEquals(7, JpegScanlineContract.alignedStripRows(7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectQuality_whenItIsOutsideBaselineRange() {
        JpegScanlineContract(width = 10, height = 10, quality = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectSize_whenWidthIsNotPositive() {
        JpegScanlineContract(width = 0, height = 10)
    }
}
