package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PrepassMath] — the 1024px full-image Pre-pass contract:
 * `7 x 7` dark channel on a `256 x 256` grid, atmospheric light from the brightest `0.1%`,
 * guided-smoothed coarse transmission with `omega <= 0.95`, and the packed `2%` / `0.3%`
 * short-edge luminance guide pyramid.
 */
class PrepassMathTest {

    @Test
    fun shouldAnaliseOnA256GridAndPackThreeChannels_whenFrameIs1024px() {
        val width = 1024
        val height = 1024
        val frame = syntheticFrame(width, height)

        val analysis = PrepassMath.analyze(frame, width, height)

        assertEquals(PrepassMath.ANALYSIS_GRID, analysis.gridSize)
        assertEquals(256 * 256 * 4, analysis.packedGuides.size)
        assertEquals(256 * 256, analysis.transmissionLow.size)
        assertEquals(3, analysis.atmosphericLight.size)
    }

    @Test
    fun shouldPickTheBrightestDarkChannelAsAtmosphericLight_whenFrameHasABrightSky() {
        val width = 512
        val height = 512
        val frame = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val sky = y < height / 10
            val base = if (sky) 0.86f else 0.02f
            for (x in 0 until width) {
                val index = (y * width + x) * 3
                frame[index] = base
                frame[index + 1] = base + 0.01f
                frame[index + 2] = base + 0.02f
            }
        }

        val analysis = PrepassMath.analyze(frame, width, height)

        for (channel in 0..2) {
            assertTrue(
                "A[$channel] must follow the bright sky: ${analysis.atmosphericLight[channel]}",
                analysis.atmosphericLight[channel] >= 0.8f
            )
        }
    }

    @Test
    fun shouldSettleTransmissionAtOneMinusOmega_whenFrameIsUniformHaze() {
        val width = 256
        val height = 256
        val frame = FloatArray(width * height * 3) { 0.6f }

        val analysis = PrepassMath.analyze(frame, width, height)

        assertEquals(0.6f, analysis.atmosphericLight[0], 1e-3f)
        assertEquals(
            1.0f - PrepassMath.DEFAULT_OMEGA,
            analysis.transmissionAt(0.5f, 0.5f),
            2e-3f
        )
        for (value in analysis.transmissionLow) {
            assertTrue("Transmission must stay in [0, 1]: $value", value in 0.0f..1.0f)
        }
    }

    @Test
    fun shouldClampOmegaToTheHazeRetentionContract_whenOmegaExceedsTheMaximum() {
        val frame = FloatArray(256 * 256 * 3) { 0.5f }

        val clamped = PrepassMath.analyze(frame, 256, 256, omega = 5.0f)
        val contract = PrepassMath.analyze(frame, 256, 256, omega = PrepassMath.MAX_OMEGA)

        assertEquals(
            contract.transmissionAt(0.3f, 0.7f),
            clamped.transmissionAt(0.3f, 0.7f),
            1e-5f
        )
        assertEquals(1.0f - PrepassMath.MAX_OMEGA, clamped.transmissionAt(0.5f, 0.5f), 2e-3f)
    }

    @Test
    fun shouldBuildAProgressivelySmootherGuidePyramid_whenFrameCarriesFineDetail() {
        val width = 1024
        val height = 1024
        val frame = detailedFrame(width, height)

        val analysis = PrepassMath.analyze(frame, width, height)

        val clarityResidual = highFrequencyResidual(analysis.clarityBase, analysis.gridSize)
        val textureResidual = highFrequencyResidual(analysis.textureBase, analysis.gridSize)
        assertTrue(
            "The 2% clarity guide must carry less fine structure than the 0.3% texture guide: " +
                "clarity=$clarityResidual texture=$textureResidual",
            clarityResidual < textureResidual * 0.5f
        )
        for (value in analysis.clarityBase + analysis.textureBase) {
            assertTrue("Guide values must stay in [0, 1]: $value", value in -1e-4f..1.0001f)
        }
    }

    @Test
    fun shouldGiveADarkerFrameASmallerAtmosphericLight_whenOnlyBrightnessChanges() {
        val bright = PrepassMath.analyze(FloatArray(256 * 256 * 3) { 0.75f }, 256, 256)
        val dark = PrepassMath.analyze(FloatArray(256 * 256 * 3) { 0.15f }, 256, 256)

        assertTrue(bright.atmosphericLight[0] > dark.atmosphericLight[0])
        assertTrue(dark.atmosphericLight[0] >= PrepassMath.ATMOSPHERIC_FLOOR)
    }

    @Test
    fun shouldReturnGuidesIndependentOfDehaze_whenDehazeIsNotUsed() {
        val frame = detailedFrame(256, 256)

        val analysis = PrepassMath.analyze(frame, 256, 256)

        assertEquals(256, analysis.gridSize)
        assertTrue(analysis.clarityBase.isNotEmpty())
        assertTrue(analysis.textureBase.isNotEmpty())
        assertEquals(analysis.transmissionLow.size, analysis.clarityBase.size)
    }

    private fun syntheticFrame(width: Int, height: Int): FloatArray {
        val frame = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = (y * width + x) * 3
                val gradient = y.toFloat() / height.toFloat()
                frame[index] = 0.75f * (1.0f - gradient) + 0.08f
                frame[index + 1] = 0.70f * (1.0f - gradient) + 0.07f
                frame[index + 2] = 0.62f * (1.0f - gradient) + 0.06f
            }
        }
        return frame
    }

    private fun detailedFrame(width: Int, height: Int): FloatArray {
        val frame = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = (y * width + x) * 3
                val gradient = 0.35f + 0.35f * y.toFloat() / height.toFloat()
                val checker = if (((x / 8) + (y / 8)) % 2 == 0) 0.12f else -0.12f
                val value = (gradient + checker).coerceIn(0.0f, 1.0f)
                frame[index] = value
                frame[index + 1] = value
                frame[index + 2] = value
            }
        }
        return frame
    }

    private fun highFrequencyResidual(values: FloatArray, grid: Int): Float {
        val smooth = ImageFilters.boxMean(values, grid, grid, 4)
        var total = 0.0
        for (index in values.indices) total += kotlin.math.abs(values[index] - smooth[index])
        return (total / values.size).toFloat()
    }
}
