package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [ForegroundColorEstimator] — the FMGE local 2x2 system that removes the old
 * background colour from the transition band.
 */
class ForegroundColorEstimatorTest {

    @Test
    fun shouldMoveTheForegroundTowardTheTrueColour_whenAlphaIsMixed() {
        val width = 48
        val height = 16
        val image = FloatArray(width * height * 3)
        val alpha = FloatArray(width * height)
        val trueForeground = floatArrayOf(0.9f, 0.2f, 0.1f)
        val trueBackground = floatArrayOf(0.1f, 0.2f, 0.8f)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val a = column.toFloat() / (width - 1)
                alpha[index] = a
                for (channel in 0 until 3) {
                    image[index * 3 + channel] =
                        a * trueForeground[channel] + (1.0f - a) * trueBackground[channel]
                }
            }
        }

        val estimate = ForegroundColorEstimator.initialize(image, alpha, width, height)

        val center = (height / 2) * width + (width / 2)
        val estimated = floatArrayOf(
            estimate.foregroundAt(center, 0),
            estimate.foregroundAt(center, 1),
            estimate.foregroundAt(center, 2)
        )
        val source = floatArrayOf(
            image[center * 3],
            image[center * 3 + 1],
            image[center * 3 + 2]
        )
        assertTrue(
            "FMGE should decontaminate the mixed pixel: F=${estimated.contentToString()} " +
                "I=${source.contentToString()}",
            error(estimated, trueForeground) < error(source, trueForeground)
        )
        assertTrue(estimate.foreground.all { it.isFinite() && it in 0.0f..1.0f })
        assertTrue(estimate.background.all { it.isFinite() && it in 0.0f..1.0f })
    }

    @Test
    fun shouldRefineTheBandFromAProxyInitialisation_whenRoundsAreLimited() {
        val width = 32
        val height = 8
        val image = FloatArray(width * height * 3) { 0.5f }
        val alpha = FloatArray(width * height) { index ->
            val column = index % width
            if (column < width / 2) 0.0f else 1.0f
        }
        val initial = ForegroundEstimate(
            width = width,
            height = height,
            foreground = FloatArray(width * height * 3) { 0.2f },
            background = FloatArray(width * height * 3) { 0.8f }
        )

        val refined = ForegroundColorEstimator.refineBand(
            image = image,
            alpha = alpha,
            initial = initial,
            width = width,
            height = height
        )

        assertEquals(width, refined.width)
        assertEquals(height, refined.height)
        assertTrue(refined.foreground.all { it.isFinite() && it in 0.0f..1.0f })
        assertTrue(refined.background.all { it.isFinite() && it in 0.0f..1.0f })
    }

    @Test
    fun shouldReturnTheImageAsForeground_whenTheSolverCannotAllocate() {
        val width = 8
        val height = 4
        val image = FloatArray(width * height * 3) { 0.25f }

        val fallback = ForegroundColorEstimator.fallback(image, width, height)

        assertEquals(image.size, fallback.foreground.size)
        assertTrue(fallback.foreground.all { it == 0.25f })
        assertTrue(fallback.background.all { it == 0.0f })
    }

    @Test
    fun shouldUseTheSmallScaleIterationCount_whenTheCoarsestLevelIsSmall() {
        val width = 16
        val height = 16
        val image = FloatArray(width * height * 3) { index -> (index % 7) / 7.0f }
        val alpha = FloatArray(width * height) { index -> (index % width) / (width - 1f) }

        val estimate = ForegroundColorEstimator.initialize(image, alpha, width, height)

        assertTrue(estimate.foreground.all { it.isFinite() && it in 0.0f..1.0f })
    }

    private fun error(left: FloatArray, right: FloatArray): Float =
        left.indices.sumOf { kotlin.math.abs(left[it] - right[it]).toDouble() }.toFloat()
}
