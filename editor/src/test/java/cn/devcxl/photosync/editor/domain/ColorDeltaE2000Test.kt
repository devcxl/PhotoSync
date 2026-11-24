package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the CIE Delta E 2000 implementation against the published Sharma test data set
 * (Sharma, Wu & Dalal, 2005, "The CIEDE2000 Color-Difference Formula"), which is an
 * independent source of truth from this codebase's own math.
 */
class ColorDeltaE2000Test {

    @Test
    fun shouldMatchPublishedSharmaPairs_whenComputingDeltaE2000() {
        val reference = LabColor(50.0, 0.0, -82.7485)

        assertEquals(2.0425, ColorDeltaE2000.deltaE2000(LabColor(50.0, 2.6772, -79.7751), reference), 0.0001)
        assertEquals(2.8615, ColorDeltaE2000.deltaE2000(LabColor(50.0, 3.1571, -77.2803), reference), 0.0001)
        assertEquals(3.4392, ColorDeltaE2000.deltaE2000(LabColor(50.0, 2.8361, -74.0283), reference), 0.0001)
    }

    @Test
    fun shouldReturnZero_whenComparingAColorWithItself() {
        val color = ColorDeltaE2000.fromSrgb(0.42f, 0.18f, 0.91f)
        assertEquals(0.0, ColorDeltaE2000.deltaE2000(color, color), 1e-9)
    }

    @Test
    fun shouldMapSrgbEndpointsToKnownLabValues_whenConvertingFromSrgb() {
        val white = ColorDeltaE2000.fromSrgb(1.0f, 1.0f, 1.0f)
        assertEquals(100.0, white.lightness, 0.01)
        assertEquals(0.0, white.a, 0.01)
        assertEquals(0.0, white.b, 0.01)

        val middleGray = ColorDeltaE2000.fromSrgb(0.5f, 0.5f, 0.5f)
        assertEquals(53.3890, middleGray.lightness, 0.01)
        assertEquals(0.0, middleGray.a, 0.01)
        assertEquals(0.0, middleGray.b, 0.01)
    }

    @Test
    fun shouldTreatEightBitSamplesAsEightBitSrgb_whenConvertingFromSrgb8() {
        val white = ColorDeltaE2000.fromSrgb8(255, 255, 255)
        assertEquals(100.0, white.lightness, 0.01)

        val mid = ColorDeltaE2000.fromSrgb8(128, 128, 128)
        assertEquals(53.585, mid.lightness, 0.01)
    }
}
