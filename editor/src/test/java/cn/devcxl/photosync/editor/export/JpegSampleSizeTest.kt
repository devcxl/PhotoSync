package cn.devcxl.photosync.editor.export

import org.junit.Assert.assertEquals
import org.junit.Test

class JpegSampleSizeTest {

    @Test
    fun shouldKeepFullResolution_whenSourceFitsTheUploadBudget() {
        assertEquals(1, jpegSampleSizeFor(6000, 4000, 6144))
    }

    @Test
    fun shouldHalveOnce_whenHalfOfTheLongEdgeStillFits() {
        assertEquals(2, jpegSampleSizeFor(8160, 6120, 4096))
        assertEquals(2, jpegSampleSizeFor(8160, 6120, 6144))
    }

    @Test
    fun shouldHalveTwice_whenAQuarterIsTheFirstFittingPowerOfTwo() {
        assertEquals(4, jpegSampleSizeFor(20000, 15000, 6144))
    }

    @Test
    fun shouldKeepSource_whenBudgetIsDisabled() {
        assertEquals(1, jpegSampleSizeFor(11648, 8736, 0))
    }

    @Test
    fun shouldScaleSmallSourcesToTinyBudgets_whenTargetBudgetIsSmallerThanSource() {
        assertEquals(4, jpegSampleSizeFor(100, 50, 32))
    }
}
