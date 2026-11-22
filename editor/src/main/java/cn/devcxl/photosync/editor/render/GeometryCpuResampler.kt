package cn.devcxl.photosync.editor.render

import kotlin.math.floor
import kotlin.math.roundToInt

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.Uv

/**
 * CPU reference implementation of Pass A, used when the editor writes its preview cache.
 *
 * It evaluates exactly the same inverse homography and lateral-CA radial scaling as
 * [GeometryCaGlProgram], so the image the user saw in the editor matches the cached thumbnail
 * down to bilinear-sampling differences.
 */
internal object GeometryCpuResampler {

    /**
     * Renders the cropped, rotated, straightened and perspective-corrected frame of [source].
     *
     * @param source Upright source bitmap.
     * @param inverseMatrix `H^-1` from [GeometryMath.buildPassAInverseMatrix].
     * @param outputWidth Target width in pixels.
     * @param outputHeight Target height in pixels.
     * @param caRedK Red-channel radial coefficient.
     * @param caBlueK Blue-channel radial coefficient.
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
                targetPixels[row * width + column] = samplePixel(
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

    private fun samplePixel(
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
        val red = sampleChannel(sourcePixels, sourceWidth, sourceHeight, ca.red, RED_SHIFT)
        val green = sampleChannel(sourcePixels, sourceWidth, sourceHeight, ca.green, GREEN_SHIFT)
        val blue = sampleChannel(sourcePixels, sourceWidth, sourceHeight, ca.blue, BLUE_SHIFT)
        return OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
    }

    private fun sampleChannel(
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
