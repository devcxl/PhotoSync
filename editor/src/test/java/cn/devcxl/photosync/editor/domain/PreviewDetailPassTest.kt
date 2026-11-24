package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PreviewDetailPass] — the gallery preview cache must run the same Pass C chain as
 * the GL canvas, and must short-circuit to the untouched pixel array when nothing is enabled.
 */
class PreviewDetailPassTest {

    @Test
    fun shouldReturnTheSamePixels_whenSpatialDetailIsNeutral() {
        val pixels = grayscalePixels(32, 32) { x, _ -> if (x % 2 == 0) 0.4f else 0.6f }

        val out = PreviewDetailPass.apply(pixels, 32, 32, EditGraph())

        assertSame(pixels, out)
    }

    @Test
    fun shouldRaiseLocalContrast_whenClarityIsApplied() {
        val width = 64
        val height = 64
        val pixels = grayscalePixels(width, height) { x, y ->
            val structure = if (((x / 4) + (y / 4)) % 2 == 0) 0.06f else -0.06f
            0.45f + structure
        }
        val before = standardDeviation(pixels)

        val out = PreviewDetailPass.apply(
            pixels,
            width,
            height,
            EditGraph(detail = DetailOps(clarity = 100)),
            fullResolutionShortEdge = height
        )

        assertNotSame(pixels, out)
        val after = standardDeviation(out)
        assertTrue("Clarity must raise local contrast: before=$before after=$after", after > before)
        for (index in out.indices) {
            assertTrue("Channel out of range at $index", red(out[index]) in 0.0f..1.0f)
        }
    }

    @Test
    fun shouldKeepEveryPixelBounded_whenDehazeIsNegative() {
        val width = 32
        val height = 32
        val pixels = grayscalePixels(width, height) { x, _ -> 0.3f + 0.02f * (x % 5) }

        val out = PreviewDetailPass.apply(
            pixels,
            width,
            height,
            EditGraph(detail = DetailOps(dehaze = -60))
        )

        assertNotSame(pixels, out)
        var changed = 0
        for (index in pixels.indices) {
            val alpha = (out[index] ushr 24) and 0xFF
            assertTrue("Alpha must be preserved", alpha == 0xFF)
            assertTrue("Channel out of range at $index", red(out[index]) in 0.0f..1.0f)
            if (out[index] != pixels[index]) changed++
        }
        assertTrue("Negative dehaze must synthesise haze", changed > 0)
    }

    @Test
    fun shouldIncreaseNeighbourContrast_whenSharpeningIsRaised() {
        val width = 48
        val height = 48
        val pixels = grayscalePixels(width, height) { x, _ ->
            val distance = abs(x - width / 2)
            (0.5f - distance * 0.01f).coerceAtLeast(0.1f)
        }
        val before = neighbourContrast(pixels, width, height)

        val out = PreviewDetailPass.apply(
            pixels,
            width,
            height,
            EditGraph(detail = DetailOps(sharpen = SharpenOps(amount = 120, radius = 1.0f))),
            fullResolutionShortEdge = height
        )

        val after = neighbourContrast(out, width, height)
        assertTrue("Sharpening must steepen edges: before=$before after=$after", after > before)
    }

    private fun grayscalePixels(
        width: Int,
        height: Int,
        value: (Int, Int) -> Float
    ): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val v = (value(x, y).coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun red(pixel: Int): Float = ((pixel ushr 16) and 0xFF) / 255.0f

    private fun standardDeviation(pixels: IntArray): Float {
        var sum = 0.0
        var squares = 0.0
        for (pixel in pixels) {
            val value = red(pixel)
            sum += value
            squares += value.toDouble() * value.toDouble()
        }
        val mean = sum / pixels.size
        return kotlin.math.sqrt(kotlin.math.max(0.0, squares / pixels.size - mean * mean)).toFloat()
    }

    private fun neighbourContrast(pixels: IntArray, width: Int, height: Int): Float {
        var total = 0.0
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                total += abs(red(pixels[y * width + x + 1]) - red(pixels[y * width + x]))
                count++
            }
        }
        return (total / count).toFloat()
    }
}
