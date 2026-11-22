package cn.devcxl.photosync.editor.render

import kotlin.math.floor
import kotlin.math.roundToInt

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * CPU reference implementation of Pass A, used when the editor writes its preview cache.
 *
 * Evaluates the exact same inverse homography and lateral-CA radial scaling as
 * [GeometryCaGlProgram] on both 8-bit sRGB [Bitmap]s and 16-bit linear [LinearRawBuffer]s.
 */
internal object GeometryCpuResampler {

    private const val CHANNELS = 4

    /**
     * Renders the cropped, rotated, straightened and perspective-corrected frame of an 8-bit
     * [source] bitmap.
     */
    fun resample(
        source: Bitmap,
        inverseMatrix: Mat3,
        outputWidth: Int,
        outputHeight: Int,
        caRedK: Float,
        caBlueK: Float
    ): Bitmap {
        val width = outputWidth.coerceAtLeast(1)
        val height = outputHeight.coerceAtLeast(1)
        val sourceWidth = source.width
        val sourceHeight = source.height
        val sourcePixels = IntArray(sourceWidth * sourceHeight)
        source.getPixels(sourcePixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)

        val targetPixels = IntArray(width * height)
        for (row in 0 until height) {
            val v = (row + 0.5f) / height
            for (column in 0 until width) {
                val u = (column + 0.5f) / width
                targetPixels[row * width + column] = sampleBitmapPixel(
                    sourcePixels = sourcePixels,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    inverseMatrix = inverseMatrix,
                    outputU = u,
                    outputV = v,
                    caRedK = caRedK,
                    caBlueK = caBlueK
                )
            }
        }
        return Bitmap.createBitmap(targetPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Renders the cropped, rotated, straightened, perspective-corrected and CA-corrected frame
     * of a 16-bit linear [LinearRawBuffer], preserving scene-linear float values above `1.0`.
     */
    fun resampleLinear(
        source: LinearRawBuffer,
        inverseMatrix: Mat3,
        outputWidth: Int,
        outputHeight: Int,
        caRedK: Float,
        caBlueK: Float
    ): LinearRawBuffer {
        val width = outputWidth.coerceAtLeast(1)
        val height = outputHeight.coerceAtLeast(1)
        val samples = FloatArray(width * height * CHANNELS)
        val p00 = FloatArray(CHANNELS)
        val p10 = FloatArray(CHANNELS)
        val p01 = FloatArray(CHANNELS)
        val p11 = FloatArray(CHANNELS)
        var outIndex = 0
        for (row in 0 until height) {
            val v = (row + 0.5f) / height
            for (column in 0 until width) {
                val u = (column + 0.5f) / width
                val preCrop = inverseMatrix.map(u, v)
                val ca = GeometryMath.applyLateralCaSampleUv(preCrop, caRedK, caBlueK)
                samples[outIndex++] = sampleLinearChannel(source, ca.red, 0, p00, p10, p01, p11)
                samples[outIndex++] = sampleLinearChannel(source, ca.green, 1, p00, p10, p01, p11)
                samples[outIndex++] = sampleLinearChannel(source, ca.blue, 2, p00, p10, p01, p11)
                samples[outIndex++] = 1.0f
            }
        }
        return LinearRawBuffer.fromSamples(
            width = width,
            height = height,
            autoBrightGain = source.autoBrightGain,
            effectiveIso = source.effectiveIso,
            rgbaSamples = samples
        )
    }

    private fun sampleLinearChannel(
        source: LinearRawBuffer,
        uv: Uv,
        channel: Int,
        p00: FloatArray,
        p10: FloatArray,
        p01: FloatArray,
        p11: FloatArray
    ): Float {
        val w = source.width
        val h = source.height
        val x = uv.u.coerceIn(0.0f, 1.0f) * (w - 1).coerceAtLeast(0)
        val y = uv.v.coerceIn(0.0f, 1.0f) * (h - 1).coerceAtLeast(0)
        val x0 = floor(x).toInt().coerceIn(0, w - 1)
        val y0 = floor(y).toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0
        val fy = y - y0
        source.readPixel(y0 * w + x0, p00)
        source.readPixel(y0 * w + x1, p10)
        source.readPixel(y1 * w + x0, p01)
        source.readPixel(y1 * w + x1, p11)
        val top = p00[channel] + (p10[channel] - p00[channel]) * fx
        val bottom = p01[channel] + (p11[channel] - p01[channel]) * fx
        return top + (bottom - top) * fy
    }

    private fun sampleBitmapPixel(
        sourcePixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        inverseMatrix: Mat3,
        outputU: Float,
        outputV: Float,
        caRedK: Float,
        caBlueK: Float
    ): Int {
        val preCrop = inverseMatrix.map(outputU, outputV)
        val ca = GeometryMath.applyLateralCaSampleUv(preCrop, caRedK, caBlueK)
        val red = sampleBitmapChannel(sourcePixels, sourceWidth, sourceHeight, ca.red, RED_SHIFT)
        val green = sampleBitmapChannel(sourcePixels, sourceWidth, sourceHeight, ca.green, GREEN_SHIFT)
        val blue = sampleBitmapChannel(sourcePixels, sourceWidth, sourceHeight, ca.blue, BLUE_SHIFT)
        return OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
    }

    private fun sampleBitmapChannel(
        pixels: IntArray,
        width: Int,
        height: Int,
        uv: Uv,
        shift: Int
    ): Int {
        val x = uv.u.coerceIn(0.0f, 1.0f) * (width - 1).coerceAtLeast(0)
        val y = uv.v.coerceIn(0.0f, 1.0f) * (height - 1).coerceAtLeast(0)
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fractionX = x - x0
        val fractionY = y - y0
        val topLeft = channel(pixels[y0 * width + x0], shift)
        val topRight = channel(pixels[y0 * width + x1], shift)
        val bottomLeft = channel(pixels[y1 * width + x0], shift)
        val bottomRight = channel(pixels[y1 * width + x1], shift)
        val top = topLeft + (topRight - topLeft) * fractionX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fractionX
        val blended = top + (bottom - top) * fractionY
        return blended.roundToInt().coerceIn(0, 255)
    }

    private fun channel(pixel: Int, shift: Int): Float = ((pixel ushr shift) and 0xFF).toFloat()

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BLUE_SHIFT = 0
    private const val OPAQUE_ALPHA = -0x1000000
}
