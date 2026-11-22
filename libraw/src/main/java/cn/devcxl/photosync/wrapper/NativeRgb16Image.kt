package cn.devcxl.photosync.wrapper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

import timber.log.Timber

/**
 * Retained native `RGB16` copy of a RAW frame, used by the Tier 2 bake residency.
 *
 * The pixels stay in native memory (owned by the LibRaw allocation that produced them), so a 61MP
 * sensor costs `~349 MiB` instead of the `~700 MiB` an `RGBA16F` copy would need. Bands extract
 * `<= 2560 x 2560` sub-rectangles on demand, which is how full-resolution exports exceed
 * `GL_MAX_TEXTURE_SIZE` without ever allocating a full-frame texture.
 *
 * Instances are created by [NativeRgb16.decode]; call [close] to release the native pixels.
 */
class NativeRgb16Image internal constructor(
    private val handle: Long,
    val width: Int,
    val height: Int,
    val autoBrightGain: Float,
    val effectiveIso: Float
) : AutoCloseable {

    @Volatile
    private var released = false

    /** Longest image edge in pixels. */
    val longEdge: Int get() = max(width, height)

    /**
     * Converts the source sub-rectangle into interleaved `RGBA16F` texels.
     *
     * @param x0 Left column of the slice in source pixels.
     * @param y0 Top row of the slice in source pixels.
     * @param width Slice width; `x0 + width` must not exceed [width].
     * @param height Slice height; `y0 + height` must not exceed [height].
     * @param reuse Optional direct buffer of at least `width * height * 8` bytes to fill.
     * @return The filled buffer, or `null` when the slice is out of bounds or the image is closed.
     */
    fun extractRgba16fSlice(
        x0: Int,
        y0: Int,
        width: Int,
        height: Int,
        reuse: ByteBuffer? = null
    ): ByteBuffer? {
        if (released || width <= 0 || height <= 0) return null
        if (x0 < 0 || y0 < 0 || x0 + width > this.width || y0 + height > this.height) {
            Timber.w("Rejected RGB16 slice [%d,%d %dx%d] outside %dx%d", x0, y0, width, height,
                this.width, this.height)
            return null
        }
        val byteCount = width * height * BYTES_PER_TEXEL
        val target = reuse?.takeIf { it.isDirect && it.capacity() >= byteCount }
            ?: ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        target.rewind()
        val ok = NativeRgb16.nativeExtractSlice(handle, x0, y0, width, height, target)
        if (!ok) {
            Timber.e("Native RGB16 slice extraction failed for [%d,%d %dx%d]", x0, y0, width, height)
            return null
        }
        return target
    }

    /**
     * Area-downsamples the whole frame into a `<= maxLongEdge` `RGBA16F` buffer for the Pre-pass.
     *
     * @param maxLongEdge Longest edge of the returned proxy.
     * @return The proxy buffer, or `null` when the image is closed or the conversion failed.
     */
    fun extractRgba16fScaled(maxLongEdge: Int): LinearRawBuffer? {
        if (released || maxLongEdge <= 0) return null
        val scale = if (longEdge <= maxLongEdge) 1.0 else maxLongEdge.toDouble() / longEdge
        val targetWidth = max(1, (width * scale).roundToInt())
        val targetHeight = max(1, (height * scale).roundToInt())
        val target = ByteBuffer.allocateDirect(targetWidth * targetHeight * BYTES_PER_TEXEL)
            .order(ByteOrder.nativeOrder())
        val ok = NativeRgb16.nativeExtractScaled(handle, targetWidth, targetHeight, target)
        if (!ok) return null
        return LinearRawBuffer(
            width = targetWidth,
            height = targetHeight,
            autoBrightGain = autoBrightGain,
            effectiveIso = effectiveIso,
            rgba16fBuffer = target
        )
    }

    /** Releases the native pixels. Safe to call more than once. */
    override fun close() {
        if (released) return
        released = true
        NativeRgb16.nativeRelease(handle)
    }

    private companion object {
        const val BYTES_PER_TEXEL = 8
    }
}

/**
 * JNI entry points of the native `RGB16` residency store.
 *
 * Public only so `:editor` can open the Tier 2 source and probe RAW headers; the `native*`
 * members are not part of the module contract.
 */
object NativeRgb16 {

    init {
        System.loadLibrary("raw_wrapper")
    }

    /**
     * Decodes [path] into a retained native `RGB16` image.
     *
     * @param halfSize Enables LibRaw `half_size = 1` for Tier 3 exports.
     */
    fun decode(path: String, halfSize: Boolean): NativeRgb16Image? =
        nativeDecode(path, halfSize)

    /**
     * Reads the RAW frame dimensions without demosaicing.
     *
     * @return `width to height`, or `null` when the header cannot be opened.
     */
    fun probeDimensions(path: String): Pair<Int, Int>? {
        val dimensions = nativeProbeDimensions(path) ?: return null
        if (dimensions.size < 2 || dimensions[0] <= 0 || dimensions[1] <= 0) return null
        return dimensions[0] to dimensions[1]
    }

    external fun nativeDecode(path: String, halfSize: Boolean): NativeRgb16Image?

    external fun nativeExtractSlice(
        handle: Long,
        x0: Int,
        y0: Int,
        width: Int,
        height: Int,
        destination: ByteBuffer
    ): Boolean

    external fun nativeExtractScaled(
        handle: Long,
        targetWidth: Int,
        targetHeight: Int,
        destination: ByteBuffer
    ): Boolean

    external fun nativeRelease(handle: Long)

    external fun nativeProbeDimensions(path: String): IntArray?
}
