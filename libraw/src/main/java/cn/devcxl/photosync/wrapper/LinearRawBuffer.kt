package cn.devcxl.photosync.wrapper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decoded 16-bit linear-light RAW pixels produced by [RawWrapper.decodeToLinearRgba16F].
 *
 * Pixels are stored as four interleaved IEEE 754 binary16 (`half_float`) channels per pixel in
 * `GL_RGBA` order, which maps one-to-one onto a `GL_RGBA16F` texture upload. The buffer is a
 * direct (off-heap) buffer owned by the JVM garbage collector, so no native memory needs to be
 * released by callers.
 *
 * @property width Pixel width of [rgba16fBuffer].
 * @property height Pixel height of [rgba16fBuffer].
 * @property autoBrightGain Histogram auto-brightening gain the browse page applies before tone
 *   mapping; `1.0` when no brightening is needed and capped at `4.0` (+2.0 EV).
 * @property effectiveIso Camera ISO multiplied by [autoBrightGain].
 * @property rgba16fBuffer Little-endian half-float RGBA samples, `width * height * 8` bytes.
 */
data class LinearRawBuffer(
    val width: Int,
    val height: Int,
    val autoBrightGain: Float,
    val effectiveIso: Float,
    val rgba16fBuffer: ByteBuffer
) {

    /**
     * Returns a box-filtered copy whose longest edge is at most [maxLongEdge], preserving
     * [autoBrightGain] and [effectiveIso].
     *
     * Downsampling in 16-bit linear light keeps the drag proxy visually identical to the
     * full preview without paying for a second LibRaw demosaic. Returns `this` when the buffer
     * already fits or when [maxLongEdge] is non-positive.
     */
    fun downscaledTo(maxLongEdge: Int): LinearRawBuffer {
        val target = fitTargetSize(maxLongEdge) ?: return this
        val targetBuffer = ByteBuffer.allocateDirect(target.first * target.second * BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        val source = rgba16fBuffer.duplicate().order(ByteOrder.nativeOrder())
        source.rewind()
        val accumulator = FloatArray(CHANNELS)
        for (y in 0 until target.second) {
            val sourceY = sourceRange(y, height, target.second)
            for (x in 0 until target.first) {
                val sourceX = sourceRange(x, width, target.first)
                averageBlock(source, sourceX, sourceY, accumulator)
                writeAccumulatedPixel(targetBuffer, accumulator)
            }
        }
        targetBuffer.rewind()
        return copy(
            width = target.first,
            height = target.second,
            rgba16fBuffer = targetBuffer
        )
    }

    private fun fitTargetSize(maxLongEdge: Int): Pair<Int, Int>? {
        if (maxLongEdge <= 0) return null
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return null
        val scale = maxLongEdge.toDouble() / longEdge
        return Pair(
            max(1, (width * scale).roundToInt()),
            max(1, (height * scale).roundToInt())
        )
    }

    private fun sourceRange(index: Int, sourceLength: Int, targetLength: Int): IntRange {
        val start = (index.toLong() * sourceLength / targetLength).toInt()
        val end = max(start + 1, ((index + 1).toLong() * sourceLength / targetLength).toInt())
        return start until minOf(end, sourceLength)
    }

    private fun averageBlock(
        source: ByteBuffer,
        xRange: IntRange,
        yRange: IntRange,
        outAverage: FloatArray
    ) {
        var red = 0.0f
        var green = 0.0f
        var blue = 0.0f
        var samples = 0
        for (y in yRange) {
            val rowOffset = y * width
            for (x in xRange) {
                source.position((rowOffset + x) * BYTES_PER_PIXEL)
                red += HalfFloat.fromHalfBits(source.short)
                green += HalfFloat.fromHalfBits(source.short)
                blue += HalfFloat.fromHalfBits(source.short)
                samples++
            }
        }
        val divisor = samples.coerceAtLeast(1).toFloat()
        outAverage[0] = red / divisor
        outAverage[1] = green / divisor
        outAverage[2] = blue / divisor
    }

    private fun writeAccumulatedPixel(target: ByteBuffer, averages: FloatArray) {
        HalfFloat.writeHalf(target, averages[0])
        HalfFloat.writeHalf(target, averages[1])
        HalfFloat.writeHalf(target, averages[2])
        HalfFloat.writeHalf(target, 1.0f)
    }

    /**
     * Decodes the four interleaved half-float channels of [pixelIndex] into [destination]
     * (`RGBA` order).
     *
     * [destination] must hold at least four floats. The shared buffer view is guarded so the GL
     * thread (8-bit texture fallback) and the preview-cache writer can read concurrently.
     */
    fun readPixel(pixelIndex: Int, destination: FloatArray) {
        require(destination.size >= CHANNELS) { "destination must hold $CHANNELS floats" }
        val view = pixelView
        synchronized(view) {
            for (channel in 0 until CHANNELS) {
                view.position(pixelIndex * BYTES_PER_PIXEL + channel * 2)
                destination[channel] = HalfFloat.fromHalfBits(view.short)
            }
        }
    }

    private val pixelView: ByteBuffer by lazy {
        rgba16fBuffer.duplicate().order(ByteOrder.nativeOrder())
    }

    companion object {
        /** Bytes per `GL_RGBA16F` texel: four binary16 channels. */
        const val BYTES_PER_PIXEL = 8

        private const val CHANNELS = 4

        /**
         * Builds a buffer from interleaved `RGBA` single-precision samples in `[0, 1]`, encoding
         * them as binary16. Intended for synthetic sources and tests.
         */
        fun fromSamples(
            width: Int,
            height: Int,
            autoBrightGain: Float,
            effectiveIso: Float,
            rgbaSamples: FloatArray
        ): LinearRawBuffer {
            require(width > 0 && height > 0) { "LinearRawBuffer needs a positive size" }
            require(rgbaSamples.size == width * height * CHANNELS) {
                "Expected ${width * height * CHANNELS} samples, got ${rgbaSamples.size}"
            }
            val buffer = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
                .order(ByteOrder.nativeOrder())
            for (sample in rgbaSamples) {
                HalfFloat.writeHalf(buffer, sample)
            }
            buffer.rewind()
            return LinearRawBuffer(
                width = width,
                height = height,
                autoBrightGain = autoBrightGain,
                effectiveIso = effectiveIso,
                rgba16fBuffer = buffer
            )
        }
    }
}
