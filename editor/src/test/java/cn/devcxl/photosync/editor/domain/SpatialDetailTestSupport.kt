package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared fixtures for the Pass C CPU-reference tests: deterministic synthetic frames plus the
 * luminance / chroma statistics each stage is judged on.
 */
internal object SpatialDetailTestSupport {

    fun params(
        clarity: Float = 0.0f,
        texture: Float = 0.0f,
        dehaze: Float = 0.0f,
        sharpAmount: Float = 0.0f,
        sharpRadius: Float = 1.0f,
        sharpThreshold: Float = 0.0f,
        sharpMasking: Float = 0.0f,
        nrLuma: Float = 0.0f,
        nrChroma: Float = 0.0f,
        nrDetail: Float = 0.5f
    ): SpatialDetailParams = SpatialDetailParams(
        clarity = clarity,
        texture = texture,
        dehaze = dehaze,
        sharpenAmount = sharpAmount,
        sharpenRadiusPx = sharpRadius,
        sharpenThreshold = sharpThreshold,
        sharpenMasking = sharpMasking,
        nrLuma = nrLuma,
        nrChroma = nrChroma,
        nrDetail = nrDetail
    )

    fun analysis(
        clarityBase: Float,
        textureBase: Float,
        transmission: Float,
        atmosphericLight: FloatArray = floatArrayOf(0.6f, 0.6f, 0.6f),
        grid: Int = 32
    ): PrepassMath.Analysis = PrepassMath.Analysis(
        atmosphericLight = atmosphericLight,
        transmissionLow = FloatArray(grid * grid) { transmission },
        clarityBase = FloatArray(grid * grid) { clarityBase },
        textureBase = FloatArray(grid * grid) { textureBase },
        gridSize = grid
    )

    fun noisyGrayFrame(width: Int, height: Int): FloatArray {
        val image = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val noise = 0.05f * sin(x * 1.7f) * cos(y * 2.3f)
                val base = (y * width + x) * 3
                image[base] = 0.5f + noise
                image[base + 1] = 0.5f + noise
                image[base + 2] = 0.5f + noise
            }
        }
        return image
    }

    fun hardEdgeFrame(width: Int, height: Int): FloatArray {
        val image = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (x < width / 2) 0.2f else 0.8f
                val base = (y * width + x) * 3
                image[base] = value
                image[base + 1] = value
                image[base + 2] = value
            }
        }
        return image
    }

    fun chromaMottledFrame(width: Int, height: Int): FloatArray {
        val image = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = (y * width + x) * 3
                val warm = (x + y) % 2 == 0
                image[base] = 0.5f
                image[base + 1] = if (warm) 0.56f else 0.44f
                image[base + 2] = if (warm) 0.44f else 0.56f
            }
        }
        return image
    }

    fun checkerboardFrame(width: Int, height: Int): FloatArray {
        val image = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if ((x + y) % 2 == 0) 0.3f else 0.7f
                val base = (y * width + x) * 3
                image[base] = value
                image[base + 1] = value
                image[base + 2] = value
            }
        }
        return image
    }

    fun meanLuma(image: FloatArray): Float {
        var sum = 0.0
        for (index in 0 until image.size / 3) {
            sum += ToneShaderMath.luminance(
                image[index * 3],
                image[index * 3 + 1],
                image[index * 3 + 2]
            )
        }
        return (sum / (image.size / 3)).toFloat()
    }

    fun meanAbsoluteLocalNoise(image: FloatArray, width: Int, height: Int): Float {
        val luma = ImageFilters.luminance(image)
        val mean = ImageFilters.boxMean(luma, width, height, 1)
        var sum = 0.0
        for (index in luma.indices) sum += abs(luma[index] - mean[index])
        return (sum / luma.size).toFloat()
    }

    fun edgeContrast(image: FloatArray, width: Int, height: Int): Float {
        var left = 0.0
        var right = 0.0
        var leftCount = 0
        var rightCount = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = image[(y * width + x) * 3]
                if (x < width / 4) {
                    left += value
                    leftCount++
                } else if (x >= width * 3 / 4) {
                    right += value
                    rightCount++
                }
            }
        }
        return (right / rightCount - left / leftCount).toFloat()
    }

    fun chromaSpread(image: FloatArray): Float {
        var spread = 0.0f
        for (index in 0 until image.size / 3) {
            val base = index * 3
            spread += abs(image[base + 2] - image[base + 1])
        }
        return spread / (image.size / 3)
    }
}
