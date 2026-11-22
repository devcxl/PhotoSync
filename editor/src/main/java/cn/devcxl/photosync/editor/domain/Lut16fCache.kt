package cn.devcxl.photosync.editor.domain

/**
 * Dense `33 x 33 x 33` RGBA samples decoded from a `.lut16f` cache, used by the CPU preview path.
 *
 * Samples are stored blue-slowest (`b * size^2 + g * size + r`) with four components per texel so
 * the same buffer can be uploaded as `GL_RGBA16F`.
 */
internal class Lut3dSamples(val size: Int, val rgba: FloatArray) {

    init {
        require(size >= MIN_SIZE) { "A 3D LUT grid needs at least $MIN_SIZE samples per axis" }
        require(rgba.size == size * size * size * Lut16fCache.CHANNEL_COUNT) {
            "Expected ${size * size * size * Lut16fCache.CHANNEL_COUNT} floats, got ${rgba.size}"
        }
    }

    /** Copies the RGB triple of texel [index] into [out]. */
    fun rgb(index: Int, out: FloatArray) {
        val base = index * Lut16fCache.CHANNEL_COUNT
        out[0] = rgba[base]
        out[1] = rgba[base + 1]
        out[2] = rgba[base + 2]
    }

    private companion object {
        private const val MIN_SIZE = 2
    }
}

/**
 * Binary layout of a pre-compiled `33 x 33 x 33 RGBA16F` `.cube` cache.
 *
 * The file is an 8-byte ASCII magic header followed by `287,496` bytes of little-endian
 * half-float texels (alpha is always opaque), so the runtime never parses `.cube` text again.
 */
internal object Lut16fCache {

    /** Samples per axis of the pre-compiled cache. */
    const val GRID_SIZE = 33

    /** Components per texel (`r`, `g`, `b`, `a`). */
    const val CHANNEL_COUNT = 4

    /** Bytes of the magic header that precedes the texel payload. */
    const val HEADER_SIZE_BYTES = 8

    /** Bytes of one half-float component. */
    const val BYTES_PER_COMPONENT = 2

    /** Bytes of the RGBA16F texel payload (`33^3 * 4 * 2`). */
    const val DATA_SIZE_BYTES = GRID_SIZE * GRID_SIZE * GRID_SIZE * CHANNEL_COUNT * BYTES_PER_COMPONENT

    /** Total size of a `.lut16f` cache file: header plus payload. */
    const val TOTAL_SIZE_BYTES = HEADER_SIZE_BYTES + DATA_SIZE_BYTES

    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val MAGIC_TEXT = "PSLUT16F"

    private val MAGIC: ByteArray = MAGIC_TEXT.toByteArray(Charsets.US_ASCII)

    /** Returns the 8-byte ASCII magic header every cache file starts with. */
    fun headerBytes(): ByteArray = MAGIC.copyOf()

    /** True when [bytes] has the exact cache size and a matching magic header. */
    fun isValid(bytes: ByteArray): Boolean =
        bytes.size == TOTAL_SIZE_BYTES && bytes.copyOfRange(0, HEADER_SIZE_BYTES).contentEquals(MAGIC)

    /** Decodes the half-float payload of [bytes] into CPU-readable samples, or `null` if invalid. */
    fun samplesFromCache(bytes: ByteArray): Lut3dSamples? {
        if (!isValid(bytes)) return null
        val componentCount = GRID_SIZE * GRID_SIZE * GRID_SIZE * CHANNEL_COUNT
        val rgba = FloatArray(componentCount)
        var offset = HEADER_SIZE_BYTES
        for (component in 0 until componentCount) {
            rgba[component] = HalfFloatBits.fromBits(readHalfBits(bytes, offset))
            offset += BYTES_PER_COMPONENT
        }
        return Lut3dSamples(GRID_SIZE, rgba)
    }

    /** Writes [bits] at [offset] in little-endian order and returns the advanced offset. */
    fun writeHalfBits(bytes: ByteArray, offset: Int, bits: Short): Int {
        bytes[offset] = (bits.toInt() and BYTE_MASK).toByte()
        bytes[offset + 1] = ((bits.toInt() ushr BITS_PER_BYTE) and BYTE_MASK).toByte()
        return offset + BYTES_PER_COMPONENT
    }

    private fun readHalfBits(bytes: ByteArray, offset: Int): Short {
        val low = bytes[offset].toInt() and BYTE_MASK
        val high = bytes[offset + 1].toInt() and BYTE_MASK
        return ((high shl BITS_PER_BYTE) or low).toShort()
    }
}
