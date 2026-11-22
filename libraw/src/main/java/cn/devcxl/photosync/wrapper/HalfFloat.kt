package cn.devcxl.photosync.wrapper

import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * IEEE 754 binary16 (`half_float`) codec matching the layout of `GL_HALF_FLOAT` texel uploads.
 *
 * Conversions are round-to-nearest-even with subnormal flush thresholds identical to the
 * native `raw_linear16.cpp` helper, so CPU-side downsampling and GPU texels agree bit-for-bit
 * for every value that survives the round trip.
 */
internal object HalfFloat {

    private const val SIGN_MASK = 0x8000
    private const val EXPONENT_MASK = 0x7C00
    private const val MANTISSA_MASK = 0x03FF
    private const val EXPONENT_INFINITY = 0x1F

    /**
     * Encodes [value] as binary16 bits.
     */
    fun toHalfBits(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and SIGN_MASK
        val rawExponent = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7FFFFF
        if (rawExponent == 0xFF) {
            return (sign or EXPONENT_MASK or (mantissa ushr 13)).toShort()
        }
        val exponent = rawExponent - 127 + 15
        if (exponent >= EXPONENT_INFINITY) {
            return (sign or EXPONENT_MASK).toShort()
        }
        if (exponent <= 0) {
            return (sign or encodeSubnormal(mantissa, exponent)).toShort()
        }
        var half = (exponent shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) != 0)) {
            half++
        }
        return (sign or half).toShort()
    }

    /**
     * Decodes binary16 [bits] back into a single-precision float.
     */
    fun fromHalfBits(bits: Short): Float {
        val raw = bits.toInt() and 0xFFFF
        val sign = if (raw and SIGN_MASK != 0) -1.0f else 1.0f
        val exponent = (raw ushr 10) and EXPONENT_INFINITY
        val mantissa = raw and MANTISSA_MASK
        val magnitude = when {
            exponent == 0 -> mantissa * 2.0f.pow(-24)
            exponent == EXPONENT_INFINITY ->
                if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (mantissa + 1024) * 2.0f.pow(exponent - 25)
        }
        return sign * magnitude
    }

    /**
     * Appends the binary16 encoding of [value] to [buffer] in the buffer's byte order.
     */
    fun writeHalf(buffer: ByteBuffer, value: Float) {
        buffer.putShort(toHalfBits(value))
    }

    private fun encodeSubnormal(mantissa: Int, exponent: Int): Int {
        if (exponent < -10) return 0
        val shift = 14 - exponent
        val withHiddenBit = mantissa or 0x800000
        var half = withHiddenBit ushr shift
        val remainder = withHiddenBit and ((1 shl shift) - 1)
        val halfway = 1 shl (shift - 1)
        if (remainder > halfway || (remainder == halfway && (half and 1) != 0)) {
            half++
        }
        return half
    }
}
